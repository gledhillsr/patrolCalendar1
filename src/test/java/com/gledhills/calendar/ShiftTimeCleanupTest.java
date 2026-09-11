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

      //(1) a time the old rule rejected, stranded in the Label with no start beside it. This is
      //    the row that had no start time at all until now.
      assertEquals("16:00", startOf(connection, "Friday_0"), "the start comes back out of the Label");
      assertEquals("", labelOf(connection, "Friday_0"), "and the Label is cleared, not left doubled");
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
        + " ('Friday_5','','0800','')");           //a packed start
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
