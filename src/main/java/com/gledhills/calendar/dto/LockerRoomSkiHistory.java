package com.gledhills.calendar.dto;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /api/locker/skihistory}: the locker room's whole skihistory table, each row
 * a column-name to value map keyed the same way the table is.
 *
 * @param rows every local skihistory row; {@code history_id} is required on each
 */
public record LockerRoomSkiHistory(List<Map<String, String>> rows) {

  /**
   * What the reconcile did, so the caller can print it and so an unexpected shape is visible
   * rather than silent.
   *
   * @param inserted rows added to the web site
   * @param updated  rows that existed but differed
   * @param deleted  rows removed because the locker room no longer has them
   * @param unchanged rows already identical
   * @param refused  set when nothing was applied, explaining why
   */
  public record Result(int inserted, int updated, int deleted, int unchanged, String refused) {
  }
}
