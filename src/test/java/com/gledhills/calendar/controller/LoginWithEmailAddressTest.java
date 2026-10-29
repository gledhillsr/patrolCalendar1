package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.LoginIdentifier;
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
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Connection;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Signing in with an email address instead of a member number.
 *
 * <p>A fifth of every failed sign-in this site has logged - 48 attempts from 24 distinct people -
 * is somebody typing their email address into the Member ID box. Three of them went on to request
 * password reset after password reset, each one succeeding, because a correct reset cannot fix an
 * identifier that was never the problem. One is still locked out.
 *
 * <p>Two things are pinned here, and the second matters more than the first: that an address
 * naming exactly one member signs them in, and that when it does, it is the member <em>number</em>
 * that reaches the session and the next page - not the address. Everything downstream of login is
 * keyed on the number, so getting that wrong would sign somebody in to a broken site rather than
 * refusing them honestly.
 *
 * <p>Writes to Sample's roster and puts back what it changed.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class LoginWithEmailAddressTest {

    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    /** Ids no Sample member has. Created per test, removed afterwards. */
    private static final String ONE_ID = "990101";
    private static final String TWIN_A_ID = "990102";
    private static final String TWIN_B_ID = "990103";

    private static final String ONE_EMAIL = "solo.patroller@example.com";
    private static final String SHARED_EMAIL = "two.of.us@example.com";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        withPatrolData((log, patrolData) -> {
            try {
                Connection connection = patrolData.getConnection();
                for (String id : new String[] {ONE_ID, TWIN_A_ID, TWIN_B_ID}) {
                    SqlWriter.executeUpdate(log, connection, "removeLoginEmailTestMember",
                                            "DELETE FROM roster WHERE IDNumber = '" + id + "'");
                }
            }
            catch (Exception e) {
                throw new AssertionError("could not clean up test members", e);
            }
        });
    }

    // ---------------------------------------------------------------- resolution

    /** The ordinary case, and the one that must not change: a member number is left alone. */
    @Test
    void aMemberNumberIsNotTouched() {
        withPatrolData((log, patrolData) -> {
            LoginIdentifier identifier =
                patrolData.resolveLoginIdentifier("123456", sessionData(log));

            assertEquals(LoginIdentifier.Resolution.NOT_AN_EMAIL, identifier.resolution());
            assertEquals("123456", identifier.memberId(),
                         "a number must reach the credential check exactly as typed");
        });
    }

    /** A last name is not an email either - that sign-in path still works for members with no password. */
    @Test
    void aLastNameIsNotTouched() {
        withPatrolData((log, patrolData) -> {
            assertEquals(LoginIdentifier.Resolution.NOT_AN_EMAIL,
                         patrolData.resolveLoginIdentifier("Hauca", sessionData(log)).resolution());
        });
    }

    @Test
    void anAddressNamingOneMemberResolvesToTheirNumber() {
        createMember(ONE_ID, "Solo", "Patroller", ONE_EMAIL);

        withPatrolData((log, patrolData) -> {
            LoginIdentifier identifier =
                patrolData.resolveLoginIdentifier(ONE_EMAIL, sessionData(log));

            assertEquals(LoginIdentifier.Resolution.RESOLVED, identifier.resolution());
            assertEquals(ONE_ID, identifier.memberId());
        });
    }

    /** Rosters hold addresses with stray case and trailing spaces; a member should not have to reproduce either. */
    @Test
    void theAddressIsMatchedWithoutRegardToCaseOrSpace() {
        createMember(ONE_ID, "Solo", "Patroller", ONE_EMAIL);

        withPatrolData((log, patrolData) -> {
            LoginIdentifier identifier =
                patrolData.resolveLoginIdentifier("  Solo.Patroller@Example.COM  ", sessionData(log));

            assertEquals(LoginIdentifier.Resolution.RESOLVED, identifier.resolution());
            assertEquals(ONE_ID, identifier.memberId());
        });
    }

    @Test
    void anAddressOnNoRosterRowIsUnknown() {
        withPatrolData((log, patrolData) -> {
            LoginIdentifier identifier =
                patrolData.resolveLoginIdentifier("nobody.here@example.com", sessionData(log));

            assertEquals(LoginIdentifier.Resolution.UNKNOWN_EMAIL, identifier.resolution());
            assertEquals("nobody.here@example.com", identifier.memberId(),
                         "an unresolvable address must still reach the credential check as typed, "
                           + "so the back door account is unaffected");
        });
    }

    /**
     * The case that decides the whole design. 39 members across 14 resorts share an address with
     * somebody - couples, almost always - and signing either of them in would hand a person their
     * spouse's session whenever the two also share a password.
     */
    @Test
    void anAddressSharedByTwoMembersIsRefusedRatherThanGuessed() {
        createMember(TWIN_A_ID, "First", "Twin", SHARED_EMAIL);
        createMember(TWIN_B_ID, "Second", "Twin", SHARED_EMAIL);

        withPatrolData((log, patrolData) -> {
            LoginIdentifier identifier =
                patrolData.resolveLoginIdentifier(SHARED_EMAIL, sessionData(log));

            assertEquals(LoginIdentifier.Resolution.AMBIGUOUS_EMAIL, identifier.resolution(),
                         "neither member may be picked");
            assertFalse(TWIN_A_ID.equals(identifier.memberId())
                          || TWIN_B_ID.equals(identifier.memberId()),
                        "and neither one's number may leak out as the answer");
        });
    }

    // ---------------------------------------------------------------- the whole route

    /**
     * The regression that would really hurt: signing in by address must put the member
     * <em>number</em> in the session and on the next page's URL. Everything after login is keyed
     * on the number.
     */
    @Test
    void signingInByAddressCarriesTheNumberOnwardsNotTheAddress() throws Exception {
        createMember(ONE_ID, "Solo", "Patroller", ONE_EMAIL);

        // a member with no password set signs in with their last name
        MvcResult result = mockMvc.perform(post("/LoginHelp")
                                             .param("resort", "Sample")
                                             .param("ID", ONE_EMAIL)
                                             .param("Password", "Patroller")
                                             .param("submit1", "Login")
                                             .header("User-Agent", BROWSER))
                                  .andReturn();

        String redirect = result.getResponse().getRedirectedUrl();
        assertNotNull(redirect, "the address should have signed them in: "
          + result.getResponse().getContentAsString());
        assertTrue(redirect.contains("ID=" + ONE_ID),
                   "the member number belongs on the next page, got: " + redirect);
        assertFalse(redirect.contains("@"),
                    "and the address does not, got: " + redirect);

        assertEquals(ONE_ID, result.getRequest().getSession().getAttribute("loggedInUserId"),
                     "the session is keyed on the number");
    }

    /**
     * An address the site cannot place gets a message that names the problem.
     *
     * <p>"Invalid member ID or password" is what the three members in the logs were told, over and
     * over, while they kept resetting a password that was never wrong.
     */
    @Test
    void anUnknownAddressIsToldItIsAnUnknownAddress() throws Exception {
        String page = mockMvc.perform(post("/LoginHelp")
                                        .param("resort", "Sample")
                                        .param("ID", "nobody.here@example.com")
                                        .param("Password", "whatever")
                                        .param("submit1", "Login")
                                        .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("is not on the"),
                   "it should say the address is not on this roster: " + page);
        assertTrue(page.contains("patrol member number"),
                   "and point at what the box actually wants: " + page);
    }

    /** A shared address says so, rather than leaving the member to guess why their password "failed". */
    @Test
    void aSharedAddressSaysWhyItCannotBeUsed() throws Exception {
        createMember(TWIN_A_ID, "First", "Twin", SHARED_EMAIL);
        createMember(TWIN_B_ID, "Second", "Twin", SHARED_EMAIL);

        String page = mockMvc.perform(post("/LoginHelp")
                                        .param("resort", "Sample")
                                        .param("ID", SHARED_EMAIL)
                                        .param("Password", "Twin")
                                        .param("submit1", "Login")
                                        .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("more than one member"),
                   "it should say the address is shared: " + page);
        assertTrue(page.contains("patrol member number"),
                   "and point at what to use instead: " + page);
    }

    /**
     * A wrong password is still a wrong password. The helpful wording is for an identifier the
     * site cannot place, and must not start appearing when the address was perfectly good.
     */
    @Test
    void aGoodAddressWithABadPasswordGetsThePlainMessage() throws Exception {
        createMember(ONE_ID, "Solo", "Patroller", ONE_EMAIL);

        String page = mockMvc.perform(post("/LoginHelp")
                                        .param("resort", "Sample")
                                        .param("ID", ONE_EMAIL)
                                        .param("Password", "not the last name")
                                        .param("submit1", "Login")
                                        .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("Invalid member ID or password"),
                   "nothing is wrong with the address, only the password: " + page);
        assertFalse(page.contains("is not on the"),
                    "and it must not claim the address is unknown: " + page);
    }

    /** The box has to say it takes an address, or the members who need this never try it. */
    @Test
    void theLoginFormSaysTheBoxTakesAnAddressToo() throws Exception {
        String page = mockMvc.perform(
                               org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                 .get("/MemberLogin")
                                 .param("resort", "Sample")
                                 .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("or email address"),
                   "the Member ID field should offer it: " + page);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Built the way the nsp.org import builds one, so these members are shaped like real rows
     * rather than like whatever columns this test remembered to fill in. That also gives them a
     * password - their last name - which is what lets the tests below perform a real sign-in.
     */
    private void createMember(String id, String first, String last, String email) {
        withPatrolData((log, patrolData) -> {
            try {
                Roster member = new Roster(log);
                member.memberData[Roster.ID_NUM] = id;
                member.setFirst(first);
                member.setLast(last);
                member.setEmail(email);
                member.setClassification("BAS");
                member.setCommitment("2");
                member.setDirector("");
                member.setInstructor("0");
                member.setPassword(last);

                SessionData sessionData = sessionData(log);
                SqlWriter.executeUpdate(log, patrolData.getConnection(),
                                        "createLoginEmailTestMember",
                                        member.getInsertSQLString("Sample", sessionData));
            }
            catch (Exception e) {
                throw new AssertionError("could not create test member " + id, e);
            }
        });
    }

    private SessionData sessionData(Logger log) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        return new SessionData(ServletAdapter.toJavaxRequest(request), null, log);
    }

    private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger log = new Logger(LoginWithEmailAddressTest.class, javaxRequest, "GET", "Sample",
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
}
