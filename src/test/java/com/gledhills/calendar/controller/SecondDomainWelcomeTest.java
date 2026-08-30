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
 * The greeting in the header for visitors who arrived on the site's second domain.
 *
 * Two domains point at this one application. Someone who typed patrolcalendar.com is told they are
 * in the right place; someone on the primary domain sees nothing, because there is nothing to
 * reassure them about. Both halves are pinned here - the message appearing everywhere is as wrong
 * as it appearing nowhere, and neither shows up in a test that only ever asks for one host.
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
    void thePrimaryDomainIsNotGreeted() throws Exception {
        assertFalse(headerFor("http://" + SiteIdentity.DOMAIN + "/MemberLogin?resort=Sample").contains(GREETING),
                    "the primary domain has nothing to reassure a visitor about");
    }

    /**
     * A name that merely ends in the same letters is a different site. Without this, a host like
     * notpatrolcalendar.com would be greeted as if it were ours.
     */
    @Test
    void aLookalikeHostIsNotTheSameSite() throws Exception {
        assertFalse(headerFor("http://notpatrolcalendar.com/MemberLogin?resort=Sample").contains(GREETING),
                    "only the domain itself and its subdomains are this site");
    }

    private String headerFor(String url) throws Exception {
        return mockMvc.perform(get(URI.create(url))
                                   .header("User-Agent", BROWSER))   //no User-Agent is a bot: 403
                      .andExpect(status().isOk())
                      .andReturn().getResponse().getContentAsString();
    }
}
