package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one answer to "is this a time?", which the migration, both controllers and the calendar all
 * rely on.
 *
 * <p>Every value here was taken from Brighton's or Sample's real StartTime column, because the
 * field has been free text for twenty years and no invented list would have guessed what is in
 * there.
 */
class ShiftTimeTest {

  @Test
  void theFormsPeopleActuallyType() {
    assertEquals("8:00", ShiftTime.normalize("8:00"));
    assertEquals("8:00", ShiftTime.normalize("08:00"));
    assertEquals("8:00", ShiftTime.normalize("0800"));
    assertEquals("8:00", ShiftTime.normalize("800"));
    assertEquals("8:00", ShiftTime.normalize("8"));
    assertEquals("8:00", ShiftTime.normalize("8am"));
    assertEquals("8:00", ShiftTime.normalize("8AM"));
    assertEquals("8:00", ShiftTime.normalize("8 am"));
    assertEquals("8:00", ShiftTime.normalize("8:00am"));
    assertEquals("6:30", ShiftTime.normalize("6:30"));
    assertEquals("6:00", ShiftTime.normalize("6"));
    assertEquals("19:00", ShiftTime.normalize("1900"));
    assertEquals("14:00", ShiftTime.normalize("1400"));
    assertEquals("13:00", ShiftTime.normalize("13:00"));
    assertEquals("16:00", ShiftTime.normalize("4:00 pm"));
    assertEquals("16:00", ShiftTime.normalize("4pm"));
    assertEquals("16:30", ShiftTime.normalize("16:30"));
    assertEquals("9:00", ShiftTime.normalize("09:00"));
  }

  /** A meridiem is a 12 hour clock, and midnight/noon are where that goes wrong. */
  @Test
  void middayAndMidnight() {
    assertEquals("0:00", ShiftTime.normalize("12am"));
    assertEquals("12:00", ShiftTime.normalize("12pm"));
    assertEquals("0:30", ShiftTime.normalize("12:30am"));
    assertEquals("12:30", ShiftTime.normalize("12:30pm"));
    assertEquals("0:00", ShiftTime.normalize("0"), "a bare 0 is 24 hour midnight");
    assertNull(ShiftTime.normalize("0am"), "there is no 0 on a 12 hour clock");
    assertNull(ShiftTime.normalize("13pm"));
  }

  /**
   * The labels sitting in Brighton's and Sample's StartTime column today. PMExtra is the one that
   * matters: anything looking for "pm" inside the string turns a label into noon.
   */
  @Test
  void realLabelsAreNotTimes() {
    for (String label : new String[] {"dir", "swing", "Helper", "Instructor", "mentee", "dispatch",
                                      "aid room", "PMExtra", "reg", "TL", "start time"}) {
      assertNull(ShiftTime.normalize(label), label + " is a label, not a time");
      assertFalse(ShiftTime.isTime(label), label + " is a label, not a time");
    }
  }

  @Test
  void nothingAtAllIsNotATime() {
    assertNull(ShiftTime.normalize(null));
    assertNull(ShiftTime.normalize(""));
    assertNull(ShiftTime.normalize("   "));
  }

  /** Out of range reads as "not a time", the same as a label, so the caller has one branch. */
  @Test
  void impossibleClockTimesAreRejected() {
    assertNull(ShiftTime.normalize("25:00"));
    assertNull(ShiftTime.normalize("24:00"));
    assertNull(ShiftTime.normalize("8:60"));
    assertNull(ShiftTime.normalize("8:75"));
    assertNull(ShiftTime.normalize("2400"));
    assertNull(ShiftTime.normalize("99"));
    assertNull(ShiftTime.normalize("-1"));
  }

  /**
   * Two rows of one shift typed differently must land on the same string, or shiftKey counts them
   * as two shifts and stops noticing that somebody is on both.
   */
  @Test
  void differentSpellingsOfOneTimeAgree() {
    String canonical = ShiftTime.normalize("8:00");
    for (String same : new String[] {"8", "08:00", "0800", "800", "8am", "8:00 AM", " 8:00 "}) {
      assertEquals(canonical, ShiftTime.normalize(same), same + " is the same shift as 8:00");
    }
    assertTrue(ShiftTime.isTime("8am"));
  }

