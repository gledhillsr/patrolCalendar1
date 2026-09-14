package com.gledhills.calendar;

import com.gledhills.calendar.ShiftLabelMigration.ResortOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pass that tidies shift template times.
 *
 * <p>Built against a throwaway database in the shape a real resort is in <em>after</em> the Label
 * migration and before this one, because every resort on this machine has already been through
 * both - without that, a {@code cleanResort} that did nothing at all would pass.
 *
 * <p>The two properties worth pinning are opposite ones: that it fixes what it can, and that it
 * leaves alone what it cannot. The second is the one that would go wrong quietly - blanking an
 * unreadable end, or "helpfully" guessing at it, loses the only record that the shift ever had one.
 */
class ShiftTimeCleanupTest {

  private static final String SCRATCH_DB = "shiftTimeCleanupScratch";

  /**
   * A database holding one of everything, run twice.
   *
   * <p>The row names say what each is for; the assertions below are in the same order.
   */
  @Test
  void itFixesWhatItCanAndLeavesTheRestAlone() throws Exception {
    try (Connection connection = scratchDatabase()) {
      ResortOutcome outcome = ShiftTimeCleanup.cleanResort(connection);

      assertTrue(outcome.ok(), outcome.detail());
      assertTrue(outcome.changed(), "it had work to do: " + outcome.detail());

      //(1) a time the old rule rejected, sitting in the Label with no start beside it. This is
      //    the row that had no start time at all until now.
      assertEquals("16:00", startOf(connection, "Friday_0"), "the start is filled in from the Label");
      assertEquals("4:00P", labelOf(connection, "Friday_0"),
                   "and the Label is left exactly as the director wrote it - it is what the "
                     + "calendar draws, and only the missing start was the problem");
      assertEquals("21:00", endOf(connection, "Friday_0"), "'9:00 p' is a time, so it is tidied too");

      //(2) an end that was never migrated: readable, just not canonical
      assertEquals("16:00", endOf(connection, "Friday_1"));
      assertEquals("8:00", startOf(connection, "Friday_1"), "a correct start is not rewritten");

      //(3) already canonical, with a real label beside it: untouched
      assertEquals("dir", labelOf(connection, "Friday_2"));
      assertEquals("8:00", startOf(connection, "Friday_2"));
      assertEquals("16:30", endOf(connection, "Friday_2"));

      //(4) an end nobody can read stays exactly as it is - see the class comment
      assertEquals("Close", endOf(connection, "Friday_3"), "an unreadable end must survive");
      assertEquals("8:00", startOf(connection, "Friday_3"));

      //(5) a label-only row is legal and is not turned into a time
      assertEquals("mentee", labelOf(connection, "Friday_4"));
      assertEquals("", startOf(connection, "Friday_4"));
      assertEquals("", endOf(connection, "Friday_4"), "a blank end is not invented");

      //(6) a packed start still gets canonicalised
      assertEquals("8:00", startOf(connection, "Friday_5"));

      //(7) a director may deliberately caption a shift with an abbreviated time - "6pm" is a
      //    perfectly good thing to want on the calendar. The caption stays exactly as typed and
      //    the machine-readable start is added beside it, which is the whole point of the split.
      assertEquals("6pm", labelOf(connection, "Friday_6"), "the abbreviation the director chose");
      assertEquals("18:00", startOf(connection, "Friday_6"), "with the real start filled in");
      assertEquals("22:00", endOf(connection, "Friday_6"), "and the end tidied as usual");

      //(8) a time written into one end of a caption is used for the start, caption untouched
      assertEquals("Vol 10:00AM", labelOf(connection, "Friday_7"), "the caption is the director's");
      assertEquals("10:00", startOf(connection, "Friday_7"), "with the time taken off the end");
      assertEquals("14:00", endOf(connection, "Friday_7"));

      assertEquals("8:30 Paid", labelOf(connection, "Friday_8"));
      assertEquals("8:30", startOf(connection, "Friday_8"), "and off the front when it is there");

      //(9) THE important one. "Train 2" and "Avy 1" are course and team numbers. Reading the bare
      //    number as a clock time would schedule people at 02:00 and 01:00 while the label still
      //    said "Train 2", so nothing on the page would look wrong.
      assertEquals("", startOf(connection, "Friday_9"),
                   "a course number must never be read as a time");
      assertEquals("Train 2", labelOf(connection, "Friday_9"));
      assertEquals("", startOf(connection, "Friday_10"), "nor a team number");
      assertEquals("Avy 1", labelOf(connection, "Friday_10"));

      //and it is settled: pressing the button again writes nothing
      ResortOutcome again = ShiftTimeCleanup.cleanResort(connection);
      assertTrue(again.ok(), again.detail());
      assertFalse(again.changed(), "second run on the same database: " + again.detail());
      assertEquals("nothing to do", again.detail());
    }
  }

