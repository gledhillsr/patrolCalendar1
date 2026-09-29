package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.SiteIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The line in the header that says which of the site's two domains you arrived on.
 *
 * Two domains point at this one application. Someone who typed patrolcalendar.com is told they are
 * in the right place; someone still on gledhills.com is asked to move to the new name. Every case
 * is pinned here - a message appearing on the wrong domain is as bad as it appearing on none, and
 * neither shows up in a test that only ever asks for one host.
 *
 * A host that is neither - localhost, anything else - gets NOTHING, which is the case that keeps
 * this out of every other render test and out of the goldens. It is also why the nudge is its own
 * question rather than the negation of the greeting.
 *
 * MockMvc requests default to server name "localhost", which is why every other render test - and
 * MonthCalendarGoldenTest in particular - is unaffected by this feature. The host is set here by
 * asking for a full URI rather than a path.
 *
 * MemberLogin is the target because it is public: it draws the same header as every other page
 * without needing a logged-in member.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class SecondDomainWelcomeTest {

    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    private static final String GREETING = "Welcome to " + SiteIdentity.PATROL_CALENDAR_DISPLAY;
    private static final String NUDGE =
        "Please update URL to use " + SiteIdentity.PATROL_CALENDAR_DISPLAY + " (same site)";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void theSecondDomainIsGreeted() throws Exception {
        assertTrue(headerFor("http://patrolcalendar.com/MemberLogin?resort=Sample").contains(GREETING),
                   "a visitor on " + SiteIdentity.PATROL_CALENDAR_DOMAIN + " should be welcomed");
    }

    @Test
    void theWwwFormIsTheSameSite() throws Exception {
        assertTrue(headerFor("http://www.patrolcalendar.com/MemberLogin?resort=Sample").contains(GREETING),
                   "www is the same domain and should be welcomed the same way");
    }

    @Test
    void thePrimaryDomainIsAskedToMove() throws Exception {
        String header = headerFor("http://" + SiteIdentity.DOMAIN + "/MemberLogin?resort=Sample");

        assertTrue(header.contains(NUDGE),
                   "a visitor still on " + SiteIdentity.DOMAIN + " should be pointed at the new name");
        assertFalse(header.contains(GREETING),
                    "and must not also be welcomed to a domain they are not on");
    }

    @Test
    void theWwwFormOfThePrimaryDomainIsAskedToMoveToo() throws Exception {
        assertTrue(headerFor("http://www." + SiteIdentity.DOMAIN + "/MemberLogin?resort=Sample")
                       .contains(NUDGE),
                   "www is the same site and its visitors are on the old name just the same");
    }

    /** The two lines are alternatives, never both, and the new domain is never asked to move. */
    @Test
    void theSecondDomainIsNotAskedToMove() throws Exception {
        assertFalse(headerFor("http://patrolcalendar.com/MemberLogin?resort=Sample").contains(NUDGE),
                    "somebody already on the new name has nothing to change");
    }

    /**
     * Neither line on a host that is neither domain. This is what keeps the header out of every
     * other render test and out of MonthCalendarGoldenTest, all of which run on "localhost".
     */
    @Test
    void anotherHostGetsNeitherLine() throws Exception {
        String header = headerFor("http://localhost/MemberLogin?resort=Sample");

        assertFalse(header.contains(GREETING), "localhost is not the second domain");
        assertFalse(header.contains(NUDGE), "and telling a developer to change their URL is noise");
    }

    /**
     * A name that merely ends in the same letters is a different site. Without this, a host like
     * notpatrolcalendar.com would be greeted as if it were ours.
     */
    @Test
    void aLookalikeHostIsNotTheSameSite() throws Exception {
        assertFalse(headerFor("http://notpatrolcalendar.com/MemberLogin?resort=Sample").contains(GREETING),
                    "only the domain itself and its subdomains are this site");
        assertFalse(headerFor("http://notgledhills.com/MemberLogin?resort=Sample").contains(NUDGE),
                    "and the same for the primary domain - endsWith would have matched this");
    }

    private String headerFor(String url) throws Exception {
        return mockMvc.perform(get(URI.create(url))
                                   .header("User-Agent", BROWSER))   //no User-Agent is a bot: 403
                      .andExpect(status().isOk())
                      .andReturn().getResponse().getContentAsString();
    }
}
