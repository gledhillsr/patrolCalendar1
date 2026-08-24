package com.gledhills.calendar.store;

import com.gledhills.calendar.controller.ServletAdapter;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps createTablesForNewResort.sql honest.
 *
 * That file is how a new resort's database gets built, and nothing else reads it - so without a
 * test it goes stale the first time somebody adds a table, and the staleness only surfaces when a
 * new resort half works months later.
 *
 * Compares it against a live resort rather than a hardcoded list, so adding a table to the app is
 * what makes this fail.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class CreateTablesForNewResortSqlTest {

  private static final Path SQL_FILE = Path.of("createTablesForNewResort.sql");

  /** Prefixed per resort rather than shared, so it appears in the file as a {RESORT} placeholder. */
  private static final String RESORT_PREFIXED_TOKENS = "{RESORT}_password_reset_tokens";

  @Test
  void theFileHoldsEveryTableAWorkingResortHas() throws Exception {
    Set<String> inFile = tablesIn(Files.readString(SQL_FILE));
    Set<String> inDatabase = tablesInSampleResort();

    // the token table is named after its resort, so the file carries a {RESORT} placeholder and
    // the database carries "Sample_password_reset_tokens" - neither side can match the other
    // literally, and theSelfMadeTablesAreCovered checks the file has it
    inFile.remove(RESORT_PREFIXED_TOKENS);
    inDatabase.removeIf(table -> table.endsWith("_password_reset_tokens"));

    assertEquals(inDatabase, inFile,
                 "createTablesForNewResort.sql has drifted from a working resort's schema."
                   + "\n  in the database but not the file: " + missing(inDatabase, inFile)
                   + "\n  in the file but not the database: " + missing(inFile, inDatabase)
                   + "\n  Re-record it from a healthy resort and read the diff.");
  }

  /** The tables the app creates for itself must be in here, or a new resort cannot be set up. */
  @Test
  void theSelfMadeTablesAreCovered() throws Exception {
    Set<String> inFile = tablesIn(Files.readString(SQL_FILE));

    for (String table : new String[] {"menubuttons", "shifttemplatepatrollers",
                                      RESORT_PREFIXED_TOKENS}) {
      assertTrue(inFile.contains(table), table + " should be in createTablesForNewResort.sql");
    }
  }

  /** Re-running it against a resort that is already up must not be a destructive act. */
  @Test
  void everyStatementIsSafeToRerun() throws IOException {
    String sql = Files.readString(SQL_FILE);

    //anchored on the backtick so the prose above, which mentions the statement, is not counted
    long creates = countOf(sql, "CREATE TABLE `") + countOf(sql, "CREATE TABLE IF NOT EXISTS `");
    long guarded = countOf(sql, "CREATE TABLE IF NOT EXISTS `");

    assertTrue(creates > 0, "it should create tables");
    assertEquals(creates, guarded,
                 "every CREATE TABLE needs IF NOT EXISTS - this runs against live resorts");
    assertTrue(!sql.toUpperCase().contains("DROP TABLE"),
               "nothing here should drop a table - this file runs against live resorts");
  }

  private long countOf(String text, String needle) {
    long count = 0;
    int at = text.indexOf(needle);
    while (at >= 0) {
      count++;
      at = text.indexOf(needle, at + needle.length());
    }
    return count;
  }

  private Set<String> tablesIn(String sql) {
    Set<String> found = new LinkedHashSet<>();
    Matcher matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS `([^`]+)`").matcher(sql);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  private Set<String> tablesInSampleResort() {
    Set<String> found = new LinkedHashSet<>();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", "123456");
    session.setAttribute("loggedInResort", "Sample");
    request.setSession(session);
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(CreateTablesForNewResortSqlTest.class, javaxRequest, "GET", "Sample",
                            Logger.ERROR);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);

    try (ResultSet tables = patrolData.getConnection().getMetaData()
                                //scoped to this connection's database - a null catalog returns
                                //every schema on the server, Brighton's and mysql's included
                                .getTables(patrolData.getConnection().getCatalog(), null, "%",
                                           new String[] {"TABLE"})) {
      while (tables.next()) {
        found.add(tables.getString("TABLE_NAME"));
      }
    }
    catch (SQLException e) {
      throw new IllegalStateException("could not read the Sample schema", e);
    }
    finally {
      patrolData.close();
    }
    return found;
  }

  private Set<String> missing(Set<String> from, Set<String> other) {
    Set<String> only = new LinkedHashSet<>(from);
    only.removeAll(other);
    return only;
  }
}
