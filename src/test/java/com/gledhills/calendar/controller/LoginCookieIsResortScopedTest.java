package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.AuthCookie;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.SessionData;
import jakarta.servlet.http.Cookie;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The nspToken login cookie authenticates on the resort it was issued for, and nowhere else.
 *
 * It used to be checked against whichever resort's database the current page belonged to, so a
 * cookie for Sample was looked up in Brighton's roster - logging "called getMemberByID with invalid
 * memberID=[123456]" on every request and throwing the cookie away. Two things had to be true for
 * the fix: the cookie must not log anybody in on another patrol's site, and it must not be DELETED
 * there either - somebody looking at a second resort should still be logged in when they go back.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class LoginCookieIsResortScopedTest {

    private static final String SAMPLE_DIRECTOR = "123456";
    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /** The cookie still does its job where it belongs - this is remember-me working. */
    @Test
    void aCookieLogsYouInOnItsOwnResort() throws Exception {
        MvcResult result = requestWithCookie("Sample", SAMPLE_DIRECTOR, "Sample");

        assertEquals(200, result.getResponse().getStatus(),
                     "a Sample cookie should log a Sample member in on Sample: "
                       + result.getResponse().getRedirectedUrl());
    }

    /** ...and does nothing at all on somebody else's. */
    @Test
    void aCookieDoesNotLogYouInOnAnotherResort() throws Exception {
        MvcResult result = requestWithCookie("Sample", SAMPLE_DIRECTOR, "Brighton");

        assertEquals(302, result.getResponse().getStatus(),
                     "a Sample cookie must not authenticate on Brighton");
        String redirect = result.getResponse().getRedirectedUrl();
        assertNotNull(redirect, "it should send them to log in");
        assertTrue(redirect.contains("MemberLogin") && redirect.contains("resort=Brighton"),
                   "and to Brighton's login, got: " + redirect);
    }

    /**
     * The part that would have been easy to get wrong.
     *
     * The rejected-cookie branch deletes the cookie, which is right for one that is forged or
     * expired. A cookie for another resort is neither - deleting it would silently log the member
     * out of their own patrol just because they glanced at somebody else's page.
     */
    @Test
    void aCookieForAnotherResortIsNotDeleted() throws Exception {
        MvcResult result = requestWithCookie("Sample", SAMPLE_DIRECTOR, "Brighton");

        List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
        for (String header : setCookies) {
            assertFalse(header.startsWith("nspToken=;") || header.contains("Max-Age=0"),
                        "the member's own login cookie was deleted: " + header);
        }
    }

    /** A page on the cookie's own resort still refreshes it, so the 14 days keeps sliding. */
    @Test
    void aCookieOnItsOwnResortIsRefreshed() throws Exception {
        MvcResult result = requestWithCookie("Sample", SAMPLE_DIRECTOR, "Sample");

        boolean refreshed = result.getResponse().getHeaders("Set-Cookie").stream()
            .anyMatch(header -> header.startsWith("nspToken=") && !header.contains("Max-Age=0"));
        assertTrue(refreshed, "expected the cookie TTL to be extended: "
                     + result.getResponse().getHeaders("Set-Cookie"));
    }

    /** GET a credential-gated page carrying a cookie minted for cookieResort. */
    private MvcResult requestWithCookie(String cookieResort, String memberId, String pageResort)
            throws Exception {
        String value = mintCookie(cookieResort, memberId);
        assertNotNull(value, "no cookie signing key configured - cannot run this test");

        return mockMvc.perform(get("/ListAssignments")
                                   .param("resort", pageResort)
                                   .param("ID", memberId)
                                   .cookie(new Cookie("nspToken", value))
                                   .header("User-Agent", BROWSER))
            .andReturn();
    }

    /** A genuinely signed cookie, made the same way the application makes them. */
    private String mintCookie(String resort, String memberId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger log = new Logger(LoginCookieIsResortScopedTest.class, javaxRequest, "GET", resort,
                                Logger.ERROR);
        return AuthCookie.create(new SessionData(javaxRequest, null, log), log, resort, memberId,
                                 3600);
    }
}
