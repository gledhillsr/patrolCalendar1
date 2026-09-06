package com.gledhills.calendar;

import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.ShiftTime;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Splits the old free-text Start field into a Label and a real time.
 *
 * <p>Run for every resort at once from <b>Directors &rarr; Site Wide Configuration</b>, which is
 * the normal way. For one resort from the command line, using the jar the app runs from:
 *
 * <pre>
 *   java -Dloader.main=com.gledhills.calendar.ShiftLabelMigration \
 *        -cp calendar-1.jar org.springframework.boot.loader.launch.PropertiesLauncher Brighton
 * </pre>
 *
 * <p>What it does, to {@code shiftdefinitions} and {@code assignments} alike:
 *
 * <ol>
 *   <li>adds the {@code Label} column if the table has not got one</li>
 *   <li>a StartTime that is <b>not</b> a readable time moves to Label, and StartTime is emptied -
 *       {@code dir}, {@code swing}, {@code aid room}, {@code PMExtra} are all real examples</li>
 *   <li>a StartTime that <b>is</b> a time stays put, rewritten in canonical form - {@code 0800},
 *       {@code 8am} and {@code 8:00} are one time and must end up as one string, or
 *       {@code shiftKey} reads them as different shifts</li>
 * </ol>
 *
 * <p><b>EndTime is deliberately left alone.</b> Brighton has 373 assignment rows whose EndTime is
 * not a time either ({@code end time}, {@code 5:30-9}, the literal {@code empty}), and there is no
 * End label to move them to. Emptying them would be data loss for no gain.
 *
 * <p>Idempotent: see {@link #migrateResort}. A second run writes nothing and says so.
 */
public final class ShiftLabelMigration {

  /** Both tables carry the same two columns, so the same pass does each of them. */
  private static final String[] TABLES = {"shiftdefinitions", "assignments"};

  /** Wide enough for the longest label in Brighton's data ("PM Aid Room") with room to spare. */
  private static final String LABEL_COLUMN = "`Label` varchar(32) NOT NULL DEFAULT ''";

  private ShiftLabelMigration() {
  }

  public static void main(String... args) {
    PrintWriter out = new PrintWriter(System.out, true);
    List<String> resorts = new ArrayList<>(List.of(args));

    if (resorts.isEmpty()) {
      out.println("usage: ShiftLabelMigration <resort> [<resort> ...]");
      out.println("   or use the button on the Directors page's Site Wide Configuration,");
      out.println("   which does the same thing to every resort in PatrolData.resortMap.");
      return;
    }

    Properties properties = System.getProperties();
    SessionData sessionData = new SessionData(properties, out);
    Logger LOG = new Logger(ShiftLabelMigration.class, null, "ShiftLabelMigration",
                            "ShiftLabelMigration", Logger.INFO);

    out.println("== migrating ==");
    for (String resort : resorts) {
      if (!PatrolData.isValidResort(resort)) {
        out.println(resort + ": not a resort in PatrolData.resortMap, skipped");
        continue;
      }
      PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
      try {
        ResortOutcome outcome = migrateResort(patrolData.getConnection());
        out.println(resort + ": " + (outcome.ok() ? outcome.detail() : "FAILED - " + outcome.detail()));
      }
      finally {
        patrolData.close();
      }
    }
    out.flush();
  }

  /**
   * What one resort's migration did, for a caller that wants to show it rather than print it.
   *
   * @param ok      false only when something failed; a resort with nothing to do is still ok
   * @param changed whether anything was actually written
   */
  public record ResortOutcome(boolean ok, boolean changed, String detail) {
  }

  /**
   * Migrate one resort, both tables.
   *
   * <p><b>Idempotent, and self-healing.</b> It does not decide by asking whether the column exists,
   * because the ALTER and the row moves cannot share a transaction - MySQL commits DDL on the spot -
   * so a run that failed between them would leave a resort with the column and its data still in the
   * old shape, and a column check would skip it forever. It looks at the data instead: a StartTime
   * that is already canonical or already blank needs nothing, so a second run writes nothing and
   * reports "nothing to do", while a half-finished first run gets finished.
   */
  public static ResortOutcome migrateResort(Connection connection) {
    try {
      int columnsAdded = 0;
      int moved = 0;
      int normalized = 0;

      for (String table : TABLES) {
        if (!hasLabelColumn(connection, table)) {
          try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE `" + table + "` ADD COLUMN " + LABEL_COLUMN);
          }
          columnsAdded++;
        }

        String keyColumn = table.equals("assignments") ? "Date" : "EventName";
        List<String[]> updates = new ArrayList<>();   //key, newStart, newLabel (null = time only)

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
               "SELECT `" + keyColumn + "`, `StartTime`, `Label` FROM `" + table + "`")) {
          while (rows.next()) {
            String key = rows.getString(1);
            String start = rows.getString(2);
            String label = rows.getString(3);

            if (start == null || start.trim().isEmpty()) {
              continue;                                   //blank: nothing to sort
            }
            String canonical = ShiftTime.normalize(start);
            if (canonical == null) {
              if (label != null && !label.trim().isEmpty()) {
                continue;                                 //already has a label; leave it alone
              }
              updates.add(new String[] {key, "", start.trim()});
            }
            else if (!canonical.equals(start)) {
              updates.add(new String[] {key, canonical, null});
            }
          }
        }

        String withLabel =
          "UPDATE `" + table + "` SET `StartTime` = ?, `Label` = ? WHERE `" + keyColumn + "` = ?";
        String timeOnly = "UPDATE `" + table + "` SET `StartTime` = ? WHERE `" + keyColumn + "` = ?";
        try (PreparedStatement moveStatement = connection.prepareStatement(withLabel);
             PreparedStatement timeStatement = connection.prepareStatement(timeOnly)) {
          for (String[] update : updates) {
            if (update[2] == null) {
              timeStatement.setString(1, update[1]);
              timeStatement.setString(2, update[0]);
              timeStatement.executeUpdate();
              normalized++;
            }
            else {
              moveStatement.setString(1, update[1]);
              moveStatement.setString(2, update[2]);
              moveStatement.setString(3, update[0]);
              moveStatement.executeUpdate();
              moved++;
            }
          }
        }
      }

      boolean changed = columnsAdded > 0 || moved > 0 || normalized > 0;
      if (!changed) {
        return new ResortOutcome(true, false, "nothing to do");
      }
      StringBuilder detail = new StringBuilder();
      if (columnsAdded > 0) {
        detail.append(columnsAdded == TABLES.length ? "Label column added to both tables"
                                                    : "Label column added to 1 table");
      }
      if (moved > 0) {
        detail.append(detail.length() == 0 ? "" : ", ").append(moved).append(" moved to Label");
      }
      if (normalized > 0) {
        detail.append(detail.length() == 0 ? "" : ", ").append(normalized)
              .append(" time(s) rewritten");
      }
      return new ResortOutcome(true, true, detail.toString());
    }
    catch (Exception e) {
      return new ResortOutcome(false, false, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static boolean hasLabelColumn(Connection connection, String table) throws SQLException {
    try (ResultSet columns =
           connection.getMetaData().getColumns(connection.getCatalog(), null, table, "Label")) {
      return columns.next();
    }
  }
}
