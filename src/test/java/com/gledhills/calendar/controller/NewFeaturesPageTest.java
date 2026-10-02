package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import com.gledhills.calendar.utils.SiteIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The New Features page, and the director-only button that reaches it.
 *
 * <p>The page is prose, so what is worth testing is not its wording but the two things that break
 * silently: who can see it, and whether its contents list still matches the sections below it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class NewFeaturesPageTest {

    private static final String DIRECTOR_ID = "123456";
    private static final String MEMBER_ID = "200401";
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
    void aDirectorSeesThePage() throws Exception {
        String page = pageAs(DIRECTOR_ID);

        assertTrue(page.contains("What&rsquo;s new") || page.contains("What’s new"),
                   "the page should render its heading");
        assertTrue(page.contains("Contents"), "and its contents list");
    }

    /** An ordinary member is refused, not merely un-linked. */
    @Test
    void aMemberIsRefused() throws Exception {
        assertEquals(403, mockMvc.perform(get("/NewFeatures")
                                              .param("resort", "Sample").param("ID", MEMBER_ID)
                                              .header("User-Agent", BROWSER)
                                              .session(sessionFor(MEMBER_ID)))
                                 .andReturn().getResponse().getStatus());
    }

    /** Not logged in at all: sent to the login page rather than shown anything. */
    @Test
    void aVisitorIsSentToLogIn() throws Exception {
        mockMvc.perform(get("/NewFeatures").param("resort", "Sample")
                                           .header("User-Agent", BROWSER))
               .andExpect(status().is3xxRedirection());
    }

    @Test
    void anUnknownResortIsRejected() throws Exception {
        mockMvc.perform(get("/NewFeatures").param("resort", "DevilsHead").param("ID", DIRECTOR_ID)
                                           .header("User-Agent", BROWSER)
                                           .session(sessionFor(DIRECTOR_ID)))
               .andExpect(status().isBadRequest());
    }

    /**
     * The header button is drawn for a director and for nobody else - it is the only way anyone
     * finds this page.
     */
    @Test
    void theHeaderButtonIsDirectorOnly() throws Exception {
        String asDirector = mockMvc.perform(get("/MonthCalendar")
                                                .param("resort", "Sample").param("ID", DIRECTOR_ID)
                                                .header("User-Agent", BROWSER)
                                                .session(sessionFor(DIRECTOR_ID)))
                                   .andReturn().getResponse().getContentAsString();
        String asMember = mockMvc.perform(get("/MonthCalendar")
                                              .param("resort", "Sample").param("ID", MEMBER_ID)
                                              .header("User-Agent", BROWSER)
                                              .session(sessionFor(MEMBER_ID)))
                                 .andReturn().getResponse().getContentAsString();

        assertTrue(asDirector.contains("New Features"), "a director should be offered the button");
        assertFalse(asMember.contains("New Features"),
                    "an ordinary member should not see it at all");
    }

    /** It sits after Logout, which is where a director will look for it. */
    @Test
    void theButtonFollowsLogout() throws Exception {
        String page = mockMvc.perform(get("/MonthCalendar")
                                          .param("resort", "Sample").param("ID", DIRECTOR_ID)
                                          .header("User-Agent", BROWSER)
                                          .session(sessionFor(DIRECTOR_ID)))
                             .andReturn().getResponse().getContentAsString();

        assertTrue(page.indexOf("MemberLogout") < page.indexOf("New Features"),
                   "the New Features button should come after Logout in the members bar");
    }

    /**
     * Every entry in the contents list points at a section that exists, and every section is
     * listed. A page of prose rots quietly: an added section nobody can reach from the top, or a
     * link that goes nowhere, is exactly the kind of thing no other test would notice.
     */
    @Test
    void theContentsListAndTheSectionsAgree() throws Exception {
        String page = pageAs(DIRECTOR_ID);

        List<String> linked = matches(page, "href=\"#([a-z-]+)\"");
        linked.remove("top");                    //the back-to-contents links
        List<String> sections = matches(page, "<h3 id=\"([a-z-]+)\"");

        List<String> missingSection = new ArrayList<>(linked);
        missingSection.removeAll(sections);
        assertEquals(List.of(), missingSection,
                     "these contents entries point at a section that does not exist");

        List<String> notListed = new ArrayList<>(sections);
        notListed.removeAll(linked);
        assertEquals(List.of(), notListed,
                     "these sections are not in the contents list at the top");
    }

    /** Each feature says where to find it, which is the thing a director actually came for. */
    @Test
    void everyFeatureSaysWhereToFindIt() throws Exception {
        String page = pageAs(DIRECTOR_ID);

        int sections = matches(page, "<h3 id=\"([a-z-]+)\"").size();
        int whereBlocks = page.split("class=\"where\"", -1).length - 1;

        //the two Upcoming entries have nowhere to send anybody yet; everything shipped does
        assertEquals(sections - 2, whereBlocks,
                     "every shipped feature should carry a Where: line saying how to reach it");
    }

    /** The three groupings, and every feature sitting under exactly one of them. */
    @Test
    void thePageIsGroupedIntoThreeCategories() throws Exception {
        String page = pageAs(DIRECTOR_ID);

        for (String heading : List.of("Available to everyone", "Available to directors",
                                      "Upcoming features")) {
            assertTrue(page.contains(heading), "missing category: " + heading);
        }

        //the contents list names each group, and the body repeats them as headings
        for (String heading : List.of("Available to everyone", "Available to directors",
                                      "Upcoming features")) {
            assertEquals(2, page.split(Pattern.quote(heading), -1).length - 1,
                         heading + " should appear once in the contents and once as a heading");
        }
    }

    /** The invitation to suggest something, which is the only outbound ask on the page. */
    @Test
    void theWhatIsMissingSectionInvitesEmail() throws Exception {
        String page = pageAs(DIRECTOR_ID);

        assertTrue(page.contains("What is missing"), "the section should be there");
        assertTrue(page.contains("mailto:"), "and offer a way to write in");
        assertTrue(page.contains("subject=Patrol calendar"),
                   "with a subject line already filled in");
    }

    private String pageAs(String patrollerId) throws Exception {
        return mockMvc.perform(get("/NewFeatures")
                                   .param("resort", "Sample").param("ID", patrollerId)
                                   .header("User-Agent", BROWSER)
                                   .session(sessionFor(patrollerId)))
                      .andExpect(status().isOk())
                      .andReturn().getResponse().getContentAsString();
    }

    private static List<String> matches(String text, String pattern) {
        List<String> found = new ArrayList<>();
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        while (matcher.find()) {
            if (!found.contains(matcher.group(1))) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    private MockHttpSession sessionFor(String patrollerId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", patrollerId);
        session.setAttribute("loggedInResort", "Sample");
        return session;
    }

  /**
   * The Sample resort entry links straight into the demo, on the second domain and in a new tab.
   *
   * <p>The host is asserted through SiteIdentity rather than as a literal, for the same reason the
   * template builds it that way: SiteIdentityTest fails anybody who writes the domain out by hand,
   * because that is how a rename leaves half a site pointing at the old name.
   */
  @Test
  void theSampleResortEntryLinksStraightIntoTheDemo() throws Exception {
        String page = pageAs(DIRECTOR_ID);

    String expected = "https://" + SiteIdentity.PATROL_CALENDAR_DOMAIN
                        + "/calendar-1/MemberLogin?resort=Sample&amp;NSPgoto=MonthCalendar";
    assertTrue(page.contains(expected), "the link should go to the Sample login: " + expected);
    assertTrue(page.contains("Click this link"), "and say so in the Where line");

    int at = page.indexOf(expected);
    String tag = page.substring(page.lastIndexOf("<a", at), page.indexOf(">", at) + 1);
    assertTrue(tag.contains("target=\"_blank\""),
               "it opens in a new window, so a reader does not lose this page: " + tag);
  }
}
