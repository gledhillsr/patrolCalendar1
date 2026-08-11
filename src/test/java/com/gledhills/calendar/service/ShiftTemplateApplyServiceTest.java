package com.gledhills.calendar.service;

import com.gledhills.calendar.controller.ServletAdapter;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.store.ShiftTemplatePatrollers;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static com.gledhills.calendar.service.ShiftTemplateApplyService.ConflictMode.OVERWRITE;
import static com.gledhills.calendar.service.ShiftTemplateApplyService.ConflictMode.SKIP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for applying a day-of-week template across a date range.
 *
 * This writes real assignment rows, so it works inside March 2025 - a month with nothing saved and
 * outside every month the calendar goldens are recorded for - and clears the month plus the
 * template's patrollers afterwards.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ShiftTemplateApplyServiceTest {

  private static final String TEMPLATE = "Saturday";
  private static final String MEMBER_ID = "200401";
  private static final int YEAR = 2025;
  private static final int MONTH = 3; //March, 1 based

  /** March 2025 Saturdays: 1, 8, 15, 22, 29. */
  private static final LocalDate FROM = LocalDate.of(YEAR, MONTH, 1);
  private static final LocalDate TO = LocalDate.of(YEAR, MONTH, 31);

  @Autowired
  private ShiftTemplateApplyService service;

  @AfterEach
  void cleanUp() {
    withPatrolData((log, patrolData) -> {
      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH)) {
        patrolData.deleteAssignment(assignment);
      }
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(TEMPLATE)) {
          ShiftTemplatePatrollers.deleteShift(log, patrolData.getConnection(), shift.getEventName());
        }
      }
    });
  }

  /** Only the day-of-week names can be applied, which is what enables the button on the page. */
  @Test
  void onlyTheDayOfWeekNamesAreApplicable() {
    for (String day : new String[] {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday",
                                    "Friday", "Saturday"}) {
      assertTrue(service.isDayOfWeekTemplate(day), day + " should be applicable");
    }
    assertFalse(service.isDayOfWeekTemplate("Midnight"), "a named template is not a weekday");
    assertFalse(service.isDayOfWeekTemplate("Closed"), "'Closed' is reserved, not a weekday");
    assertFalse(service.isDayOfWeekTemplate(null), "no selection is not a weekday");
  }

  @Test
  void previewFindsEveryMatchingWeekdayInTheRange() {
    withPatrolData((log, patrolData) -> {
      ShiftTemplateApplyService.Preview preview = preview(patrolData, SKIP, log);

      assertTrue(preview.isApplicable(), TEMPLATE + " should be applicable");
      assertEquals(List.of(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 8),
                           LocalDate.of(2025, 3, 15), LocalDate.of(2025, 3, 22),
                           LocalDate.of(2025, 3, 29)),
                   preview.daysToWrite(),
                   "March 2025 has five Saturdays");
      assertEquals(0, preview.getDaysInConflictCount(), "March 2025 starts empty");
    });
  }

  /** Preview must not write anything - it is the "are you sure" step. */
  @Test
  void previewWritesNothing() {
    withPatrolData((log, patrolData) -> {
      preview(patrolData, SKIP, log);

      assertEquals(0, patrolData.readSortedAssignments(YEAR, MONTH).size(),
                   "preview must leave the calendar untouched");
    });
  }

  @Test
  void applyWritesTheTemplatePatrollersOntoEveryMatchingDay() {
    withPatrolData((log, patrolData) -> {
      givenTemplateRowZeroHolds(patrolData, log, MEMBER_ID);

      int written = apply(patrolData, SKIP, log);

      assertEquals(5, written, "five Saturdays should have been written");
      List<Integer> daysWithMember = new ArrayList<>();
      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH)) {
        if (MEMBER_ID.equals(assignment.getPosID(0))) {
          daysWithMember.add(assignment.getDay());
        }
      }
      assertEquals(List.of(1, 8, 15, 22, 29), daysWithMember,
                   "the template patroller should be on every Saturday");
    });
  }

  /** The default answer to the dialog: a day that already has shifts is not touched. */
  @Test
  void skipLeavesDaysThatAlreadyHaveShiftsAlone() {
    withPatrolData((log, patrolData) -> {
      givenSaturdayTheEighthIsAlreadyScheduled(patrolData, log);
      givenTemplateRowZeroHolds(patrolData, log, MEMBER_ID);

      ShiftTemplateApplyService.Preview preview = preview(patrolData, SKIP, log);

      assertEquals(List.of(LocalDate.of(2025, 3, 8)), preview.daysInConflict(),
                   "the 8th should be named as the day already scheduled");
      assertFalse(preview.isOverwriting(), "skip must not be reported as overwriting");
      assertFalse(preview.daysToWrite().contains(LocalDate.of(2025, 3, 8)),
                  "and must not be in the write list");

      apply(patrolData, SKIP, log);

      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH)) {
        if (assignment.getDay() == 8) {
          assertEquals("do not touch", assignment.getEventName(),
                       "the existing day must survive untouched");
          assertEquals("0", assignment.getPosID(0),
                       "and must not have gained the template's patroller");
        }
      }
    });
  }

  /** The other answer: the day is cleared out and rebuilt from the template. */
  @Test
  void overwriteReplacesTheDaysThatAlreadyHaveShifts() {
    withPatrolData((log, patrolData) -> {
      givenSaturdayTheEighthIsAlreadyScheduled(patrolData, log);
      givenTemplateRowZeroHolds(patrolData, log, MEMBER_ID);

      ShiftTemplateApplyService.Preview preview = preview(patrolData, OVERWRITE, log);

      assertTrue(preview.isCanOverwrite(), "a template with a patroller on it can overwrite");
      assertTrue(preview.isOverwriting(), "overwrite mode with a conflict is destructive");
      assertEquals(5, preview.getDaysAffectedCount(),
                   "all five Saturdays are written when the conflict is overwritten");

      assertEquals(5, apply(patrolData, OVERWRITE, log), "every Saturday should be written");

      List<Integer> daysWithMember = new ArrayList<>();
      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH)) {
        assertFalse("do not touch".equals(assignment.getEventName()),
                    "the overwritten day's old rows should be gone");
        if (MEMBER_ID.equals(assignment.getPosID(0))) {
          daysWithMember.add(assignment.getDay());
        }
      }
      assertEquals(List.of(1, 8, 15, 22, 29), daysWithMember,
                   "the 8th should have been rebuilt from the template like every other Saturday");
    });
  }

  /**
   * An empty template has nothing to push out, so overwriting could only lose work. The page does
   * not offer the choice, and asking for it anyway must still not delete anything.
   */
  @Test
  void aTemplateWithNoPatrollersNeverOverwrites() {
    withPatrolData((log, patrolData) -> {
      givenSaturdayTheEighthIsAlreadyScheduled(patrolData, log);

      ShiftTemplateApplyService.Preview preview = preview(patrolData, OVERWRITE, log);

      assertFalse(preview.isCanOverwrite(), "an empty template must not offer overwriting");
      assertFalse(preview.isOverwriting(), "and must not be treated as overwriting");

      apply(patrolData, OVERWRITE, log);

      boolean survived = false;
      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH)) {
        survived |= assignment.getDay() == 8 && "do not touch".equals(assignment.getEventName());
      }
      assertTrue(survived, "the existing day must survive an overwrite it never offered");
    });
  }

  /** Only Sunday..Saturday templates fill days in; a named template has no weekday to match. */
  @Test
  void aTemplateThatIsNotADayOfWeekCannotBeApplied() {
    withPatrolData((log, patrolData) -> {
      ShiftTemplateApplyService.Preview preview =
        service.preview("Sample", patrolData, "Midnight", FROM, TO, SKIP, log);

      assertFalse(preview.isApplicable(), "Midnight is not a day-of-week template");
      assertEquals(0, service.apply("Sample", patrolData, "Midnight", FROM, TO, SKIP, log),
                   "and applying it should write nothing");
    });
  }

  @Test
  void aBackwardsOrMissingRangeIsRejected() {
    withPatrolData((log, patrolData) -> {
      assertFalse(service.preview("Sample", patrolData, TEMPLATE, TO, FROM, SKIP, log).isApplicable(),
                  "a backwards range should not be applicable");
      assertFalse(service.preview("Sample", patrolData, TEMPLATE, null, TO, SKIP, log).isApplicable(),
                  "a missing from date should not be applicable");
    });
  }

  /** An off-roster id must not reach the calendar, where it would render as "Err id (...)". */
  @Test
  void patrollersNoLongerOnTheRosterAreNotWritten() {
    withPatrolData((log, patrolData) -> {
      givenTemplateRowZeroHolds(patrolData, log, "999999999");

      ShiftTemplateApplyService.Preview preview = preview(patrolData, SKIP, log);
      assertEquals(0, preview.patrollersPerDay(),
                   "an unknown id should not be counted as a patroller");

      apply(patrolData, SKIP, log);
      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH)) {
        assertEquals("0", assignment.getPosID(0),
                     "no slot should hold the unknown id: " + assignment.getDate());
      }
    });
  }

  private ShiftTemplateApplyService.Preview preview(PatrolData patrolData,
                                                    ShiftTemplateApplyService.ConflictMode mode,
                                                    Logger log) {
    return service.preview("Sample", patrolData, TEMPLATE, FROM, TO, mode, log);
  }

  private int apply(PatrolData patrolData, ShiftTemplateApplyService.ConflictMode mode, Logger log) {
    return service.apply("Sample", patrolData, TEMPLATE, FROM, TO, mode, log);
  }

  /** Something of the director's own on Saturday the 8th, which the range covers. */
  private void givenSaturdayTheEighthIsAlreadyScheduled(PatrolData patrolData, Logger log) {
    Assignments existing = new Assignments(
      Assignments.createAssignmentName("2025-03-08", 1), templateRowZero(patrolData), log);
    existing.setEventName("do not touch");
    patrolData.writeAssignment(existing);
  }

  private void givenTemplateRowZeroHolds(PatrolData patrolData, Logger log, String patrollerId) {
    ShiftTemplatePatrollers.write(log, patrolData.getConnection(),
                                  templateRowZero(patrolData).getEventName(), List.of(patrollerId));
  }

  private ShiftDefinitions templateRowZero(PatrolData patrolData) {
    for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
      if (shift.parsedEventName().equals(TEMPLATE) && shift.getEventIndex() == 0) {
        return shift;
      }
    }
    throw new AssertionError("the " + TEMPLATE + " template should have a row 0");
  }

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", "123456");
    session.setAttribute("loggedInResort", "Sample");
    request.setSession(session);

    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(ShiftTemplateApplyServiceTest.class, javaxRequest, "GET", "Sample",
                            Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
    }
    finally {
      patrolData.close();
    }
  }
}
