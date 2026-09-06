package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.ShiftTime;

/**
 * What a shift row's End Time becomes when the form comes back.
 *
 * <p>End Time is not like Start Time, because it was never migrated. Brighton alone has 373
 * assignment rows whose End is not a time at all - {@code end time}, {@code 5:30-9}, the literal
 * {@code empty} - and there is no End label to move them into, so emptying or rejecting them would
 * be data loss for nothing. They stay exactly as they are.
 *
 * <p>The rule is therefore about <b>editing</b> rather than about storing: a value the director did
 * not touch is kept whatever it says, and a value they did touch has to be a time, stored
 * canonically like a Start Time. A resort's history stays readable while everything typed from now
 * on is machine-readable, which is what the personal-calendar sync needs.
 *
 * <p>"Did not touch" is decided by comparing with what was on the row, not by trusting a hidden
 * field, so nothing posted can talk the server into keeping a new non-time value.
 */
record EndTimeEdit(String value, boolean rejected) {

  /**
   * @param posted what came back on the form
   * @param stored what is on the row now - the historical value that may not be a time
   */
  static EndTimeEdit of(String posted, String stored) {
    String typed = posted == null ? "" : posted.trim();
    String was = stored == null ? "" : stored.trim();

    if (typed.equals(was)) {
      return new EndTimeEdit(was, false);          //untouched: history is kept as it is
    }
    if (typed.isEmpty()) {
      return new EndTimeEdit("", false);           //clearing it is allowed; End is not required
    }

    String canonical = ShiftTime.normalize(typed);
    if (canonical == null) {
      //changed to something that is not a time: keep the old value and let the caller say so
      return new EndTimeEdit(was, true);
    }
    return new EndTimeEdit(canonical, false);
  }

  /** What both editors tell a director who typed one, worded once. */
  static String rowNeedsATimeMessage(int rowNumber, String typed) {
    return "\"" + typed + "\" is not a time, so shift " + rowNumber + "'s End Time was not changed."
           + " End Time takes a time like 8:00, 8am, 16 or 16:30. Nothing was saved.";
  }
}
