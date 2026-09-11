package com.gledhills.calendar.service;

import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.utils.Logger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which template rows the audit puts in front of a director.
 *
 * <p>The value of this list is entirely in what it leaves out. {@code 1600} and {@code 4:30pm} are
 * readable and the cleanup rewrites them, so a row holding one is <b>not</b> a problem and must not
 * appear here - listing it would bury the handful that genuinely need somebody's judgement among
 * hundreds that do not, which is the failure this whole panel exists to avoid.
 */
class ShiftTimeAuditTest {

  /** A readable time in any of its written forms is nobody's problem. */
  @Test
  void aRowThatCanBeReadIsNotListed() {
    assertEquals(List.of(), problems(row("Friday_0", "", "8:00", "16:30")));
    assertEquals(List.of(), problems(row("Friday_0", "", "0800", "1600")),
                 "untidy but readable - the cleanup handles these, so they are not a problem");
    assertEquals(List.of(), problems(row("Friday_0", "dir", "8:00", "4:30P")),
                 "a label beside a real pair of times is fine");
  }

  /** An end nobody can parse is named, and quoted back so the director can see what it says. */
  @Test
  void anUnreadableEndIsListedWithItsValue() {
    List<ShiftTimeAudit.Problem> found = problems(row("Friday_0", "", "8:00", "Close"));

    assertEquals(1, found.size());
    assertEquals("Friday", found.get(0).templateName());
    assertEquals(1, found.get(0).position(), "positions are 1-based, as the editor numbers them");
    assertTrue(found.get(0).problem().contains("Close"),
               "it should quote the offending value: " + found.get(0).problem());
  }

  /** A missing end is a different message from an unreadable one - they need different fixes. */
  @Test
  void aBlankEndSaysSoPlainly() {
    List<ShiftTimeAudit.Problem> found = problems(row("Friday_0", "", "8:00", ""));

    assertEquals(1, found.size());
    assertEquals("no end time", found.get(0).problem());
  }

  /**
   * A label-only shift is listed, and described as what it is.
   *
   * <p>It may well be correct - a resort that has always run an untimed slot should leave it - so
   * the wording says it cannot sync rather than that it is wrong.
   */
  @Test
  void aLabelOnlyShiftIsListedAsUnsyncable() {
    List<ShiftTimeAudit.Problem> found = problems(row("Friday_0", "mentee", "", ""));

    assertEquals(1, found.size());
    assertTrue(found.get(0).problem().contains("label only"), found.get(0).problem());
  }

  /** A row with nothing at all is the worst case and is not confused with a label-only one. */
  @Test
  void aRowWithNeitherIsCalledOutOnce() {
    List<ShiftTimeAudit.Problem> found = problems(row("Friday_0", "", "", ""));

    assertEquals(1, found.size(), "one row is one entry, however many things are wrong with it");
    assertTrue(found.get(0).problem().contains("no label"), found.get(0).problem());
  }

  /** Listed by template then row, so a director works down it in the order they would fix them. */
  @Test
  void theListIsOrderedTheWayADirectorWouldWorkIt() {
    List<ShiftTimeAudit.Problem> found = problems(
      row("Sunday_1", "", "8:00", "Close"),
      row("Friday_2", "", "8:00", "Close"),
      row("Friday_0", "", "8:00", "Close"));

    assertEquals(List.of("Friday", "Friday", "Sunday"),
                 found.stream().map(ShiftTimeAudit.Problem::templateName).toList());
    assertEquals(List.of(1, 3, 2),
                 found.stream().map(ShiftTimeAudit.Problem::position).toList());
  }

  private List<ShiftTimeAudit.Problem> problems(ShiftDefinitions... rows) {
    return ShiftTimeAudit.problems(List.of(rows));
  }

  private ShiftDefinitions row(String eventName, String label, String start, String end) {
    Logger log = new Logger(ShiftTimeAuditTest.class, null, "test", "test", Logger.INFO);
    return new ShiftDefinitions(eventName, start, end, 2, 0, label, log);
  }
}
