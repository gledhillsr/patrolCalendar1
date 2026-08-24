package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Site Wide Configuration: jobs that walk every resort's database rather than the current one.
 *
 * Restricted to the site owner by resort and id together - "is a director" would not do, because
 * every resort has directors and none of them should be reaching into the other 47 databases.
 *
 * The create job is idempotent (CREATE TABLE IF NOT EXISTS), so running it here is safe; only
 * Sample and Brighton have databases locally, and the rest are expected to report a failure.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class SiteWideConfigurationTest {

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

    @Test
    void theOwnerReachesThePage() throws Exception {
        MvcResult result = request(get("/SiteWideConfiguration"), "Brighton", OWNER_ID);

        assertEquals(200, result.getResponse().getStatus(), "the owner should get in");
        assertTrue(result.getResponse().getContentAsString().contains("Site Wide Configuration"),
                   "and see the page");
    }

    /** A director of some other resort is still nobody here. */
    @Test
    void anotherResortsDirectorIsRefused() throws Exception {
        assertEquals(403, request(get("/SiteWideConfiguration"), "Sample", DIRECTOR_ID)
                              .getResponse().getStatus(),
                     "a Sample director should not reach it");
        assertEquals(403, request(post("/SiteWideConfiguration"), "Sample", DIRECTOR_ID)
                              .getResponse().getStatus(),
                     "nor run it");
    }

    /** Right id, wrong resort - both have to match. */
    @Test
    void theOwnerIdOnAnotherResortIsRefused() throws Exception {
        assertEquals(403, request(get("/SiteWideConfiguration"), "Sample", OWNER_ID)
                              .getResponse().getStatus(),
                     "the id alone is not enough");
    }

    @Test
    void theButtonOnlyShowsForTheOwner() throws Exception {
        String owners = request(get("/Directors"), "Brighton", OWNER_ID)
            .getResponse().getContentAsString();
        String directors = request(get("/Directors"), "Sample", DIRECTOR_ID)
            .getResponse().getContentAsString();

        assertTrue(owners.contains("Site Wide Configuration"), "the owner should see the button");
        assertFalse(directors.contains("Site Wide Configuration"),
                    "an ordinary director should not");
    }

    /** The job itself: every resort reported, and the ones with a database get the table. */
    @Test
    void runningItReportsEveryResort() throws Exception {
        String page = request(post("/SiteWideConfiguration"), "Brighton", OWNER_ID)
            .getResponse().getContentAsString();

        assertTrue(page.contains("Sample"), "Sample should be in the report: " + page);
        assertTrue(page.contains("Brighton"), "and Brighton");
        assertTrue(page.contains("resorts reported success"), "with a summary line");
    }

    /** Both self-made tables, not just one: a resort added later needs all of them. */
    @Test
    void itCreatesBothTables() throws Exception {
        request(post("/SiteWideConfiguration"), "Brighton", OWNER_ID);

        for (String table : new String[] {"menubuttons", "shifttemplatepatrollers"}) {
            assertTrue(tableExists("Sample", table), table + " should exist in Sample");
            assertTrue(tableExists("Brighton", table), table + " should exist in Brighton");
        }
        //this one is prefixed per resort rather than shared
        assertTrue(tableExists("Sample", "Sample_password_reset_tokens"),
                   "the token table should exist in Sample");
        assertTrue(tableExists("Brighton", "Brighton_password_reset_tokens"),
                   "and in Brighton");
    }

    /** Safe to run twice - that is the point of CREATE TABLE IF NOT EXISTS. */
    @Test
    void runningItTwiceIsHarmless() throws Exception {
        request(post("/SiteWideConfiguration"), "Brighton", OWNER_ID);
        MvcResult second = request(post("/SiteWideConfiguration"), "Brighton", OWNER_ID);

        assertEquals(200, second.getResponse().getStatus(), "a second run should be fine");
    }

    /**
     * The clean-up job, on a row made for the test.
     *
     * The assertion that matters is the second one: the rule is
     * {@code EventName LIKE '\_%'}, and an unescaped underscore is a single-character wildcard
     * that matches <em>every</em> template - a resort's whole shift configuration.
     */
    @Test
    void itRemovesUnnamedTemplatesAndNothingElse() throws Exception {
        int namedBefore = templateRowCount("Sample") ;
        insertUnnamedTemplate("Sample");
        assertEquals(namedBefore + 1, templateRowCount("Sample"), "the test row should be in");

        String found = request(post("/SiteWideConfiguration"), "Brighton", OWNER_ID, "findUnnamed")
            .getResponse().getContentAsString();
        assertTrue(found.contains("1 to remove"), "find should report it: " + found);
        assertEquals(namedBefore + 1, templateRowCount("Sample"), "and find must not delete");

        String removed = request(post("/SiteWideConfiguration"), "Brighton", OWNER_ID, "removeUnnamed")
            .getResponse().getContentAsString();
        assertTrue(removed.contains("1 removed"), "remove should report it: " + removed);

        assertEquals(namedBefore, templateRowCount("Sample"),
                     "exactly one row should have gone - if this is 0, the underscore was treated "
                       + "as a LIKE wildcard and the whole shift configuration was deleted");
    }

    private int templateRowCount(String resort) {
        return scalar(resort, "SELECT COUNT(*) FROM shiftdefinitions");
    }

    private void insertUnnamedTemplate(String resort) {
        scalar(resort, "INSERT INTO shiftdefinitions (EventName, StartTime, EndTime, Count, "
                         + "ShiftType) VALUES ('_9', '8:00', '16:00', 1, 0)");
    }

    /** Runs a statement against a resort; returns the first column of the first row, or 0. */
    private int scalar(String resort, String sql) {
        org.springframework.mock.web.MockHttpServletRequest request =
            new org.springframework.mock.web.MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", OWNER_ID);
        session.setAttribute("loggedInResort", resort);
        request.setSession(session);
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        com.gledhills.calendar.utils.Logger log = new com.gledhills.calendar.utils.Logger(
            SiteWideConfigurationTest.class, javaxRequest, "GET", resort,
            com.gledhills.calendar.utils.Logger.ERROR);
        com.gledhills.calendar.utils.PatrolData patrolData =
            new com.gledhills.calendar.utils.PatrolData(
                resort, new com.gledhills.calendar.utils.SessionData(javaxRequest, null, log), log);
        try (java.sql.Statement statement = patrolData.getConnection().createStatement()) {
            if (sql.startsWith("SELECT")) {
                java.sql.ResultSet results = statement.executeQuery(sql);
                return results.next() ? results.getInt(1) : 0;
            }
            statement.executeUpdate(sql);
            return 0;
        }
        catch (java.sql.SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        finally {
            patrolData.close();
        }
    }

    private boolean tableExists(String resort, String table) {
        org.springframework.mock.web.MockHttpServletRequest request =
            new org.springframework.mock.web.MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", OWNER_ID);
        session.setAttribute("loggedInResort", resort);
        request.setSession(session);
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        com.gledhills.calendar.utils.Logger log = new com.gledhills.calendar.utils.Logger(
            SiteWideConfigurationTest.class, javaxRequest, "GET", resort,
            com.gledhills.calendar.utils.Logger.ERROR);
        com.gledhills.calendar.utils.PatrolData patrolData =
            new com.gledhills.calendar.utils.PatrolData(
                resort, new com.gledhills.calendar.utils.SessionData(javaxRequest, null, log), log);
        try (java.sql.ResultSet results = patrolData.getConnection().getMetaData()
                                              .getTables(null, null, table, null)) {
            return results.next();
        }
        catch (java.sql.SQLException e) {
            return false;
        }
        finally {
            patrolData.close();
        }
    }

    private MvcResult request(MockHttpServletRequestBuilder builder, String resort, String id,
                              String action) throws Exception {
        return request(builder.param("action", action), resort, id);
    }

    private MvcResult request(MockHttpServletRequestBuilder builder, String resort, String id)
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", id);
        session.setAttribute("loggedInResort", resort);

        return mockMvc.perform(builder
                                   .param("resort", resort)
                                   .param("ID", id)
                                   .header("User-Agent", BROWSER)
                                   .session(session))
            .andReturn();
    }
}
