package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.ShiftTime;

/**
 * What a shift row's Start and Label fields hold once the server has had its say.
 *
 * <p>Both shift editors post a Label and a Start Time per row, and both have to answer the same
 * question the same way, so the answer lives here rather than twice.
 *
 * <p>The rule:
 *
 * <ul>
 *   <li>a Start that is a time is stored <b>exactly as it was typed</b> - {@code 8am} stays
 *       {@code 8am} and {@code 4pm} stays {@code 4pm}. It is a director's own shorthand and the
 *       calendar shows it back to them; rewriting it to {@code 16:00} was tidiness at their
 *       expense. Two rows of a long shift typed {@code 8am} and {@code 08:00} are still one shift,
 *       because {@link com.gledhills.calendar.utils.ShiftTime#shiftKey} normalizes before it
 *       groups rather than relying on the stored text</li>
 *   <li>a Start that is <b>not</b> a time was a label typed into the wrong box. It moves to the
 *       Label when the Label is empty, exactly as the migration treated twenty years of them, and
 *       the Start is left blank so the column holds only real times</li>
 *   <li>if the Label is already filled, an unparsable Start is dropped and logged - there is
 *       nowhere left to put it, and keeping it would defeat the point of the split</li>
 * </ul>
 *
 * <p>The browser checks this first and says so in plain words; this is the backstop for a form
 * posted around that check, which is the same division of labour as the template name rule.
 */
record StartAndLabel(String start, String label) {

  /**
   * A row with neither a Label nor a Start Time, which is not something to save.
   *
   * <p>The calendar draws a slot's label or, failing that, its start time, so a row with neither
   * renders as a blank line that nobody can identify, on a shift people are expected to turn up
   * for. Both editors refuse to save one; the browser says so first.
   */
  boolean hasNeitherLabelNorStart() {
    return start.isEmpty() && label.isEmpty();
  }

  /** What both editors tell a director who tried to save one, worded once. */
  static String rowNeedsLabelOrStartMessage(int rowNumber) {
    return "Shift " + rowNumber + " has no Label and no Start Time. Every shift needs one or the"
           + " other - the calendar shows the Label if there is one, and the Start Time if there"
           + " is not. Nothing was saved.";
  }

  static StartAndLabel of(String rawStart, String rawLabel, Logger LOG) {
    String label = rawLabel == null ? "" : rawLabel.trim();
    String start = rawStart == null ? "" : rawStart.trim();

    if (start.isEmpty()) {
      return new StartAndLabel("", label);
    }

    if (ShiftTime.isTime(start)) {
      //kept as typed: normalize decides whether it IS a time, it does not decide how it is written
      return new StartAndLabel(start, label);
    }

    if (label.isEmpty()) {
      //a label typed into the Start box, which is how every one of these got there before the split
      return new StartAndLabel("", start);
    }

    LOG.warn("shift row posted a start time that is not a time and a label that is already set;"
             + " dropping the start value. label=[" + label + "]");
    return new StartAndLabel("", label);
  }
}
