package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.service.SiteDirectorsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * "Email all directors, every resort" - the site owner's broadcast, reached only from
 * SiteWideConfiguration.
 *
 * Nothing here posts a valid message. A successful POST hands the batch to the real EmailService,
 * which would mail every director at every resort for real; the only POSTs below are ones that
 * stop before that - a refusal, and a form that fails validation.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class SiteDirectorsEmailTest {

    private static final String OWNER_RESORT = "Brighton";
    private static final String OWNER_ID = "192443";
    private static final String DIRECTOR_ID = "123456";
    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private SiteDirectorsService siteDirectorsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /** The button that gets you there, and only the owner sees the page holding it. */
    @Test
    void theButtonIsOnSiteWideConfiguration() throws Exception {
        String page = request(get("/SiteWideConfiguration"), OWNER_RESORT, OWNER_ID)
            .getResponse().getContentAsString();

        assertTrue(page.contains("Email all directors, every resort"),
                   "the owner should see the button: " + page);
        assertTrue(page.contains("value=\"siteDirectors\""),
                   "and it should ask for the site-wide audience");
    }

    @Test
    void theOwnerGetsTheDirectorList() throws Exception {
        MvcResult result = siteDirectorRequest(get("/EmailForm"), OWNER_RESORT, OWNER_ID);
        String page = result.getResponse().getContentAsString();

        assertEquals(200, result.getResponse().getStatus(), "the owner should get in");
        assertTrue(page.contains("Every <b>director</b> at every resort"),
                   "the page should say whose addresses these are: " + page);
    }

    /**
     * Recipients carry their resort, in the option value as well as the label.
     *
     * This is the whole point of the feature: ids repeat across resorts, so "192443" alone would
     * be resolved against Brighton and could mail a different person entirely.
     */
    @Test
    void everyRecipientIsQualifiedByResort() throws Exception {
        String page = siteDirectorRequest(get("/EmailForm"), OWNER_RESORT, OWNER_ID)
            .getResponse().getContentAsString();

        assertTrue(page.contains("value=\"Brighton:"),
                   "option values should be Resort:id, not a bare id: " + page);
        assertTrue(page.contains("(Brighton)"), "and the label should name the resort");
    }

    /**
     * The audience is submitted exactly once.
     *
     * It rides in the form action's query string, because that is the copy that survives a
     * multipart body Tomcat refused to parse. A second copy as a hidden field is what broke this
     * in production: Spring joins duplicate parameter values with a comma, the controller saw
     * "siteDirectors,siteDirectors", and the send was quietly rebuilt as Brighton's own roster.
     */
    @Test
    void theAudienceIsPostedOnlyOnce() throws Exception {
        String page = siteDirectorRequest(get("/EmailForm"), OWNER_RESORT, OWNER_ID)
            .getResponse().getContentAsString();

        assertTrue(page.contains("audience=siteDirectors"),
                   "the form action should carry the audience: " + page);
        assertFalse(page.contains("name=\"audience\""),
                    "a second copy as a form field makes the value arrive doubled: " + page);
    }

    /** The demo resort's made-up people are never mailed. */
    @Test
    void sampleIsExcluded() throws Exception {
        String page = siteDirectorRequest(get("/EmailForm"), OWNER_RESORT, OWNER_ID)
            .getResponse().getContentAsString();

        assertFalse(page.contains("value=\"Sample:"), "no Sample recipient should be listed");
        assertFalse(page.contains("(Sample)"), "nor labelled");

        for (EmailRecipient director : siteDirectorsService.allDirectors(sessionData(), logger())) {
            assertFalse(SiteDirectorsService.DEMO_RESORT.equalsIgnoreCase(director.getResort()),
                        "the service itself should skip Sample, not just the page");
        }
    }

    /** Everyone with a usable address starts selected, so the owner deselects rather than selects. */
    @Test
    void everybodyStartsSelected() throws Exception {
        String page = siteDirectorRequest(get("/EmailForm"), OWNER_RESORT, OWNER_ID)
            .getResponse().getContentAsString();

        int options = countOccurrences(page, "value=\"Brighton:");
        int selected = countOccurrences(page, "selected=\"selected\"");
        assertTrue(options > 0, "there should be some directors to mail");
        assertEquals(options, selected, "every one of them should start selected: " + page);
    }

    /** Nobody else may see 47 other resorts' director addresses, or mail them. */
    @Test
    void anotherResortsDirectorIsRefused() throws Exception {
        assertEquals(403, siteDirectorRequest(get("/EmailForm"), "Sample", DIRECTOR_ID)
                              .getResponse().getStatus(),
                     "a Sample director should not see the list");
        assertEquals(403, siteDirectorRequest(post("/EmailForm"), "Sample", DIRECTOR_ID)
                              .getResponse().getStatus(),
                     "nor send to it");
    }

    /** Right id, wrong resort - the gate is both together, as on SiteWideConfiguration. */
    @Test
    void theOwnerIdOnAnotherResortIsRefused() throws Exception {
        assertEquals(403, siteDirectorRequest(get("/EmailForm"), "Sample", OWNER_ID)
                              .getResponse().getStatus(),
                     "the id alone is not enough");
    }

    /**
     * A rejected form comes back as the director list, not as Brighton's roster.
     *
     * The filters behind an ordinary recipient list are not posted back, so a redisplay rebuilds
     * it as "everybody" - which for this audience would silently swap in the wrong people while
     * the owner was fixing a typo in the subject.
     */
    @Test
    void aRejectedFormRedisplaysTheDirectorList() throws Exception {
        MockHttpSession session = session(OWNER_RESORT, OWNER_ID);
        String page = mockMvc.perform(post("/EmailForm")
                                          .param("resort", OWNER_RESORT)
                                          .param("fromPatrollerId", OWNER_ID)
                                          .param("audience", "siteDirectors")
                                          .param("subject", "")          //fails validation
                                          .param("message", "")
                                          .param("patrollerIds", "Brighton:" + OWNER_ID)
                                          .header("User-Agent", BROWSER)
                                          .session(session))
            .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("Every <b>director</b> at every resort"),
                   "it should still be the site-wide list: " + page);
        assertTrue(page.contains("value=\"Brighton:"), "with resort-qualified recipients");
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private MvcResult siteDirectorRequest(MockHttpServletRequestBuilder builder, String resort,
                                          String id) throws Exception {
        return request(builder.param("audience", "siteDirectors"), resort, id);
    }

    private MvcResult request(MockHttpServletRequestBuilder builder, String resort, String id)
            throws Exception {
        return mockMvc.perform(builder
                                   .param("resort", resort)
                                   .param("ID", id)
                                   .header("User-Agent", BROWSER)
                                   .session(session(resort, id)))
            .andReturn();
    }

    private MockHttpSession session(String resort, String id) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", id);
        session.setAttribute("loggedInResort", resort);
        return session;
    }

    private com.gledhills.calendar.utils.SessionData sessionData() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session(OWNER_RESORT, OWNER_ID));
        return new com.gledhills.calendar.utils.SessionData(
            ServletAdapter.toJavaxRequest(request), null, logger());
    }

    private com.gledhills.calendar.utils.Logger logger() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session(OWNER_RESORT, OWNER_ID));
        return new com.gledhills.calendar.utils.Logger(
            SiteDirectorsEmailTest.class, ServletAdapter.toJavaxRequest(request), "GET",
            OWNER_RESORT, com.gledhills.calendar.utils.Logger.ERROR);
    }
}
