package com.gledhills.calendar.dto;

import java.util.List;
import java.util.Map;

/**
 * JSON returned by {@code /api/locker/roster}, the replacement for the roster half of the
 * locker room's old direct-MySQL sync.
 *
 * <p>Each member is the roster row as a column-name to value map rather than a fixed record,
 * so a column added on the web site flows through without a change at either end - the PHP
 * side assigns the keys straight onto its Patroller object.
 *
 * <p><b>The password columns are deliberately absent.</b> The old sync copied {@code Password}
 * (plain text) and {@code newPassword} down to the locker-room machine, which nothing there
 * ever read. See {@code LockerRoomApiController.ROSTER_COLUMNS_TO_SKIP}.
 *
 * @param resort  the resort this was read for
 * @param count   number of members, so the caller can sanity check what it received
 * @param members every roster row, ordered by IDNumber
 */
public record LockerRoomRoster(String resort, int count, List<Map<String, String>> members) {
}
