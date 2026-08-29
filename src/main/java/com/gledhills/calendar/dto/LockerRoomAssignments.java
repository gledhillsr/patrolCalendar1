package com.gledhills.calendar.dto;

import java.util.List;

/**
 * JSON returned by {@code /api/locker/assignments} to the locker-room PHP board.
 *
 * <p>One {@link Slot} per assigned position, in the order the shifts are stored, so the caller can
 * render them top to bottom without sorting. A position nobody has signed up for is still returned,
 * with {@code patrollerId} "0" and a null {@code name} - the board draws those as "-- EMPTY", and
 * dropping them would hide how many openings are left.
 *
 * @param resort the resort this was read for
 * @param date   the day requested, ISO yyyy-MM-dd
 * @param slots  every position on that day, filled or not
 */
public record LockerRoomAssignments(String resort, String date, List<Slot> slots) {

  /**
   * @param startTime  shift start as shown on the web site, e.g. "8:00" or "swing"
   * @param endTime    shift end, may be blank
   * @param eventName  the shift's name, may be blank
   * @param shiftType  Assignments.DAY_TYPE .. HOLIDAY_TYPE
   * @param patrollerId the member id, or "0" when the position is unfilled
   * @param name       "First Last", or null when unfilled or the id is not in the roster
   */
  public record Slot(String startTime, String endTime, String eventName, int shiftType,
                     String patrollerId, String name) {
  }
}
