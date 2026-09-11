package com.gledhills.calendar;

import com.gledhills.calendar.ShiftLabelMigration.ResortOutcome;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.ShiftTime;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Finishes the job {@link ShiftLabelMigration} started, on the shift templates.
 *
 * <p>Three things were left behind, and all three are the computer's to fix rather than a
 * director's - the values are perfectly readable, they are just not in the shape the rest of the
 * app now expects:
 *
 * <ol>
 *   <li><b>A time sitting in the Label.</b> {@code ShiftTime} used to insist on the {@code m} of
 *       the meridiem, so {@code 4:00P} and {@code 8:00 a} were read as labels and moved out of
 *       StartTime by the original migration, which then blanked the start. The value is intact and
 *       the reversal is exact: on KellyCanyon's history all 1,794 such labels matched the
 *       pre-migration StartTime character for character. Those rows have <b>no start time at all</b>
 *       until this puts it back.</li>
 *   <li><b>A StartTime that is a time but not canonical.</b> The original pass did this, so there
 *       should be none left; it is here because a resort migrated before the meridiem was widened
 *       can still produce one, and because a pass that silently depends on another having run first
 *       is the kind that fails quietly.</li>
 *   <li><b>An EndTime that is a time but not canonical</b> - {@code 1600}, {@code 4:30pm},
 *       {@code 2100}. EndTime was never migrated, so this is most of the work: 214 rows site-wide.
 *       Canonical form is not tidiness, it is what makes {@code shiftKey} read two rows of one
 *       shift as one shift.</li>
 * </ol>
 *
 * <p><b>Templates only, and deliberately.</b> {@code assignments} carries twenty years of history
 * with the same faults - 2,759 mis-sorted labels and thousands of untidy end times - and rewriting
 * a shift somebody worked in 2019 changes a record for no one's benefit. Templates are what
 * generate the seasons ahead, so fixing them is what stops the problem recurring.
 *
 * <p><b>What it will not touch:</b> an EndTime that is not a time ({@code Close}, {@code end time},
 * {@code 5:30-9}) and an EndTime that is blank. There is no End label to move those into and no way
 * to guess the hour, so they are left exactly as they are and reported instead - see
 * {@link com.gledhills.calendar.service.ShiftTimeAudit}, which is what puts them in front of the
 * director who knows the answer.
 *
 * <p>Idempotent by looking at the data: a row already in canonical form needs no update, so a
 * second run writes nothing and says "nothing to do".
 */
public final class ShiftTimeCleanup {

  private static final String TABLE = "shiftdefinitions";

  private ShiftTimeCleanup() {
  }

  /**
   * One or more resorts from the command line, using the jar the app runs from:
   *
   * <pre>
   *   java -Dloader.main=com.gledhills.calendar.ShiftTimeCleanup \
   *        -cp calendar-1.jar org.springframework.boot.loader.launch.PropertiesLauncher Brighton
   * </pre>
   *
   * <p>The button on Site Wide Configuration does the same thing to every resort at once and is the
   * normal way in; this is for checking one resort, or for a machine with no browser on it.
   */
  public static void main(String... args) {
    PrintWriter out = new PrintWriter(System.out, true);
    List<String> resorts = new ArrayList<>(List.of(args));

    if (resorts.isEmpty()) {
      out.println("usage: ShiftTimeCleanup <resort> [<resort> ...]");
      out.println("   or use the button on the Directors page's Site Wide Configuration,");
      out.println("   which does the same thing to every resort in PatrolData.resortMap.");
      return;
    }

    SessionData sessionData = new SessionData(System.getProperties(), out);
    Logger LOG = new Logger(ShiftTimeCleanup.class, null, "ShiftTimeCleanup", "ShiftTimeCleanup",
                            Logger.INFO);

    out.println("== tidying shift template times ==");
    for (String resort : resorts) {
      if (!PatrolData.isValidResort(resort)) {
        out.println(resort + ": not a resort in PatrolData.resortMap, skipped");
        continue;
      }
      PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
      try {
        ResortOutcome outcome = cleanResort(patrolData.getConnection());
        out.println(resort + ": " + (outcome.ok() ? outcome.detail() : "FAILED - " + outcome.detail()));
      }
      finally {
        patrolData.close();
      }
    }
    out.flush();
  }

