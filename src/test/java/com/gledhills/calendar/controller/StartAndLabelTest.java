package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * What the server does with the Label and Start Time a shift editor posts back.
 *
 * <p>Both editors share this, so it is tested once. The browser checks the same rule first and
 * says so in words; this is what happens when the form is posted around that check.
 */
class StartAndLabelTest {

  private final Logger log = mock(Logger.class);

  @Test
  void aTimeIsStoredCanonically() {
    assertEquals("8:00", StartAndLabel.of("0800", "", log).start());
    assertEquals("8:00", StartAndLabel.of("8am", "", log).start());
    assertEquals("16:30", StartAndLabel.of("16:30", "", log).start());
    assertEquals("", StartAndLabel.of("8:00", "", log).label());
  }

  @Test
  void aLabelIsKeptAlongsideItsTime() {
    StartAndLabel row = StartAndLabel.of("8am", "TL", log);
    assertEquals("8:00", row.start());
    assertEquals("TL", row.label());
  }

  /**
   * The case the whole split exists for: twenty years of directors typed the label into the Start
   * box, and someone will keep doing it. It moves rather than being rejected or lost.
   */
  @Test
  void aLabelTypedIntoTheStartBoxMovesToTheLabel() {
    StartAndLabel row = StartAndLabel.of("mentee", "", log);
    assertEquals("", row.start(), "the Start column holds only real times now");
    assertEquals("mentee", row.label());
  }

  @Test
  void blankStaysBlank() {
    StartAndLabel row = StartAndLabel.of("", "", log);
    assertEquals("", row.start());
    assertEquals("", row.label());

    assertEquals("", StartAndLabel.of(null, null, log).start());
    assertEquals("", StartAndLabel.of(null, null, log).label());
  }

  /** Nothing to move it to, so it goes - the browser is what stops this happening. */
  @Test
  void anUnparsableStartIsDroppedWhenTheLabelIsAlreadyTaken() {
    StartAndLabel row = StartAndLabel.of("nonsense", "TL", log);
    assertEquals("", row.start());
    assertEquals("TL", row.label());
  }

  @Test
  void surroundingSpaceIsNotPartOfEither() {
    StartAndLabel row = StartAndLabel.of("  8:00  ", "  dir  ", log);
    assertEquals("8:00", row.start());
    assertEquals("dir", row.label());
  }
}
