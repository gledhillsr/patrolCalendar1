package com.gledhills.calendar.store;

import com.gledhills.calendar.utils.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * How a shift reads inside a reminder email.
 *
 * <p>The sentence is "You are scheduled to Ski Patrol at Brighton, on Thursday, September 3, 2026"
 * followed by this and a full stop, so every case here has to end in something a full stop can
 * follow.
 *
 * <p>The reason it exists: a real reminder went out reading <i>"... 2026 from Helper to ."</i> -
 * the label had been pasted in where the start time goes and the end time was empty, and the three
 * parts were concatenated with no regard for which of them were actually there.
 */
class ShiftReminderWordingTest {

  private final Logger log = mock(Logger.class);

  private Assignments shift(String label, String start, String end) {
    ShiftDefinitions definition =
      new ShiftDefinitions("Thursday_0", start, end, 1, Assignments.DAY_TYPE, label, log);
    return new Assignments("2026-09-03_a", definition, log);
  }

  @Test
  void aLabelAndBothTimes() {
    assertEquals(" from (Helper) 8am to 4:30pm", shift("Helper", "8am", "4:30pm").describeShiftTimes());
  }

  /**
   * Why the brackets. The labels in real data are words like "can", "dir" and "PMExtra"; run
   * together with the times, "from can 1830 to 2130" reads as though "can" were part of the time.
   */
  @Test
  void theLabelIsBracketedSoItCannotBeReadAsPartOfTheTime() {
    assertEquals(" from (can) 1830 to 2130", shift("can", "1830", "2130").describeShiftTimes());
  }

  /** No label, so no empty brackets to explain away. */
  @Test
  void timesWithNoLabel() {
    assertEquals(" from 8:00 to 16:30", shift("", "8:00", "16:30").describeShiftTimes());
  }

  /** The reported bug: a label, no times. It must not read "from Helper to ". */
  @Test
  void aLabelAndNoTimesAtAll() {
    assertEquals(" from (Helper)", shift("Helper", "", "").describeShiftTimes());
  }

  @Test
  void aLabelAndAStartButNoEnd() {
    assertEquals(" from (Helper) 8am", shift("Helper", "8am", "").describeShiftTimes());
  }

  /** An end but nothing to start from - "from  to 16:30" would be worse than saying "until". */
  @Test
  void anEndWithNoStartOrLabel() {
    assertEquals(" until 16:30", shift("", "", "16:30").describeShiftTimes());
  }

  /** Nothing to say, so nothing is said - the sentence ends after the date. */
  @Test
  void nothingAtAll() {
    assertEquals("", shift("", "", "").describeShiftTimes());
  }

  /** Null times are what an unset column reads back as, and must not reach the email as "null". */
  @Test
  void nullsAreNotTheWordNull() {
    assertEquals(" from (Helper)", shift("Helper", null, null).describeShiftTimes());
    assertEquals("", shift("", null, null).describeShiftTimes());
  }

  @Test
  void surroundingSpaceDoesNotDoubleUp() {
    assertEquals(" from (Helper) 8am to 4:30pm",
                 shift("  Helper ", " 8am ", " 4:30pm ").describeShiftTimes());
  }
}