  /**
   * Tidy one resort's templates.
   *
   * <p>Returns ok with nothing changed for a resort whose templates are already clean, and ok with
   * a detail line for one that was fixed. A resort that has not had {@link ShiftLabelMigration} run
   * on it has no Label column; that is reported rather than treated as a failure, since the fix is
   * to run the other job first.
   */
  public static ResortOutcome cleanResort(Connection connection) {
    try {
      if (!hasLabelColumn(connection)) {
        return new ResortOutcome(true, false, "no Label column yet - run the shift Label job first");
      }

      List<String[]> updates = new ArrayList<>();   //name, newStart, newLabel, newEnd (null = keep)
      int startsRecovered = 0;
      int startsNormalized = 0;
      int endsNormalized = 0;

      try (Statement statement = connection.createStatement();
           ResultSet rows = statement.executeQuery(
             "SELECT `EventName`, `StartTime`, `EndTime`, `Label` FROM `" + TABLE + "`")) {
        while (rows.next()) {
          String name = rows.getString(1);
          String start = text(rows.getString(2));
          String end = text(rows.getString(3));
          String label = text(rows.getString(4));

          String newStart = null;
          String newLabel = null;
          String newEnd = null;

          //(1) a time stranded in the Label, with no start beside it, belongs back in the start
          String labelAsTime = ShiftTime.normalize(label);
          if (labelAsTime != null && start.isEmpty()) {
            newStart = labelAsTime;
            newLabel = "";
            startsRecovered++;
          }
          //(2) a start that reads as a time but is not written canonically
          else if (!start.isEmpty()) {
            String canonical = ShiftTime.normalize(start);
            if (canonical != null && !canonical.equals(start)) {
              newStart = canonical;
              startsNormalized++;
            }
          }

          //(3) the same for the end, which no migration has ever touched
          if (!end.isEmpty()) {
            String canonical = ShiftTime.normalize(end);
            if (canonical != null && !canonical.equals(end)) {
              newEnd = canonical;
              endsNormalized++;
            }
          }

          if (newStart != null || newEnd != null) {
            updates.add(new String[] {name, newStart, newLabel, newEnd});
          }
        }
      }

      applyUpdates(connection, updates);

      if (updates.isEmpty()) {
        return new ResortOutcome(true, false, "nothing to do");
      }
      StringBuilder detail = new StringBuilder();
      append(detail, startsRecovered, "start time(s) recovered from the Label");
      append(detail, startsNormalized, "start time(s) rewritten");
      append(detail, endsNormalized, "end time(s) rewritten");
      return new ResortOutcome(true, true, detail.toString());
    }
    catch (Exception e) {
      return new ResortOutcome(false, false, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Each row is updated by name, one statement per row.
   *
   * <p>Only the columns that actually changed are written: a row whose end was tidied must not have
   * its start rewritten as a side effect, because "the start was already right" and "the start was
   * set to what it already said" look identical afterwards and only one of them is true.
   */
  private static void applyUpdates(Connection connection, List<String[]> updates) throws Exception {
    String startAndLabel =
      "UPDATE `" + TABLE + "` SET `StartTime` = ?, `Label` = ? WHERE `EventName` = ?";
    String startOnly = "UPDATE `" + TABLE + "` SET `StartTime` = ? WHERE `EventName` = ?";
    String endOnly = "UPDATE `" + TABLE + "` SET `EndTime` = ? WHERE `EventName` = ?";

    try (PreparedStatement withLabel = connection.prepareStatement(startAndLabel);
         PreparedStatement timeOnly = connection.prepareStatement(startOnly);
         PreparedStatement endStatement = connection.prepareStatement(endOnly)) {
      for (String[] update : updates) {
        String name = update[0];
        String newStart = update[1];
        String newLabel = update[2];
        String newEnd = update[3];

        if (newStart != null && newLabel != null) {
          withLabel.setString(1, newStart);
          withLabel.setString(2, newLabel);
          withLabel.setString(3, name);
          withLabel.executeUpdate();
        }
        else if (newStart != null) {
          timeOnly.setString(1, newStart);
          timeOnly.setString(2, name);
          timeOnly.executeUpdate();
        }
        if (newEnd != null) {
          endStatement.setString(1, newEnd);
          endStatement.setString(2, name);
          endStatement.executeUpdate();
        }
      }
    }
  }

  private static void append(StringBuilder detail, int count, String what) {
    if (count > 0) {
      detail.append(detail.length() == 0 ? "" : ", ").append(count).append(' ').append(what);
    }
  }

  private static String text(String raw) {
    return raw == null ? "" : raw.trim();
  }

  private static boolean hasLabelColumn(Connection connection) throws Exception {
    try (ResultSet columns =
           connection.getMetaData().getColumns(connection.getCatalog(), null, TABLE, "Label")) {
      return columns.next();
    }
  }
}
