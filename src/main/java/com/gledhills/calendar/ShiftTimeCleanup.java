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
 *   <li><b>A time sitting in the Label, with no start beside it.</b> {@code ShiftTime} used to
 *       insist on the {@code m} of the meridiem, so {@code 4:00P} and {@code 8:00 a} were read as
 *       labels and moved out of StartTime by the original migration, which then blanked the start.
 *       Those rows have <b>no start time at all</b> until this fills one in. The value is intact
 *       and the reading is exact: on KellyCanyon's history all 1,794 such labels matched the
 *       pre-migration StartTime character for character.
 *
 *       <p><b>The Label is left exactly as it is.</b> Only the missing start is added. The label is
 *       what the calendar draws - {@code getDisplayStartString} prefers it over the time - so
 *       clearing it would change what a director sees on days they never touched, to no purpose:
 *       what was missing was the machine-readable start, not the caption. A row ends up carrying
 *       both, which is precisely the arrangement the Label/Start split was built for.</li>
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
    return clean(connection, TABLE, "EventName", "");
  }

  /**
   * The same tidy-up over the days that are actually <b>scheduled</b>, from today forward.
   *
   * <p>This is the half that a calendar feed actually reads. {@link #cleanResort} fixes the
   * templates, which stops the problem recurring as the season is built; this fixes the days
   * already on the calendar, which is what a patroller would be subscribing to.
   *
   * <p><b>From today forward, and no further back.</b> The same faults run through twenty years of
   * history - thousands of rows - and rewriting a shift somebody worked in 2019 changes a record
   * for nobody's benefit. A past shift is a record; a future one is a plan, and only the plan needs
   * to be readable. The cut is made in SQL on the date prefix of the key, which is stored
   * {@code yyyy-mm-dd_<n>} and so compares correctly as text.
   */
  public static ResortOutcome cleanScheduledDays(Connection connection, java.time.LocalDate from) {
    String cutoff = from.toString();
    return clean(connection, "assignments", "Date",
                 " WHERE LEFT(`Date`, 10) >= '" + cutoff.replace("'", "") + "'");
  }

  /**
   * One table's worth of tidying.
   *
   * @param keyColumn what identifies a row - the template name, or the dated slot
   * @param where     an optional SQL filter, already safe to concatenate
   */
  private static ResortOutcome clean(Connection connection, String table, String keyColumn,
                                     String where) {
    try {
      if (!hasLabelColumn(connection, table)) {
        return new ResortOutcome(true, false, "no Label column yet - run the shift Label job first");
      }

      List<String[]> updates = new ArrayList<>();   //key, newStart, newEnd (null = leave alone)
      int startsRecovered = 0;
      int startsNormalized = 0;
      int endsNormalized = 0;

      try (Statement statement = connection.createStatement();
           ResultSet rows = statement.executeQuery(
             "SELECT `" + keyColumn + "`, `StartTime`, `EndTime`, `Label` FROM `"
             + table + "`" + where)) {
        while (rows.next()) {
          String name = rows.getString(1);
          String start = text(rows.getString(2));
          String end = text(rows.getString(3));
          String label = text(rows.getString(4));

          String newStart = null;
          String newEnd = null;

          //(1) a time sitting in the Label with no start beside it: fill the start in from it and
          //    LEAVE THE LABEL ALONE. The label is what the calendar draws, so clearing it would
          //    change what every director sees on a day they did not touch - for no gain, since
          //    what was actually missing is the machine-readable start, not the caption.
          String labelAsTime = ShiftTime.normalize(label);
          if (labelAsTime == null) {
            labelAsTime = timeInsideLabel(label);      //"Vol 10:00AM", "8:30 Paid"
          }
          if (labelAsTime != null && start.isEmpty()) {
            newStart = labelAsTime;
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
            updates.add(new String[] {name, newStart, newEnd});
          }
        }
      }

      applyUpdates(connection, table, keyColumn, updates);

      if (updates.isEmpty()) {
        return new ResortOutcome(true, false, "nothing to do");
      }
      StringBuilder detail = new StringBuilder();
      append(detail, startsRecovered, "start time(s) filled in from the Label");
      append(detail, startsNormalized, "start time(s) rewritten");
      append(detail, endsNormalized, "end time(s) rewritten");
      return new ResortOutcome(true, true, detail.toString());
    }
    catch (Exception e) {
      return new ResortOutcome(false, false, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Each row is updated by key, one statement per column that changed.
   *
   * <p>Only the columns that actually changed are written: a row whose end was tidied must not have
   * its start rewritten as a side effect, because "the start was already right" and "the start was
   * set to what it already said" look identical afterwards and only one of them is true.
   *
   * <p><b>The Label is never written here.</b> Nothing this pass does is worth changing a caption a
   * director chose.
   */
  private static void applyUpdates(Connection connection, String table, String keyColumn,
                                   List<String[]> updates) throws Exception {
    String startOnly =
      "UPDATE `" + table + "` SET `StartTime` = ? WHERE `" + keyColumn + "` = ?";
    String endOnly = "UPDATE `" + table + "` SET `EndTime` = ? WHERE `" + keyColumn + "` = ?";

    try (PreparedStatement startStatement = connection.prepareStatement(startOnly);
         PreparedStatement endStatement = connection.prepareStatement(endOnly)) {
      for (String[] update : updates) {
        String key = update[0];
        String newStart = update[1];
        String newEnd = update[2];

        if (newStart != null) {
          startStatement.setString(1, newStart);
          startStatement.setString(2, key);
          startStatement.executeUpdate();
        }
        if (newEnd != null) {
          endStatement.setString(1, newEnd);
          endStatement.setString(2, key);
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


  /**
   * A start time a director wrote into one end of a label, or null when there is not one.
   *
   * <p>Real examples, all with an empty StartTime beside them: {@code Vol 10:00AM},
   * {@code Pd 1:00PM}, {@code HC 8:00}, {@code Vol 1530}, {@code 8:30 Paid}, {@code 9:15 Ski}. The
   * time is at the end more often than the start, but both happen, and a rule that read only one
   * end would fix {@code HC 8:00} while leaving {@code 8:30 Paid} - the single commonest label of
   * this shape - untouched.
   *
   * <p><b>Only the first or last whitespace-separated word is considered</b>, never the middle.
   * The label is a caption somebody wrote; hunting through it for anything time-shaped is how
   * {@code TC: 4 00} turns into a shift at four o'clock.
   */
  private static String timeInsideLabel(String label) {
    String[] words = label.trim().split("\\s+");
    if (words.length < 2) {
      return null;                     //one word: either it is a time, and the caller had it, or not
    }
    String fromEnd = deliberateTime(words[words.length - 1]);
    return fromEnd != null ? fromEnd : deliberateTime(words[0]);
  }

  /**
   * The word as a time, but <b>only when it was plainly meant as one</b>.
   *
   * <p>Stricter than {@link ShiftTime#normalize} on purpose, and this is the whole safety of the
   * feature. {@code normalize} reads a bare number as a 24 hour clock - which is right for a Start
   * field, where a director typing "8" means eight o'clock, and catastrophic here: the real labels
   * include {@code Train 2}, {@code Avy 1}, {@code Mentee 9} and {@code S&amp;T 9}, which are course
   * and team numbers. Reading those as times would put patrollers on shifts at 2am that nobody
   * scheduled, and the label would still say "Train 2" so nobody would spot it.
   *
   * <p>So a word counts only if it carries its own evidence of being a clock time: a colon
   * ({@code 8:00}), a meridiem attached to a digit ({@code 10AM}, {@code 4P}), or the three-or-four
   * digit packed form ({@code 1530}). A bare one or two digit number never qualifies.
   */
  private static String deliberateTime(String word) {
    boolean saysItIsATime = word.contains(":")
                            || word.matches("(?i).*\\d\\s*[ap]\\.?m?\\.?")
                            || word.matches("\\d{3,4}");
    return saysItIsATime ? ShiftTime.normalize(word) : null;
  }

  private static String text(String raw) {
    return raw == null ? "" : raw.trim();
  }

  private static boolean hasLabelColumn(Connection connection, String table) throws Exception {
    try (ResultSet columns =
           connection.getMetaData().getColumns(connection.getCatalog(), null, table, "Label")) {
      return columns.next();
    }
  }
}
