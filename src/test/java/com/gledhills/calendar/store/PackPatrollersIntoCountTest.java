package com.gledhills.calendar.store;

import com.gledhills.calendar.utils.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Reducing a shift's patroller count must not quietly strand anybody.
 *
 * <p>Slots are a grid, not a queue: three patrollers can sit in slots 1, 3 and 5 of a five-slot
 * shift. Dropping that shift to three slots used to leave the one in slot 5 <b>still stored and no
 * longer drawn</b> - gone from the calendar and from every count, but still on the row, so nothing
 * said they had been dropped and nothing said they were still there. Packing moves everybody down
 * into the slots that remain, and reports a loss only when there genuinely is not room.
 */
class PackPatrollersIntoCountTest {

  private final Logger log = mock(Logger.class);

  private Assignments shiftOf(int count, String... slots) {
    ShiftDefinitions definition =
      new ShiftDefinitions("Saturday_0", "8:00", "16:00", count, Assignments.DAY_TYPE, "", log);
    Assignments shift = new Assignments("2026-01-10_a", definition, log);
    for (int slot = 0; slot < slots.length; slot++) {
      shift.insertAt("Sample", slot, slots[slot]);
    }
    return shift;
  }

  private String slots(Assignments shift) {
    StringBuilder text = new StringBuilder();
    for (int slot = 0; slot < Assignments.MAX_ASSIGNMENT_SIZE; slot++) {
      text.append(slot == 0 ? "" : ",").append(shift.getPosID(slot));
    }
    return text.toString();
  }

  /** The reported case: five slots down to three, with people in 1, 3 and 5. Nobody is lost. */
  @Test
  void patrollersBeyondTheNewCountMoveIntoTheEmptySlotsInFrontOfThem() {
    Assignments shift = shiftOf(3, "111", "0", "222", "0", "333");

    assertEquals(0, shift.packPatrollersIntoCount(), "all three still fit, so none are dropped");
    assertEquals("111,222,333,0,0,0,0,0,0,0", slots(shift));
  }

  /** The order they were in is the order they stay in; packing is not a reshuffle. */
  @Test
  void theirOrderIsKept() {
    Assignments shift = shiftOf(2, "0", "777", "0", "0", "888");

    assertEquals(0, shift.packPatrollersIntoCount());
    assertEquals("777,888,0,0,0,0,0,0,0,0", slots(shift));
  }

  /** Only now is anybody actually lost: four patrollers, two slots. */
  @Test
  void patrollersAreDroppedOnlyWhenThereIsGenuinelyNoRoom() {
    Assignments shift = shiftOf(2, "111", "222", "333", "444");

    assertEquals(2, shift.packPatrollersIntoCount(), "two of the four have nowhere to go");
    assertEquals("111,222,0,0,0,0,0,0,0,0", slots(shift), "the first two are the ones kept");
  }

  /** Everything after the new count is emptied, not merely left undrawn. */
  @Test
  void theSlotsPastTheCountAreZeroed() {
    Assignments shift = shiftOf(1, "111", "222", "333");

    assertEquals(2, shift.packPatrollersIntoCount());
    assertEquals("111,0,0,0,0,0,0,0,0,0", slots(shift));
  }

  /** A missed shift is a negative id - somebody, not an empty slot - so it travels with the rest. */
  @Test
  void aMissedShiftIsStillAPatroller() {
    Assignments shift = shiftOf(2, "0", "-555", "0", "666");

    assertEquals(0, shift.packPatrollersIntoCount());
    assertEquals("-555,666,0,0,0,0,0,0,0,0", slots(shift));
  }

  /** Dropping a shift to nobody empties it and says how many that cost. */
  @Test
  void aCountOfZeroEmptiesTheShift() {
    Assignments shift = shiftOf(0, "111", "222");

    assertEquals(2, shift.packPatrollersIntoCount());
    assertEquals("0,0,0,0,0,0,0,0,0,0", slots(shift));
  }

  /** Packing an already-packed shift changes nothing, so a plain save cannot disturb a day. */
  @Test
  void packingTwiceIsTheSameAsPackingOnce() {
    Assignments shift = shiftOf(3, "111", "0", "222", "0", "333");
    shift.packPatrollersIntoCount();
    String afterFirst = slots(shift);

    assertEquals(0, shift.packPatrollersIntoCount());
    assertEquals(afterFirst, slots(shift));
  }

  /**
   * What the browser warning counts, and why it looks at every slot: somebody stranded past the
   * count by an earlier reduction is still on the row and still has to be accounted for.
   */
  @Test
  void theFilledCountSeesPatrollersStrandedPastTheCount() {
    assertEquals(3, shiftOf(2, "111", "0", "222", "0", "333").getFilledSlotCount());
    assertEquals(0, shiftOf(4).getFilledSlotCount());
  }
}
