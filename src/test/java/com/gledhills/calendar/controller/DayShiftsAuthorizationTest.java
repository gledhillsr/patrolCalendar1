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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Who may reach DayShifts.
 *
 * It used to take its identity from the "ID" query parameter, look that member up, and hand the
 * director view to whoever asked - so anyone who knew a director's member number could read a day's
 * shifts, and through the POST rewrite or delete them, without ever logging in. Every other page in
 * the app checked credentials; this one did not.
 *
 * Authority comes from the session now. The parameter is still accepted, for links and logging, but
 * confers nothing.
 *
 * Read-only: no request here is expected to get far enough to write.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class DayShiftsAuthorizationTest {

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

    /** The hole: a stranger who guesses a director's member number. */
    @Test
    void aStrangerQuotingADirectorsIdIsSentToLogin() throws Exception {
        MvcResult result = request(get("/DayShifts"), null, DIRECTOR_ID);

        assertNotEquals(200, result.getResponse().getStatus(),
                        "an anonymous request must not render the page");
        assertTrue(result.getResponse().getContentAsString().isEmpty(),
                   "and must not leak a day's shifts in the body");
    }

    /** The same through the POST, which is where shifts get rewritten and deleted. */
    @Test
    void aStrangerCannotPostEither() throws Exception {
        MvcResult result = request(post("/DayShifts"), null, DIRECTOR_ID);

        assertNotEquals(200, result.getResponse().getStatus(),
                        "an anonymous POST must not be honoured");
    }

    /** A logged-in member who is not a director is refused rather than shown a day to edit. */
    @Test
    void anOrdinaryMemberIsRefused() throws Exception {
        MvcResult result = request(get("/DayShifts"), ordinaryMemberId(), DIRECTOR_ID);

        assertEquals(403, result.getResponse().getStatus(),
                     "DayShifts is a director's page");
    }

    /** Quoting somebody else's id changes nothing - the session decides. */
    @Test
    void theIdParameterConfersNothing() throws Exception {
        MvcResult result = request(get("/DayShifts"), ordinaryMemberId(), DIRECTOR_ID);

        assertEquals(403, result.getResponse().getStatus(),
                     "a member must not borrow a director's id through the URL");
    }

    @Test
    void aDirectorStillGetsThePage() throws Exception {
        MvcResult result = request(get("/DayShifts"), DIRECTOR_ID, DIRECTOR_ID);

        assertEquals(200, result.getResponse().getStatus(), "a director should get in");
        assertTrue(result.getResponse().getContentAsString().contains("Change Today's Shift")
                       || result.getResponse().getContentAsString().contains("Shift"),
                   "and see the day");
    }

    /** A director whose session is fine but who left ID off the URL still gets in. */
    @Test
    void aDirectorWithNoIdParameterStillGetsThePage() throws Exception {
        MvcResult result = request(get("/DayShifts"), DIRECTOR_ID, null);

        assertEquals(200, result.getResponse().getStatus(),
                     "the session alone is enough - the parameter was never the credential");
    }

    private String ordinaryMemberId() {
        return "200401";
    }

    private MvcResult request(MockHttpServletRequestBuilder builder, String sessionId,
                              String idParameter) throws Exception {
        MockHttpSession session = new MockHttpSession();
        if (sessionId != null) {
            session.setAttribute("loggedInUserId", sessionId);
            session.setAttribute("loggedInResort", "Sample");
        }
        builder = builder
            .param("resort", "Sample")
            .param("dayOfWeek", "6")
            .param("date", "15")
            .param("month", "2")
            .param("year", "2025");
        if (idParameter != null) {
            builder = builder.param("ID", idParameter);
        }
        return mockMvc.perform(builder.header("User-Agent", BROWSER).session(session)).andReturn();
    }
}
