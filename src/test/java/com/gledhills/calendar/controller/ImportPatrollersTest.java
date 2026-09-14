package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.SqlWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Importing new patrollers from an nsp.org roster export.
 *
 * <p>Writes to Sample's roster, and removes what it added afterwards. The ids used are far outside
 * the range any real Sample member has, so a failed run cannot delete somebody real.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ImportPatrollersTest {

    private static final String DIRECTOR_ID = "123456";
    private static final String MEMBER_ID = "200401";
    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    /** Ids no Sample member has; cleaned up after every test. */
    private static final String NEW_ID = "990001";
    private static final String SECOND_ID = "990002";

    private static final String HEADER =
        "ID,First Name,Last Name,Dues Paid/Not Paid,Member Type,Primary Initial Class,"
          + "Primary Initial Service Type,Paid Through,OEC,OFC Completion Date,Edit,Email,"
          + "Primary Cred Class,Primary Additional Class,Primary Additional Service Type,"
          + "Billing Category,Join Date,CPR Certificate Issue Date,CPR Certificate Provider,"
          + "ICS-100 Completion Date,Youth Protection Exp Date,EMT Certifications,"
          + "Temp Not Patrolling,Militaryleave,Phone,Full Address,Birth Date,Gender";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /** Take out anything the test imported, whether it passed or not. */
    @AfterEach
    void cleanUp() {
        withPatrolData((log, patrolData) -> {
            try {
                Connection connection = patrolData.getConnection();
                for (String id : new String[] {NEW_ID, SECOND_ID}) {
                    SqlWriter.executeUpdate(log, connection, "removeImportedTestMember",
                                            "DELETE FROM roster WHERE IDNumber = '" + id + "'");
                }
            }
            catch (Exception e) {
                throw new AssertionError("could not clean up imported test members", e);
            }
        });
    }

    private static String row(String id, String first, String last, String memberType,
                              String email, String phone) {
        return row(id, first, last, memberType, email, phone, "Alpine");
    }

    /** The same row with nsp.org's Primary Initial Class - the patrol class - spelled out. */
    private static String row(String id, String first, String last, String memberType,
                              String email, String phone, String nspClass) {
        return id + "," + first + "," + last + ",Paid," + memberType + "," + nspClass + ",V,12/31/2026,CU,,"
                 + "\"<a href=\"\"/edit?ID=" + id + "\"\">Edit</a>\","
                 + email + ",,,,,3/9/2023,,,8/25/2025,,,FALSE,FALSE," + phone + ","
                 + "\"1 Main Street, WI 53121UNITED STATES\",10/20/1975,";
    }

    private MockMultipartFile csv(String... rows) {
        StringBuilder body = new StringBuilder(HEADER);
        for (String row : rows) {
            body.append("\n").append(row);
        }
        return new MockMultipartFile("rosterFile", "ResultsGrid_ExportData.csv", "text/csv",
                                     body.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ----- who may use it -------------------------------------------------------------------

    @Test
    void aMemberIsRefused() throws Exception {
        assertEquals(403, mockMvc.perform(get("/ImportPatrollers")
                                              .param("resort", "Sample").param("ID", MEMBER_ID)
                                              .header("User-Agent", BROWSER)
                                              .session(sessionFor(MEMBER_ID)))
                                 .andReturn().getResponse().getStatus());
    }

    @Test
    void aVisitorIsSentToLogIn() throws Exception {
        mockMvc.perform(get("/ImportPatrollers").param("resort", "Sample")
                                                .header("User-Agent", BROWSER))
               .andExpect(status().is3xxRedirection());
    }

    /** The upload itself is gated too, not only the page that offers it. */
    @Test
    void aMemberCannotPostAFile() throws Exception {
        assertEquals(403, mockMvc.perform(multipart("/ImportPatrollers")
                                              .file(csv(row(NEW_ID, "New", "Patroller",
                                                            "Patroller", "n@example.com", "")))
                                              .param("resort", "Sample").param("ID", MEMBER_ID)
                                              .header("User-Agent", BROWSER)
                                              .session(sessionFor(MEMBER_ID)))
                                 .andReturn().getResponse().getStatus());
    }

    // ----- the preview ----------------------------------------------------------------------

    /** Reading the file changes nothing: that is the whole reason the step exists. */
    @Test
    void thePreviewWritesNothing() throws Exception {
        String page = preview(csv(row(NEW_ID, "New", "Patroller", "Patroller",
                                      "new@example.com", "(847) 372-5936")));

        assertTrue(page.contains("Nothing has been changed yet"), "the page should say so");
        assertTrue(page.contains(NEW_ID), "and list who would be added");
        assertNull(memberOnRoster(NEW_ID), "but must not have added them");
    }

    /** Every row is accounted for, with a reason for each one left out. */
    @Test
    void thePreviewExplainsEveryRowItSkips() throws Exception {
        String page = preview(csv(
            row(NEW_ID, "New", "Patroller", "Patroller", "a@example.com", ""),
            row(DIRECTOR_ID, "Already", "Here", "Patroller", "b@example.com", ""),
            row("990003", "An", "Alumnus", "Alumni", "c@example.com", ""),
            row("NOTANUMBER", "Bad", "Id", "Patroller", "d@example.com", "")));

        assertTrue(page.contains("already on your roster"), "the existing member's reason");
        assertTrue(page.contains("not Patroller"), "the alumnus's reason");
        assertTrue(page.contains("not a number"), "the bad id's reason");
    }

    // ----- the import ------------------------------------------------------------------------

    @Test
    void importingAddsTheNewPatrollerWithTheImportDefaults() throws Exception {
        confirm(csv(row(NEW_ID, "New", "Patroller", "Patroller",
                        "new@example.com", "(847) 372-5936")));

        Roster added = memberOnRoster(NEW_ID);
        assertNotNull(added, "the new patroller should be on the roster");
        assertEquals("New", added.getFirst());
        assertEquals("Patroller", added.getLast());
        assertEquals("new@example.com", added.getEmailAddress());
        assertEquals("847-372-5936", added.getCellPhone(), "stored in the roster's phone format");
        assertEquals("BAS", added.getClassification(), "Alpine in the file, BAS in the roster");
        assertEquals("2", added.getCommitment(), "Full Time");
        assertFalse(added.isDirector(), "an imported patroller is never a director");
    }

    /**
     * The class is carried across, not defaulted. Proved through the real controller and the real
     * roster because that is the whole of it: the file says "Senior Alpine", the row says "SR".
     */
    @Test
    void theClassInTheFileDecidesTheClassification() throws Exception {
        confirm(csv(row(NEW_ID, "Sen", "Alpine", "Patroller", "a@example.com", "",
                        "Senior Alpine"),
                    row(SECOND_ID, "Pa", "Trol", "Patroller", "b@example.com", "",
                        "Senior Patroller")));

        assertEquals("SR", memberOnRoster(NEW_ID).getClassification());
        assertEquals("SRA", memberOnRoster(SECOND_ID).getClassification());
    }

    /**
     * A class nsp.org names in words this site does not know. It becomes Other, which says what
     * happened rather than guessing - and the director is told, on the page and in the log.
     */
    @Test
    void aClassThisSiteHasNoNameForIsImportedAsOther() throws Exception {
        String preview = preview(csv(row(NEW_ID, "Nor", "Dic", "Patroller", "a@example.com", "",
                                         "Nordic")));

        assertTrue(preview.contains("this site does not have a name for"),
                   "the preview should warn before anything is written: " + preview);

        confirm(csv(row(NEW_ID, "Nor", "Dic", "Patroller", "a@example.com", "", "Nordic")));

        assertEquals("OTH", memberOnRoster(NEW_ID).getClassification());
    }

    /**
     * The rule the director is promised: somebody already here is not touched.
     *
     * <p>Checked by changing the file's version of them - a different name and email - and
     * confirming the roster still holds what it did.
     */
    @Test
    void anExistingPatrollerIsLeftExactlyAsTheyWere() throws Exception {
        Roster before = memberOnRoster(DIRECTOR_ID);
        assertNotNull(before, "the test director should be on Sample's roster");
        String nameBefore = before.getFullName();
        String emailBefore = before.getEmailAddress();

        confirm(csv(row(DIRECTOR_ID, "Overwritten", "Name", "Patroller",
                        "should-not-be-used@example.com", "(555) 555-5555")));

        Roster after = memberOnRoster(DIRECTOR_ID);
        assertEquals(nameBefore, after.getFullName(), "their name must not have changed");
        assertEquals(emailBefore, after.getEmailAddress(), "nor their email");
    }

    /** Running the same file twice adds nobody twice. */
    @Test
    void importingTheSameFileAgainAddsNobody() throws Exception {
        var file = csv(row(NEW_ID, "New", "Patroller", "Patroller", "new@example.com", ""));
        confirm(file);
        assertNotNull(memberOnRoster(NEW_ID));

        String page = confirm(csv(row(NEW_ID, "New", "Patroller", "Patroller",
                                      "new@example.com", "")));

        assertTrue(page.contains("0</span> patroller(s) added")
                     || page.contains("already on your roster"),
                   "the second run should add nobody: " + page);
    }

    @Test
    void twoNewPatrollersAreBothAdded() throws Exception {
        confirm(csv(row(NEW_ID, "First", "One", "Patroller", "1@example.com", ""),
                    row(SECOND_ID, "Second", "Two", "Patroller", "2@example.com", "")));

        assertNotNull(memberOnRoster(NEW_ID));
        assertNotNull(memberOnRoster(SECOND_ID));
    }

    /** A file that is not a roster export is refused rather than half-imported. */
    @Test
    void aFileThatIsNotARosterExportImportsNobody() throws Exception {
        MockMultipartFile nonsense =
            new MockMultipartFile("rosterFile", "notes.csv", "text/csv",
                                  "just some text\nwith no header".getBytes(StandardCharsets.UTF_8));

        String page = preview(nonsense);

        assertFalse(page.contains("Will be added"),
                    "there is nothing importable in it: " + page);
    }

    /**
     * An imported patroller can sign in, using their last name.
     *
     * <p>Worth pinning because the failure is silent: the insert hashes whatever is in the
     * password field into {@code newPassword}, so leaving it blank still produced a hash. That
     * reads as "this member has a password", which switches off the last-name sign-in meant for
     * members who have none - and the account could not be reached at all.
     */
    @Test
    void anImportedPatrollerCanSignInWithTheirLastName() throws Exception {
        confirm(csv(row(NEW_ID, "New", "Hauca", "Patroller", "new@example.com", "")));

        withPatrolData((log, patrolData) -> {
            SessionData sessionData = sessionData(log);
            assertTrue(patrolData.isValidLogin(null, "Sample", NEW_ID, "Hauca", sessionData),
                       "the last name should sign them in");
            assertTrue(patrolData.isValidLogin(null, "Sample", NEW_ID, "hauca", sessionData),
                       "and it is not case sensitive");
            assertFalse(patrolData.isValidLogin(null, "Sample", NEW_ID, "something else",
                                                sessionData),
                        "but nothing else should");
        });
    }

    // ----- helpers ---------------------------------------------------------------------------

    private String preview(MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/ImportPatrollers").file(file)
                                   .param("resort", "Sample").param("ID", DIRECTOR_ID)
                                   .header("User-Agent", BROWSER)
                                   .session(sessionFor(DIRECTOR_ID)))
                      .andExpect(status().isOk())
                      .andReturn().getResponse().getContentAsString();
    }

    /**
     * The real two-step route: read the file, then press Import.
     *
     * <p>They share one session, because that is where the preview leaves the file - the director
     * chooses it once, and the confirm page has no file input at all.
     */
    private String confirm(MockMultipartFile file) throws Exception {
        MockHttpSession session = sessionFor(DIRECTOR_ID);

        mockMvc.perform(multipart("/ImportPatrollers").file(file)
                            .param("resort", "Sample").param("ID", DIRECTOR_ID)
                            .header("User-Agent", BROWSER).session(session))
               .andExpect(status().isOk());

        return mockMvc.perform(post("/ImportPatrollers/confirm")
                                   .param("resort", "Sample").param("ID", DIRECTOR_ID)
                                   .header("User-Agent", BROWSER).session(session))
                      .andExpect(status().isOk())
                      .andReturn().getResponse().getContentAsString();
    }

    private SessionData sessionData(Logger log) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(sessionFor(DIRECTOR_ID));
        return new SessionData(ServletAdapter.toJavaxRequest(request), null, log);
    }

    private Roster memberOnRoster(String id) {
        Roster[] found = new Roster[1];
        withPatrolData((log, patrolData) -> found[0] = patrolData.getMemberByID(id));
        return found[0];
    }

    private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(sessionFor(DIRECTOR_ID));
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger log = new Logger(ImportPatrollersTest.class, javaxRequest, "GET", "Sample",
                                Logger.INFO);
        PatrolData patrolData =
            new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
        try {
            work.accept(log, patrolData);
        }
        finally {
            patrolData.close();
        }
    }

    private MockHttpSession sessionFor(String patrollerId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", patrollerId);
        session.setAttribute("loggedInResort", "Sample");
        return session;
    }
}
