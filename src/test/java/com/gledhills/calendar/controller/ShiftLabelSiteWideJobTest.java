package com.gledhills.calendar.controller;

import com.gledhills.calendar.ShiftLabelMigration;
import org.junit.jupiter.api.AfterEach;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Site Wide Configuration button that gives every resort the shift Label column.
 *
 * <p>The job itself is destructive in the sense that it rewrites shift rows, so what is worth
 * pinning is that a second press does <b>nothing</b> - which is also what makes it safe to leave
 * on a page a tired director may double-click.
 *
 * <p>Sample and Brighton are already migrated on this machine, so running the job here is expected
 * to be a no-op; that is exactly the property under test rather than a limitation of it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ShiftLabelSiteWideJobTest {

    private static final String OWNER_ID = "192443";
    private static final String DIRECTOR_ID = "123456";
    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /**
     * The job on a database in the shape every unmigrated resort is in: no Label column, and
     * StartTime holding a mix of times and job names.
     *
     * <p>Built here rather than borrowed from Sample or Brighton, both of which are already
     * migrated - without this, a migrateResort that did nothing whatsoever would pass every other
     * test in this class.
     */
    @Test
    void itActuallyMigratesADatabaseThatNeedsIt() throws Exception {
        try (Connection connection = scratchDatabase()) {
            ShiftLabelMigration.ResortOutcome outcome =
                ShiftLabelMigration.migrateResort(connection);

            assertTrue(outcome.ok(), outcome.detail());
            assertTrue(outcome.changed(), "it had work to do: " + outcome.detail());
            assertTrue(outcome.detail().contains("Label column added"), outcome.detail());

            //"dir" was never a time: it moves, and the Start it was sitting in is emptied
            assertEquals("dir", labelOf(connection, "Saturday_0"));
            assertEquals("", startOf(connection, "Saturday_0"));

            //"0800" is a time: it stays, in the one canonical form
            assertEquals("", labelOf(connection, "Saturday_1"));
            assertEquals("8:00", startOf(connection, "Saturday_1"));

            //already canonical, and blank, are both left exactly as they were
            assertEquals("16:30", startOf(connection, "Saturday_2"));
            assertEquals("", startOf(connection, "Saturday_3"));

            //and now it is settled
            ShiftLabelMigration.ResortOutcome again = ShiftLabelMigration.migrateResort(connection);
            assertTrue(again.ok(), again.detail());
            assertFalse(again.changed(), "second run on the same database: " + again.detail());
        }
    }

    /**
     * A resort whose column was added but whose rows were never moved - what an interrupted run
     * leaves behind, and what a plain "does the column exist?" check would skip forever.
     */
    @Test
    void itFinishesAnInterruptedRun() throws Exception {
        try (Connection connection = scratchDatabase()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "ALTER TABLE `shiftdefinitions` ADD COLUMN `Label` varchar(32) NOT NULL DEFAULT ''");
            }

            ShiftLabelMigration.ResortOutcome outcome =
                ShiftLabelMigration.migrateResort(connection);

            assertTrue(outcome.ok(), outcome.detail());
            assertTrue(outcome.changed(),
                       "the column existed but the data was still old: " + outcome.detail());
            assertEquals("dir", labelOf(connection, "Saturday_0"));
        }
    }

    /** Running it on an already-migrated resort writes nothing and says so. */
    @Test
    void aSecondRunDoesNothing() {
        ShiftLabelMigration.ResortOutcome first = migrate("Sample");
        assertTrue(first.ok(), "Sample should migrate cleanly: " + first.detail());

        ShiftLabelMigration.ResortOutcome second = migrate("Sample");
        assertTrue(second.ok(), second.detail());
        assertFalse(second.changed(), "a second run must write nothing: " + second.detail());
        assertEquals("nothing to do", second.detail());
    }

    /**
     * The job covers every resort in the map, which is the whole point of it living on this page.
     *
     * <p>It was held to Sample alone while it was being proved on the live site; this is what says
     * that restriction has been lifted on purpose rather than left half-removed.
     */
    @Test
    void theJobCoversEveryResort() {
        assertEquals(PatrolData.resortMap.keySet(),
                     SiteWideConfigurationController.shiftLabelResorts());
        assertTrue(SiteWideConfigurationController.shiftLabelResorts().size() > 1,
                   "the map should hold every patrol, not a leftover test list");
        assertTrue(SiteWideConfigurationController.shiftLabelResorts().contains("Brighton"));
        assertTrue(SiteWideConfigurationController.shiftLabelResorts().contains("Sample"));
    }

    /**
     * Pressing it reports every resort, including the ones with no database on this machine -
     * a resort that cannot be reached has to be visible, not silently missing from the list.
     */
    @Test
    void pressingItReportsEveryResort() throws Exception {
        String page = mockMvc.perform(post("/SiteWideConfiguration")
                                          .param("resort", "Brighton").param("ID", OWNER_ID)
                                          .param("action", "shiftLabels")
                                          .header("User-Agent", BROWSER).session(ownerSession()))
                             .andExpect(status().isOk())
                             .andReturn().getResponse().getContentAsString();

        String results = page.substring(page.indexOf("Shift Label column added"));
        for (String resort : PatrolData.resortMap.keySet()) {
            assertTrue(results.contains(resort), resort + " is missing from the results");
        }
    }

    /** Brighton is the resort whose data drove this; it too should now be settled. */
    @Test
    void brightonIsAlreadySettled() {
        ShiftLabelMigration.ResortOutcome outcome = migrate("Brighton");
        assertTrue(outcome.ok(), outcome.detail());
        assertFalse(outcome.changed(),
                    "Brighton was migrated already, so this should be a no-op: " + outcome.detail());
    }

    @Test
    void theOwnerSeesTheButton() throws Exception {
        String page = mockMvc.perform(get("/SiteWideConfiguration")
                                          .param("resort", "Brighton").param("ID", OWNER_ID)
                                          .header("User-Agent", BROWSER).session(ownerSession()))
                             .andExpect(status().isOk())
                             .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("value=\"shiftLabels\""), "the job needs its own action");
        assertTrue(page.contains("Add shift Labels in every resort"), "and a button to press");
    }

    /**
     * The gate is on the POST, not only on whether the button is drawn - 47 other resorts' shift
     * data is nobody else's to rewrite.
     */
    @Test
    void anotherResortsDirectorCannotRunIt() throws Exception {
        assertEquals(403, mockMvc.perform(post("/SiteWideConfiguration")
                                              .param("resort", "Sample").param("ID", DIRECTOR_ID)
                                              .param("action", "shiftLabels")
                                              .header("User-Agent", BROWSER)
                                              .session(session("Sample", DIRECTOR_ID)))
                                 .andReturn().getResponse().getStatus());
    }

    /** The owner's id on somebody else's resort is not the owner. */
    @Test
    void theOwnerIdOnAnotherResortCannotRunIt() throws Exception {
        assertEquals(403, mockMvc.perform(post("/SiteWideConfiguration")
                                              .param("resort", "Sample").param("ID", OWNER_ID)
                                              .param("action", "shiftLabels")
                                              .header("User-Agent", BROWSER)
                                              .session(session("Sample", OWNER_ID)))
                                 .andReturn().getResponse().getStatus());
    }

    private ShiftLabelMigration.ResortOutcome migrate(String resort) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(ownerSession());
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger log = new Logger(ShiftLabelSiteWideJobTest.class, javaxRequest, "GET", resort,
                                Logger.INFO);
        PatrolData patrolData =
            new PatrolData(resort, new SessionData(javaxRequest, null, log), log);
        try {
            return ShiftLabelMigration.migrateResort(patrolData.getConnection());
        }
        finally {
            patrolData.close();
        }
    }

    private static final String SCRATCH_DB = "patrolCalendarShiftLabelJobTest";

    /** A throwaway database in the pre-migration shape: no Label column anywhere. */
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
        //the catalog has to be selected before the statement that uses it is created, or the
        //driver binds the statement to no database and every DDL below fails
        connection.setCatalog(SCRATCH_DB);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE `shiftdefinitions` (`EventName` varchar(128) NOT NULL DEFAULT '',"
                + " `StartTime` varchar(16) NOT NULL DEFAULT '',"
                + " `EndTime` varchar(16) NOT NULL DEFAULT '',"
                + " `Count` tinyint NOT NULL DEFAULT '0', `ShiftType` tinyint NOT NULL DEFAULT '0',"
                + " PRIMARY KEY (`EventName`))");
            statement.executeUpdate(
                "CREATE TABLE `assignments` (`Date` varchar(14) NOT NULL DEFAULT '',"
                + " `StartTime` varchar(12) DEFAULT NULL, `EndTime` varchar(12) DEFAULT NULL,"
                + " PRIMARY KEY (`Date`))");
            statement.executeUpdate(
                "INSERT INTO `shiftdefinitions` (EventName, StartTime, EndTime) VALUES"
                + " ('Saturday_0','dir','16:30'),"      //a label in the wrong column
                + " ('Saturday_1','0800','16:30'),"     //a time needing canonical form
                + " ('Saturday_2','16:30','21:00'),"    //already canonical
                + " ('Saturday_3','','21:00')");        //blank
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

    private String labelOf(Connection connection, String eventName) throws Exception {
        return columnOf(connection, "Label", eventName);
    }

    private String startOf(Connection connection, String eventName) throws Exception {
        return columnOf(connection, "StartTime", eventName);
    }

    private String columnOf(Connection connection, String column, String eventName)
        throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
               "SELECT `" + column + "` FROM `shiftdefinitions` WHERE EventName = '" + eventName + "'")) {
            assertTrue(rows.next(), eventName + " should exist");
            return rows.getString(1);
        }
    }

    private MockHttpSession ownerSession() {
        return session("Brighton", OWNER_ID);
    }

    private MockHttpSession session(String resort, String id) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", id);
        session.setAttribute("loggedInResort", resort);
        return session;
    }
}