  /**
   * A resort that has not had the Label migration is reported, not failed.
   *
   * <p>It is a real state - several resorts in resortMap have never been migrated - and the fix is
   * to run the other job, so the message says that rather than throwing a column error at whoever
   * pressed the button.
   */
  @Test
  void aResortWithNoLabelColumnIsToldWhatToDo() throws Exception {
    try (Connection connection = scratchDatabase()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("ALTER TABLE `shiftdefinitions` DROP COLUMN `Label`");
      }

      ResortOutcome outcome = ShiftTimeCleanup.cleanResort(connection);

      assertTrue(outcome.ok(), "a resort awaiting the other job is not a failure");
      assertFalse(outcome.changed());
      assertTrue(outcome.detail().contains("Label"), outcome.detail());
    }
  }

  private Connection scratchDatabase() throws Exception {
    Properties credentials = new Properties();
    credentials.load(new java.io.FileInputStream("/etc/patrolCalendar/credentials.properties"));
    Connection connection = DriverManager.getConnection(
      "jdbc:mysql://127.0.0.1/?allowPublicKeyRetrieval=true&useSSL=false",
      credentials.getProperty("dbUser"), credentials.getProperty("dbPassword"));

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DROP DATABASE IF EXISTS `" + SCRATCH_DB + "`");
      statement.executeUpdate("CREATE DATABASE `" + SCRATCH_DB + "`");
    }
    //the catalog has to be selected before the statement that uses it is created
    connection.setCatalog(SCRATCH_DB);

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
        "CREATE TABLE `shiftdefinitions` (`EventName` varchar(128) NOT NULL DEFAULT '',"
        + " `StartTime` varchar(16) NOT NULL DEFAULT '',"
        + " `EndTime` varchar(16) NOT NULL DEFAULT '',"
        + " `Count` tinyint NOT NULL DEFAULT '0', `ShiftType` tinyint NOT NULL DEFAULT '0',"
        + " `Label` varchar(32) NOT NULL DEFAULT '', PRIMARY KEY (`EventName`))");
      statement.executeUpdate(
        "INSERT INTO `shiftdefinitions` (EventName, Label, StartTime, EndTime) VALUES"
        + " ('Friday_0','4:00P','','9:00 p'),"     //a start stranded in the Label by the old rule
        + " ('Friday_1','','8:00','1600'),"        //an end that was never migrated
        + " ('Friday_2','dir','8:00','16:30'),"    //already right
        + " ('Friday_3','','8:00','Close'),"       //an end nobody can read
        + " ('Friday_4','mentee','',''),"          //a label-only shift
        + " ('Friday_5','','0800',''),"            //a packed start
        + " ('Friday_6','6pm','','10pm'),"         //an abbreviated time used as the label
        + " ('Friday_7','Vol 10:00AM','','2:00PM'),"  //a time on the end of a caption
        + " ('Friday_8','8:30 Paid','',''),"       //and a time on the front of one
        + " ('Friday_9','Train 2','',''),"         //a COURSE number, not half past two
        + " ('Friday_10','Avy 1','','')");         //nor a shift at one o'clock
    }
    return connection;
  }

  @AfterEach
  void dropScratchDatabase() throws Exception {
    Properties credentials = new Properties();
    credentials.load(new java.io.FileInputStream("/etc/patrolCalendar/credentials.properties"));
    try (Connection connection = DriverManager.getConnection(
           "jdbc:mysql://127.0.0.1/?allowPublicKeyRetrieval=true&useSSL=false",
           credentials.getProperty("dbUser"), credentials.getProperty("dbPassword"));
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("DROP DATABASE IF EXISTS `" + SCRATCH_DB + "`");
    }
  }

  private String labelOf(Connection c, String name) throws Exception {
    return columnOf(c, "Label", name);
  }

  private String startOf(Connection c, String name) throws Exception {
    return columnOf(c, "StartTime", name);
  }

  private String endOf(Connection c, String name) throws Exception {
    return columnOf(c, "EndTime", name);
  }

  private String columnOf(Connection connection, String column, String eventName) throws Exception {
    try (Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery(
           "SELECT `" + column + "` FROM `shiftdefinitions` WHERE EventName = '" + eventName + "'")) {
      assertTrue(rows.next(), eventName + " should exist");
      return rows.getString(1);
    }
  }
}