  /**
   * The {@code m} of the meridiem is optional, because thousands of real rows leave it off.
   *
   * <p>{@code 4:30P} was one resort's house style for years. While the rule insisted on the
   * {@code m} those values were read as labels, which is how 17 shift templates - and 2,759
   * scheduled shifts - ended up with the start time sitting in the Label column and no start time
   * at all. The values here are taken from those columns.
   */
  @Test
  void aMeridiemWithoutItsM() {
    assertEquals("16:30", ShiftTime.normalize("4:30P"));
    assertEquals("16:00", ShiftTime.normalize("4:00P"));
    assertEquals("8:00", ShiftTime.normalize("8:00 a"));
    assertEquals("12:00", ShiftTime.normalize("12:00P"));
    assertEquals("9:00", ShiftTime.normalize("9:00A"));
    assertEquals("21:30", ShiftTime.normalize("9:30 p"));
    assertEquals("22:00", ShiftTime.normalize("10P"), "no minutes either");
    assertEquals("18:30", ShiftTime.normalize("6:30P"));

    //and it agrees with the spelled-out form, which is the point
    assertEquals(ShiftTime.normalize("4:30pm"), ShiftTime.normalize("4:30P"));
    assertEquals(ShiftTime.normalize("8:00am"), ShiftTime.normalize("8:00 a"));
  }

  /**
   * Widening the meridiem must not start reading labels as times.
   *
   * <p>A bare {@code P} only means anything after a number here. {@code PMExtra} is the standing
   * example - it is a real label in Brighton's data, and any rule that goes looking for a meridiem
   * inside the string destroys it.
   */
  @Test
  void aBareLetterDoesNotTurnLabelsIntoTimes() {
    for (String label : new String[] {"PMExtra", "PM", "P", "AM Aid Room", "a", "pm shift",
                                      "dir", "swing", "mentee", "Close", "end time", "5:30-9"}) {
      assertNull(ShiftTime.normalize(label), label + " is a label, not a time");
      assertFalse(ShiftTime.isTime(label), label + " is a label, not a time");
    }
  }

  /**
   * The browser's copy of these rules must accept exactly what the server's does.
   *
   * <p>{@code static/js/shiftTime.js} is a hand-written mirror of this class - there is no shared
   * source, because one runs in a JVM and the other in a browser - so the two can drift, and when
   * they do the symptom is maddening: the box refuses a value the server would have taken happily,
   * and the message blames the value rather than the mismatch.
   *
   * <p>This nearly shipped. Widening the meridiem here without widening it there would have left
   * the browser rejecting {@code 4:30P} on the very rows this change exists to rescue.
   *
   * <p>Textual rather than behavioural: running the JavaScript would need an engine in the build,
   * and the sub-pattern is the whole of what differs between the two files.
   */
  @Test
  void theBrowserCopyCarriesTheSameMeridiemRule() throws Exception {
    String server = java.nio.file.Files.readString(
      java.nio.file.Path.of("src/main/java/com/gledhills/calendar/utils/ShiftTime.java"));
    String browser = java.nio.file.Files.readString(
      java.nio.file.Path.of("src/main/resources/static/js/shiftTime.js"));

    //Compared with the backslashes taken out: one file escapes for a Java string literal and the
    //other does not, so the raw text differs even when the rule is identical.
    String serverRule = server.replace("\\", "");
    String browserRule = browser.replace("\\", "");

    assertTrue(serverRule.contains("([ap]).?m?.?"), "ShiftTime.java should allow a bare a/p");
    assertTrue(browserRule.contains("([ap]).?m?.?"), "shiftTime.js should allow a bare a/p too");

    assertFalse(serverRule.contains("([ap]).?m.?"),
                "ShiftTime.java still insists on the m somewhere");
    assertFalse(browserRule.contains("([ap]).?m.?"),
                "shiftTime.js still insists on the m - it will reject what the server accepts");
  }
}
