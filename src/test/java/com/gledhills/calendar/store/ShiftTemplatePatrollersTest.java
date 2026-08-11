package com.gledhills.calendar.store;

import com.gledhills.calendar.controller.ServletAdapter;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for template patroller storage, against the live Sample database.
 *
 * These use shift names that no real template owns ("ZZTest_0"/"ZZTest_1") and delete them
 * afterwards, so no template a director can see is touched.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ShiftTemplatePatrollersTest {

  private static final String SHIFT_A = "ZZTest_0";
  private static final String SHIFT_B = "ZZTest_1";

  @AfterEach
  void removeTestRows() {
    withConnection((log, connection) -> {
      ShiftTemplatePatrollers.deleteShift(log, connection, SHIFT_A);
      ShiftTemplatePatrollers.deleteShift(log, connection, SHIFT_B);
    });
  }

  /** The table self-creates, so a resort that has never used the feature just works. */
  @Test
  void readingWorksBeforeAnythingHasBeenWritten() {
    withConnection((log, connection) -> {
      List<String> slots = ShiftTemplatePatrollers.read(log, connection, SHIFT_A, 3);

      assertEquals(List.of("0", "0", "0"), slots,
                   "an untouched template should read back as empty slots");
    });
  }

  @Test
  void writesAndReadsBackByPosition() {
    withConnection((log, connection) -> {
      ShiftTemplatePatrollers.write(log, connection, SHIFT_A, List.of("200401", "0", "333333"));

      List<String> slots = ShiftTemplatePatrollers.read(log, connection, SHIFT_A, 3);
      assertEquals(List.of("200401", "0", "333333"), slots,
                   "slot positions must survive the round trip, gaps included");
    });
  }

  /** Writing replaces rather than accumulating, so removing somebody really removes them. */
  @Test
  void writingReplacesThePreviousPatrollers() {
    withConnection((log, connection) -> {
      ShiftTemplatePatrollers.write(log, connection, SHIFT_A, List.of("200401", "333333"));
      ShiftTemplatePatrollers.write(log, connection, SHIFT_A, List.of("0", "333333"));

      assertEquals(List.of("0", "333333"),
                   ShiftTemplatePatrollers.read(log, connection, SHIFT_A, 2),
                   "slot 0 should now be empty");
    });
  }

  @Test
  void emptySlotsAreNotStored() {
    withConnection((log, connection) -> {
      //Arrays.asList, not List.of: the point is that a null slot is tolerated
      ShiftTemplatePatrollers.write(log, connection, SHIFT_A, Arrays.asList("0", "", null));

      Map<String, List<String>> all = ShiftTemplatePatrollers.readAll(log, connection);
      assertFalse(all.containsKey(SHIFT_A),
                  "a template with nobody on it should leave no rows behind");
    });
  }

  @Test
  void readPadsToTheRequestedSlotCount() {
    withConnection((log, connection) -> {
      ShiftTemplatePatrollers.write(log, connection, SHIFT_A, List.of("200401"));

      assertEquals(List.of("200401", "0", "0", "0"),
                   ShiftTemplatePatrollers.read(log, connection, SHIFT_A, 4),
                   "a shift widened to 4 slots should read back padded");
    });
  }

  /** The reorder arrows swap two rows, and their patrollers have to travel with them. */
  @Test
  void swapExchangesTwoRowsPatrollers() {
    withConnection((log, connection) -> {
      ShiftTemplatePatrollers.write(log, connection, SHIFT_A, List.of("200401"));
      ShiftTemplatePatrollers.write(log, connection, SHIFT_B, List.of("333333", "200402"));

      ShiftTemplatePatrollers.swap(log, connection, SHIFT_A, SHIFT_B);

      assertEquals(List.of("333333", "200402"),
                   ShiftTemplatePatrollers.read(log, connection, SHIFT_A, 2));
      assertEquals(List.of("200401"),
                   ShiftTemplatePatrollers.read(log, connection, SHIFT_B, 1));
    });
  }

  /** Swapping against an empty row must clear the occupied one, not duplicate it. */
  @Test
  void swapWithAnEmptyRowMovesRatherThanCopies() {
    withConnection((log, connection) -> {
      ShiftTemplatePatrollers.write(log, connection, SHIFT_A, List.of("200401"));

      ShiftTemplatePatrollers.swap(log, connection, SHIFT_A, SHIFT_B);

      assertEquals(List.of("0"), ShiftTemplatePatrollers.read(log, connection, SHIFT_A, 1),
                   "the patroller should have moved out of the first row");
      assertEquals(List.of("200401"), ShiftTemplatePatrollers.read(log, connection, SHIFT_B, 1),
                   "and into the second");
    });
  }

  @Test
  void deleteShiftForgetsThePatrollers() {
    withConnection((log, connection) -> {
      ShiftTemplatePatrollers.write(log, connection, SHIFT_A, List.of("200401"));
      ShiftTemplatePatrollers.deleteShift(log, connection, SHIFT_A);

      assertFalse(ShiftTemplatePatrollers.readAll(log, connection).containsKey(SHIFT_A));
    });
  }

  @Test
  void recognisesEmptySlotValues() {
    assertTrue(ShiftTemplatePatrollers.isEmpty(null));
    assertTrue(ShiftTemplatePatrollers.isEmpty(""));
    assertTrue(ShiftTemplatePatrollers.isEmpty("  "));
    assertTrue(ShiftTemplatePatrollers.isEmpty("0"));
    assertFalse(ShiftTemplatePatrollers.isEmpty("200401"));
  }

  /** A quoted name must not be able to break the hand-built SQL. */
  @Test
  void quotesInAShiftNameDoNotBreakTheWrite() {
    String awkward = "ZZTest_0";
    withConnection((log, connection) -> {
      ShiftTemplatePatrollers.write(log, connection, awkward, List.of("O'Brien"));

      assertEquals(List.of("O'Brien"),
                   ShiftTemplatePatrollers.read(log, connection, awkward, 1),
                   "an apostrophe should round-trip rather than corrupt the statement");
    });
  }

  private void withConnection(BiConsumer<Logger, Connection> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", "123456");
    session.setAttribute("loggedInResort", "Sample");
    request.setSession(session);

    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(ShiftTemplatePatrollersTest.class, javaxRequest, "GET", "Sample",
                            Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData.getConnection());
    }
    finally {
      patrolData.close();
    }
  }
}
