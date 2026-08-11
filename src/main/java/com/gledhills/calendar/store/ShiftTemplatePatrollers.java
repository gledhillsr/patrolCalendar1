package com.gledhills.calendar.store;

import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.SqlWriter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The patrollers a director has pencilled into a shift <em>template</em>.
 *
 * A template row in {@code shiftdefinitions} ("Saturday_0", "Midnight_1", ...) says when a shift
 * runs and how many slots it has. This adds who fills those slots, so a day built from the
 * template arrives with its patrollers already on it.
 *
 * These are a starting point, not a schedule: nothing here is a real assignment until it is
 * written into {@code assignments} for an actual date. Only then do the shift counts on
 * ListPatrollers and the DailyReminder emails see it. That distinction matters - it is what stops
 * a template edit from silently emailing people about shifts they never signed up for.
 *
 * The table self-creates on first use, the same way {@link PasswordResetToken} does, so no manual
 * migration is needed for any resort.
 *
 * @author Steve Gledhill
 */
public class ShiftTemplatePatrollers {

  private static final String TABLE = "shifttemplatepatrollers";

  /** "0" is the stored value for an empty slot, matching {@link Assignments}. */
  public static final String EMPTY_SLOT = "0";

  private ShiftTemplatePatrollers() {
  }

  /**
   * Every template row that has patrollers, keyed by its shift name ("Saturday_0").
   * The list is indexed by slot position and padded to the slot count with {@link #EMPTY_SLOT},
   * so callers can read position i without a bounds check.
   */
  public static Map<String, List<String>> readAll(Logger LOG, Connection connection) {
    Map<String, List<String>> byShiftName = new HashMap<>();
    if (!ensureTableExists(LOG, connection)) {
      return byShiftName;
    }

    String sql = "SELECT EventName, Position, PatrollerId FROM " + TABLE
      + " ORDER BY EventName ASC, Position ASC";
    try {
      LOG.logSqlStatement(sql);
      PreparedStatement statement = connection.prepareStatement(sql);
      ResultSet results = statement.executeQuery();

      while (results.next()) {
        String shiftName = results.getString("EventName");
        int position = results.getInt("Position");
        String patrollerId = results.getString("PatrollerId");

        List<String> slots = byShiftName.computeIfAbsent(shiftName, name -> new ArrayList<>());
        while (slots.size() <= position) {
          slots.add(EMPTY_SLOT);
        }
        slots.set(position, patrollerId == null ? EMPTY_SLOT : patrollerId);
      }
      results.close();
      statement.close();
    }
    catch (SQLException e) {
      LOG.logException("reading " + TABLE, e);
    }
    return byShiftName;
  }

  /** The patrollers for one template row, padded to {@code slotCount} with empty slots. */
  public static List<String> read(Logger LOG, Connection connection, String shiftName, int slotCount) {
    List<String> slots = readAll(LOG, connection).getOrDefault(shiftName, new ArrayList<>());
    List<String> padded = new ArrayList<>(slots);
    while (padded.size() < slotCount) {
      padded.add(EMPTY_SLOT);
    }
    return padded;
  }

  /**
   * Replace one template row's patrollers. Empty slots are not stored, so a template with nobody
   * on it leaves no rows behind.
   *
   * @param patrollerIds slot 0 first; "0", null or blank means nobody
   */
  public static void write(Logger LOG, Connection connection, String shiftName,
                           List<String> patrollerIds) {
    if (!ensureTableExists(LOG, connection)) {
      return;
    }
    deleteShift(LOG, connection, shiftName);

    for (int position = 0; position < patrollerIds.size(); position++) {
      String patrollerId = patrollerIds.get(position);
      if (isEmpty(patrollerId)) {
        continue;
      }
      String sql = "INSERT INTO " + TABLE + " (EventName, Position, PatrollerId) VALUES ('"
        + escape(shiftName) + "', " + position + ", '" + escape(patrollerId) + "')";
      try {
        SqlWriter.executeUpdate(LOG, connection, "writeShiftTemplatePatroller", sql);
      }
      catch (SQLException e) {
        //already logged by SqlWriter
      }
    }
  }

  /** Forget one template row's patrollers, e.g. when the row itself is deleted. */
  public static void deleteShift(Logger LOG, Connection connection, String shiftName) {
    if (!ensureTableExists(LOG, connection)) {
      return;
    }
    String sql = "DELETE FROM " + TABLE + " WHERE EventName = '" + escape(shiftName) + "'";
    try {
      SqlWriter.executeUpdate(LOG, connection, "deleteShiftTemplatePatrollers", sql);
    }
    catch (SQLException e) {
      //already logged by SqlWriter
    }
  }

  /**
   * Move one template row's patrollers to another name, for the reorder arrows. Both rows are
   * read before either is written, so a swap does not lose a side.
   */
  public static void swap(Logger LOG, Connection connection, String firstShiftName,
                          String secondShiftName) {
    if (!ensureTableExists(LOG, connection)) {
      return;
    }
    Map<String, List<String>> all = readAll(LOG, connection);
    List<String> first = all.getOrDefault(firstShiftName, new ArrayList<>());
    List<String> second = all.getOrDefault(secondShiftName, new ArrayList<>());

    write(LOG, connection, firstShiftName, second);
    write(LOG, connection, secondShiftName, first);
  }

  /**
   * How many patrollers each template has pencilled in, keyed by the template's parsed name
   * ("Saturday"). Counts only slots that would actually be applied: within the shift's slot count,
   * not empty, and still on the roster. Templates with nobody assigned are absent from the map.
   *
   * @param allShifts every template row, as read from shiftdefinitions
   * @param rosterIds ids currently on the roster; anybody else is skipped
   */
  public static Map<String, Integer> countByTemplateName(Logger LOG, Connection connection,
                                                        List<ShiftDefinitions> allShifts,
                                                        Set<String> rosterIds) {
    Map<String, List<String>> bySlotName = readAll(LOG, connection);
    Map<String, Integer> counts = new HashMap<>();

    for (ShiftDefinitions shift : allShifts) {
      List<String> slots = bySlotName.get(shift.getEventName());
      if (slots == null) {
        continue;
      }
      int assigned = 0;
      for (int slot = 0; slot < slots.size() && slot < shift.getCount(); slot++) {
        String patrollerId = slots.get(slot);
        if (!isEmpty(patrollerId) && rosterIds.contains(patrollerId)) {
          assigned++;
        }
      }
      if (assigned > 0) {
        counts.merge(shift.parsedEventName(), assigned, Integer::sum);
      }
    }
    return counts;
  }

  /** True when the slot holds nobody. */
  public static boolean isEmpty(String patrollerId) {
    return patrollerId == null || patrollerId.trim().isEmpty()
      || EMPTY_SLOT.equals(patrollerId.trim());
  }

  /**
   * Create the table if this resort has never used the feature. Returns false when the table is
   * unavailable, so callers degrade to "no template patrollers" rather than failing the page.
   */
  private static boolean ensureTableExists(Logger LOG, Connection connection) {
    String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
      + "EventName VARCHAR(64) NOT NULL, "
      + "Position INT NOT NULL, "
      + "PatrollerId VARCHAR(20) NOT NULL, "
      + "PRIMARY KEY (EventName, Position)"
      + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    try {
      SqlWriter.executeUpdate(LOG, connection, "ensureShiftTemplatePatrollersTable", sql);
      return true;
    }
    catch (SQLException e) {
      return false; //already logged by SqlWriter
    }
  }

  /** The rest of this codebase builds SQL by hand; at least keep quotes from breaking out. */
  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("'", "''");
  }
}
