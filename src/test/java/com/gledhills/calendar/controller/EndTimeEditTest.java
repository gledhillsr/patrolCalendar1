package com.gledhills.calendar.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End Time keeps its history, but anything typed into it from now on has to be a time.
 *
 * <p>The values here are real: Brighton's assignments hold {@code end time}, {@code 5:30-9},
 * {@code 24:00} and the literal {@code empty} in the End column, none of which is a time and none
 * of which has anywhere to go - there is no End label. They have to survive a save that did not
 * touch them, which is the difference between this and {@link StartAndLabel}.
 */
class EndTimeEditTest {

  /** The historical case: the form echoes back what was rendered, so nothing changed. */
  @Test
  void anUntouchedHistoricalValueSurvives() {
    for (String historical : new String[] {"end time", "5:30-9", "empty", "<empty>", "24:00"}) {
      EndTimeEdit edit = EndTimeEdit.of(historical, historical);
      assertFalse(edit.rejected(), historical + " was not edited, so it is not rejected");
      assertEquals(historical, edit.value(), historical + " should survive untouched");
    }
  }

  /** Whitespace round-trips through a form; it must not read as an edit. */
  @Test
  void whitespaceIsNotAnEdit() {
    EndTimeEdit edit = EndTimeEdit.of("  end time  ", "end time");
    assertFalse(edit.rejected());
    assertEquals("end time", edit.value());
  }

  @Test
  void anEditedValueIsStoredCanonically() {
    assertEquals("16:30", EndTimeEdit.of("4:30pm", "end time").value());
    assertEquals("16:00", EndTimeEdit.of("1600", "16:30").value());
    assertEquals("8:00", EndTimeEdit.of("8am", "").value());
    assertFalse(EndTimeEdit.of("4:30pm", "end time").rejected());
  }

  /** The point of the rule: you may not replace one non-time with another. */
  @Test
  void editingItToAnotherNonTimeIsRejected() {
    EndTimeEdit edit = EndTimeEdit.of("whenever", "end time");
    assertTrue(edit.rejected());
    assertEquals("end time", edit.value(), "the old value is kept, not overwritten with rubbish");
  }

  @Test
  void editingATimeToANonTimeIsRejected() {
    EndTimeEdit edit = EndTimeEdit.of("teatime", "16:30");
    assertTrue(edit.rejected());
    assertEquals("16:30", edit.value());
  }

  /** Clearing it is allowed - End Time is not required, and many rows have never had one. */
  @Test
  void clearingItIsAllowed() {
    EndTimeEdit edit = EndTimeEdit.of("", "end time");
    assertFalse(edit.rejected());
    assertEquals("", edit.value());

    assertFalse(EndTimeEdit.of(null, "16:30").rejected());
    assertEquals("", EndTimeEdit.of(null, "16:30").value());
  }

  /** A new row has nothing stored, so whatever is typed counts as an edit. */
  @Test
  void aNewRowMustBeATime() {
    assertTrue(EndTimeEdit.of("teatime", "").rejected());
    assertEquals("16:30", EndTimeEdit.of("16:30", "").value());
  }

  /**
   * An already-canonical value that was not touched is not "rewritten" - it comes back identical,
   * so a save of an untouched form writes the same bytes it read.
   */
  @Test
  void anUntouchedCanonicalTimeIsUnchanged() {
    EndTimeEdit edit = EndTimeEdit.of("16:30", "16:30");
    assertFalse(edit.rejected());
    assertEquals("16:30", edit.value());
  }

  @Test
  void theMessageNamesTheRowAndWhatWasTyped() {
    String message = EndTimeEdit.rowNeedsATimeMessage(3, "teatime");
    assertTrue(message.contains("shift 3"), message);
    assertTrue(message.contains("teatime"), message);
    assertTrue(message.contains("Nothing was saved"), message);
  }
}
