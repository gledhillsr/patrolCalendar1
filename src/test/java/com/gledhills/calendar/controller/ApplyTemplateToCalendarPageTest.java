package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.store.ShiftTemplatePatrollers;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "Apply to calendar..." panel as the director sees it: the button is only live for a
 * day-of-week template, and a day in the range that is already scheduled is named and asked about
 * rather than silently skipped or wiped.
 *
 * Works in March 2025 - a month with nothing saved, outside every month the calendar goldens are
 * recorded for - and clears the month plus the template's patrollers afterwards.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ApplyTemplateToCalendarPageTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String MEMBER_ID = "200401";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  private static final String TEMPLATE = "Saturday";
  /** A real template that is not a day of the week, so it can never be applied to a range. */
  private static final String NAMED_TEMPLATE = "Midnight";

  private static final int YEAR = 2025;
  private static final int MONTH = 3; //March, 1 based
  private static final String FROM = "2025-03-01";
  private static final String TO = "2025-03-31";
  /** The Saturday this test schedules first, so it is the one in conflict. */
  private static final String CONFLICT_DATE = "2025-03-08";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

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

  @Test
  void theButtonIsLiveForADayOfWeekTemplate() throws Exception {
    String button = applyButton(templatesPage(TEMPLATE));

    assertFalse(button.contains("disabled"), "Saturday should be applicable: " + button);
    assertTrue(button.contains("every Saturday"), "and say what it does: " + button);
  }

  @Test
  void theButtonIsDeadForATemplateThatIsNotADayOfWeek() throws Exception {
    String button = applyButton(templatesPage(NAMED_TEMPLATE));

    assertTrue(button.contains("disabled"),
               NAMED_TEMPLATE + " has no weekday to match, so the button must be dead: " + button);
    assertTrue(button.contains("Sunday to Saturday"), "and say why: " + button);
  }

  /** The whole point of the panel: the heading and the text say what it is about to do. */
  @Test
  void thePanelNamesTheTemplateAndTheWeekday() throws Exception {
    String page = openApplyPanel();

    assertTrue(page.contains("Apply the &#39;Saturday&#39; Template including the assigned patrollers")
                 || page.contains("Apply the 'Saturday' Template including the assigned patrollers"),
               "the heading should name the template and mention the patrollers");
    assertTrue(page.contains("every <span>Saturday</span>"),
               "and the panel should say it lands on every Saturday");
  }

  /** Apply is the only button that writes; the old preview step is gone. */
  @Test
  void thePanelOffersApplyAndCancelOnly() throws Exception {
    String page = openApplyPanel();

    assertFalse(page.contains("Check what this will do"), "the preview button should be gone");
    assertTrue(Pattern.compile("name=\"confirmApply\"\\s+value=\"Apply\"").matcher(page).find(),
               "Apply should be there: " + page);
    assertTrue(page.contains("value=\"Cancel\""), "and Cancel beside it");
  }

  @Test
  void aRangeWithNothingScheduledIsWrittenStraightAway() throws Exception {
    givenTemplateRowZeroHolds(MEMBER_ID);

    String page = apply();

    assertFalse(page.contains("Patroller shift assignments already exist"),
                "nothing is scheduled in March, so there is nothing to ask about");
    assertTrue(page.contains("Put Saturday shifts on 5 day(s)"),
               "and Apply should have written the five Saturdays: " + page);
    assertEquals(List.of(1, 8, 15, 22, 29), saturdaysHolding(MEMBER_ID),
                 "the template patroller should be on every Saturday");
  }

  @Test
  void anAlreadyScheduledDayStopsApplyAndIsAskedAbout() throws Exception {
    givenTheEighthIsAlreadyScheduled();
    givenTemplateRowZeroHolds(MEMBER_ID);

    String page = apply();

    assertTrue(page.contains("Patroller shift assignments already exist for:"),
               "the dialog should say what the problem is");
    assertTrue(page.contains(CONFLICT_DATE), "and name the date: " + CONFLICT_DATE);
    assertTrue(page.contains("type=\"radio\" name=\"conflictMode\" value=\"skip\" checked"),
               "skip should be a radio and the default: " + page);
    assertTrue(page.contains("type=\"radio\" name=\"conflictMode\" value=\"overwrite\""),
               "overwrite should be the other radio");
    assertTrue(page.contains("name=\"conflictAnswered\""), "and the answer marked for the next Apply");
    assertTrue(page.contains("cannot be undone"), "and say overwriting is permanent");

    assertTrue(saturdaysHolding(MEMBER_ID).isEmpty(),
               "asking must not have written anything yet");
  }

  /** An empty template has nothing to push out, so there is no question to put. */
  @Test
  void noQuestionIsAskedWhenTheTemplateHasNoPatrollers() throws Exception {
    givenTheEighthIsAlreadyScheduled();

    String page = apply();

    assertFalse(page.contains("Patroller shift assignments already exist"),
                "there is no choice to offer for an empty template");
    assertTrue(page.contains("were left alone"), "the result should say the day was kept: " + page);
    assertEquals(List.of("do not touch"), eventNamesOn(8),
                 "and it must be exactly as it was");
  }

  @Test
  void skipWritesTheOtherSaturdaysAndLeavesTheConflictAlone() throws Exception {
    givenTheEighthIsAlreadyScheduled();
    givenTemplateRowZeroHolds(MEMBER_ID);

    String page = answerAndApply("skip");

    assertTrue(page.contains("were left alone"), "the result should say the day was kept: " + page);
    assertEquals(List.of("do not touch"), eventNamesOn(8),
                 "the conflicting day must be exactly as it was");
    assertTrue(saturdaysHolding(MEMBER_ID).contains(1),
               "the empty Saturdays should still have been filled in");
    assertFalse(saturdaysHolding(MEMBER_ID).contains(8),
                "and the skipped one should not have the template patroller");
  }

  @Test
  void overwriteRebuildsTheConflictingDayFromTheTemplate() throws Exception {
    givenTheEighthIsAlreadyScheduled();
    givenTemplateRowZeroHolds(MEMBER_ID);

    String page = answerAndApply("overwrite");

    assertTrue(page.contains("were overwritten"), "the result should say so: " + page);
    assertFalse(eventNamesOn(8).contains("do not touch"),
                "the old row should be gone: " + eventNamesOn(8));
    assertEquals(List.of(1, 8, 15, 22, 29), saturdaysHolding(MEMBER_ID),
                 "every Saturday, the overwritten one included, should carry the template patroller");
  }

  /** An answered question that carries no answer must be the harmless one. */
  @Test
  void anAnswerThatIsNotOverwriteSkips() throws Exception {
    givenTheEighthIsAlreadyScheduled();
    givenTemplateRowZeroHolds(MEMBER_ID);

    answerAndApply(null);

    assertEquals(List.of("do not touch"), eventNamesOn(8),
                 "anything but a literal 'overwrite' must leave the day alone");
  }

  /** A range with no Saturday in it should say so rather than report a hollow success. */
  @Test
  void aRangeWithNoMatchingWeekdaySaysSo() throws Exception {
    String page = mockMvc.perform(applyRequest().param("applyFrom", "2025-03-10")
                                                .param("applyTo", "2025-03-14"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertTrue(page.contains("There are no Saturdays between 2025-03-10 and 2025-03-14"),
               "the panel should say the range holds no Saturday: " + page);
  }

  private String templatesPage(String template) throws Exception {
    return mockMvc.perform(get("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("selectedShift", template)
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }

  /** The panel as it opens from the template editor: dates empty, nothing worked out yet. */
  private String openApplyPanel() throws Exception {
    return perform(post("/EditShiftTemplates")
                     .param("resort", "Sample")
                     .param("ID", DIRECTOR_ID)
                     .param("selectedShift", TEMPLATE)
                     .param("eventName", TEMPLATE)
                     .param("applyToCalendar", "Apply to calendar...")
                     .header("User-Agent", BROWSER)
                     .session(directorSession()));
  }

  /** Press Apply. Writes unless the range holds a day that is already scheduled. */
  private String apply() throws Exception {
    return perform(applyRequest().param("applyFrom", FROM).param("applyTo", TO));
  }

  /** Press Apply again with the conflict question answered, which is what commits. */
  private String answerAndApply(String conflictMode) throws Exception {
    MockHttpServletRequestBuilder request = applyRequest()
      .param("applyFrom", FROM)
      .param("applyTo", TO)
      .param("conflictAnswered", "yes");

    if (conflictMode != null) {
      request = request.param("conflictMode", conflictMode);
    }
    return perform(request);
  }

  private MockHttpServletRequestBuilder applyRequest() {
    return post("/EditShiftTemplates")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("selectedShift", TEMPLATE)
      .param("eventName", TEMPLATE)
      .param("confirmApply", "Apply")
      .header("User-Agent", BROWSER)
      .session(directorSession());
  }

  private String perform(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }

  /** The "Apply to calendar..." input tag, whichever attributes Thymeleaf left on it. */
  private String applyButton(String page) {
    Matcher matcher = Pattern.compile("<input[^>]*name=\"applyToCalendar\"[^>]*>").matcher(page);
    return matcher.find() ? matcher.group() : "(no applyToCalendar button on the page)";
  }

  private void givenTheEighthIsAlreadyScheduled() {
    withPatrolData((log, patrolData) -> {
      Assignments existing = new Assignments(
        Assignments.createAssignmentName(CONFLICT_DATE, 1), templateRowZero(patrolData), log);
      existing.setEventName("do not touch");
      patrolData.writeAssignment(existing);
    });
  }

  private void givenTemplateRowZeroHolds(String patrollerId) {
    withPatrolData((log, patrolData) -> ShiftTemplatePatrollers
      .write(log, patrolData.getConnection(), templateRowZero(patrolData).getEventName(),
             List.of(patrollerId)));
  }

  private ShiftDefinitions templateRowZero(PatrolData patrolData) {
    for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
      if (shift.parsedEventName().equals(TEMPLATE) && shift.getEventIndex() == 0) {
        return shift;
      }
    }
    throw new AssertionError("the " + TEMPLATE + " template should have a row 0");
  }

  private List<String> eventNamesOn(int day) {
    List<String> names = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH)) {
        if (assignment.getDay() == day) {
          names.add(assignment.getEventName().trim());
        }
      }
    });
    names.removeIf(String::isEmpty);
    return names;
  }

  /** Which days of March hold the given patroller in slot 0. */
  private List<Integer> saturdaysHolding(String patrollerId) {
    List<Integer> days = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH)) {
        if (patrollerId.equals(assignment.getPosID(0))) {
          days.add(assignment.getDay());
        }
      }
    });
    return days;
  }

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(ApplyTemplateToCalendarPageTest.class, javaxRequest, "GET", "Sample",
                            Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
    }
    finally {
      patrolData.close();
    }
  }

  private MockHttpSession directorSession() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    return session;
  }
}
