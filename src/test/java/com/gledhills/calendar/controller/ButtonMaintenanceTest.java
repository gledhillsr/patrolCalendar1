package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.MenuButton;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Menu Configuration": four slots of custom header button, stored per resort.
 *
 * These end up as links in every member's header, so two things matter beyond the plumbing - only
 * a director may set them, and a url this app would refuse to render is refused at the point it is
 * typed rather than sanitized later.
 *
 * Writes to the menubuttons table, and clears it afterwards.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ButtonMaintenanceTest {

    private static final String DIRECTOR_ID = "123456";
    private static final String MEMBER_ID = "200401";
    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private List<MenuButton> originalButtons;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        //snapshot rather than blank on the way out: a director may have configured real buttons
        //for Sample, and a test has no business deleting them
        originalButtons = readStoredButtons();
        blankAllSlots();   //assert against a known table, not whatever is configured today
    }

    @AfterEach
    void cleanUp() {
        withPatrolData((log, patrolData) ->
                           MenuButton.writeAll(log, patrolData.getConnection(), originalButtons));
    }

    @Test
    void theFormOffersFourEmptySlots() throws Exception {
        String page = body(request(get("/ButtonMaintenance"), DIRECTOR_ID));

        assertTrue(page.contains("Customize Menu Buttons"), "the page should be titled: " + page);
        for (int position = 1; position <= MenuButton.MAX_BUTTONS; position++) {
            assertTrue(page.contains("name=\"text_" + position + "\""), "slot " + position + " text");
            assertTrue(page.contains("name=\"url_" + position + "\""), "slot " + position + " url");
            assertTrue(page.contains("name=\"memberOnly_" + position + "\""), "slot " + position + " flag");
            assertTrue(page.contains("name=\"enabled_" + position + "\""), "slot " + position + " switch");
        }
        assertFalse(page.contains("name=\"text_5\""), "and no fifth slot");
    }

    @Test
    void aButtonSurvivesASaveAndReload() throws Exception {
        save(slot(1, "Weather", "http://ccweather.com/w/index.php", false, true));

        List<MenuButton> stored = readStored();
        MenuButton first = stored.get(0);
        assertEquals("Weather", first.getText(), "text should be stored");
        assertEquals("http://ccweather.com/w/index.php", first.getUrl(), "url should be stored");
        assertFalse(first.isMemberOnly(), "and its flags");
        assertTrue(first.isEnabled(), "including the switch");

        String page = body(request(get("/ButtonMaintenance"), DIRECTOR_ID));
        assertTrue(page.contains("value=\"Weather\""), "and come back on the form: " + page);
    }

    @Test
    void theMemberOnlyFlagRoundTrips() throws Exception {
        save(slot(2, "Members Handbook", "/handbook.pdf", true, false));

        MenuButton stored = readStored().get(1);
        assertTrue(stored.isMemberOnly(), "member only should be stored");
        //enabled is forced on for a slot filled in for the first time - see
        //aNewlyDefinedButtonIsEnabledWithoutTickingTheBox, and turning it off needs a second save
        assertTrue(stored.isEnabled(), "and a newly defined button arrives switched on");
    }

    /** Set up now, switch on later: it takes a second save, and then it stays off. */
    @Test
    void aButtonCanBeSetUpAndLeftOff() throws Exception {
        save(slot(2, "Members Handbook", "/handbook.pdf", true, false));
        save(slot(2, "Members Handbook", "/handbook.pdf", true, false));

        MenuButton stored = readStored().get(1);
        assertFalse(stored.isEnabled(), "the second save turns it off");
        assertFalse(stored.isDisplayable(), "so the header would not draw it");
        assertTrue(stored.isMemberOnly(), "and its other flag is untouched");
    }

    /** Filling in a blank slot turns it on - forgetting the second box should not hide the button. */
    @Test
    void aNewlyDefinedButtonIsEnabledWithoutTickingTheBox() throws Exception {
        save("text_1", "Trail Map", "url_1", "https://example.com/map");   //no enabled_1 posted

        assertTrue(readStored().get(0).isEnabled(), "a button defined for the first time is on");
        assertTrue(readStored().get(0).isDisplayable(), "so the header would draw it");
    }

    /** But it is only "the first time": turning an existing one off has to stick. */
    @Test
    void anExistingButtonCanBeTurnedOffAndStaysOff() throws Exception {
        save("text_1", "Trail Map", "url_1", "https://example.com/map");
        assertTrue(readStored().get(0).isEnabled(), "on to begin with");

        save("text_1", "Trail Map", "url_1", "https://example.com/map");   //still no enabled_1
        assertFalse(readStored().get(0).isEnabled(),
                    "the slot is no longer new, so the unticked box is respected");
    }

    /** The page ticks it live too, so the director sees it happen rather than discovering it. */
    @Test
    void theFormTicksEnabledWhileTyping() throws Exception {
        String page = body(request(get("/ButtonMaintenance"), DIRECTOR_ID));

        assertTrue(page.contains("function autoEnable"), "the page should carry the helper");
        assertTrue(page.contains("autoEnable(1)"), "wired to the first row's fields");
        assertTrue(page.contains("data-started-blank"), "and know which rows began empty");
    }

    /** The switch is the point: a complete button that is turned off is not displayable. */
    @Test
    void onlyEnabledAndCompleteButtonsAreDisplayable() {
        assertTrue(new MenuButton(1, "Weather", "/w", false, true).isDisplayable(), "complete and on");
        assertFalse(new MenuButton(1, "Weather", "/w", false, false).isDisplayable(), "turned off");
        assertFalse(new MenuButton(1, "", "/w", false, true).isDisplayable(), "no text");
        assertFalse(new MenuButton(1, "Weather", "", false, true).isDisplayable(), "no url");
    }

    /** These become hrefs, so a scripted url is refused rather than stored and sanitized later. */
    @Test
    void anUnsafeUrlIsRefusedAndNothingIsSaved() throws Exception {
        for (String url : new String[] {"javascript:alert(1)", "data:text/html,<script>",
                                        "//evil.example.com", "java&#115;cript:alert(1)"}) {
            String page = save(slot(1, "Bad", url, false, true));

            assertTrue(page.contains("not a usable link"), "[" + url + "] should be refused: " + page);
            assertTrue(readStored().get(0).isBlank(), "[" + url + "] must not be stored");
        }
    }

    /** maxlength on the input is a browser courtesy; the save has to hold the line itself. */
    @Test
    void buttonTextLongerThanTheLimitIsRefused() throws Exception {
        String tooLong = "X".repeat(MenuButton.MAX_TEXT_LENGTH + 1);

        String page = save("text_1", tooLong, "url_1", "https://example.com");

        assertTrue(page.contains("limited to " + MenuButton.MAX_TEXT_LENGTH + " characters"),
                   "it should say so: " + page);
        assertTrue(readStored().get(0).isBlank(), "and nothing should be stored");
    }

    @Test
    void buttonTextAtTheLimitIsAccepted() throws Exception {
        String exactly = "X".repeat(MenuButton.MAX_TEXT_LENGTH);

        save("text_1", exactly, "url_1", "https://example.com");

        assertEquals(exactly, readStored().get(0).getText(), "20 characters is fine");
    }

    @Test
    void theFormCapsTheTextFieldAtTheSameLength() throws Exception {
        String page = body(request(get("/ButtonMaintenance"), DIRECTOR_ID));

        assertTrue(page.contains("maxlength=\"" + MenuButton.MAX_TEXT_LENGTH + "\""),
                   "the input should carry the same cap: " + page);
    }

    @Test
    void aHalfFilledSlotIsRefused() throws Exception {
        String noUrl = save(slot(1, "Weather", "", false, true));
        assertTrue(noUrl.contains("needs a URL"), "text without a url should be refused: " + noUrl);

        String noText = save(slot(1, "", "https://example.com", false, true));
        assertTrue(noText.contains("needs button text"), "url without text should be refused");

        assertTrue(readStored().get(0).isBlank(), "and neither should be stored");
    }

    @Test
    void anEmptySlotIsClearedRatherThanStored() throws Exception {
        save(slot(1, "Weather", "https://example.com", false, true));
        assertFalse(readStored().get(0).isBlank(), "stored to begin with");

        save();   //every field blank
        assertTrue(readStored().get(0).isBlank(), "clearing the row should remove it");
    }

    /** They show in every member's header, so only a director may change them. */
    @Test
    void anOrdinaryMemberIsRefused() throws Exception {
        assertEquals(403, request(get("/ButtonMaintenance"), MEMBER_ID).getResponse().getStatus(),
                     "a non-director should not reach the form");
        assertEquals(403, request(post("/ButtonMaintenance"), MEMBER_ID).getResponse().getStatus(),
                     "nor save one");
    }

    /** Same way out as WebSitePreferences offers. */
    @Test
    void thereIsALinkBackToTheDirectorsMenu() throws Exception {
        String page = body(request(get("/ButtonMaintenance"), DIRECTOR_ID));

        assertTrue(page.contains("Back to Directors Menu"), "the link should be there: " + page);
        assertTrue(page.contains("/Directors?resort=Sample"), "pointing at the Directors page");
    }

    @Test
    void theDirectorsPageLinksToIt() throws Exception {
        String page = body(request(get("/Directors"), DIRECTOR_ID));

        assertTrue(page.contains("Menu Configuration"), "the button should be there: " + page);
        assertTrue(page.contains("ButtonMaintenance?resort=Sample"), "pointing at the new page");
    }

    // ---------- helpers ----------

    private String[] slot(int position, String text, String url, boolean memberOnly, boolean enabled) {
        List<String> params = new ArrayList<>(List.of("text_" + position, text,
                                                      "url_" + position, url));
        if (memberOnly) {
            params.addAll(List.of("memberOnly_" + position, "1"));
        }
        if (enabled) {
            params.addAll(List.of("enabled_" + position, "1"));
        }
        return params.toArray(new String[0]);
    }

    private String save(String... params) throws Exception {
        MockHttpServletRequestBuilder builder = post("/ButtonMaintenance");
        for (int i = 0; i < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return body(request(builder, DIRECTOR_ID));
    }

    private MvcResult request(MockHttpServletRequestBuilder builder, String loggedInId)
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", loggedInId);
        session.setAttribute("loggedInResort", "Sample");

        return mockMvc.perform(builder
                                   .param("resort", "Sample")
                                   .param("ID", loggedInId)
                                   .header("User-Agent", BROWSER)
                                   .session(session))
            .andReturn();
    }

    private String body(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), "the page should render");
        return result.getResponse().getContentAsString();
    }

    private List<MenuButton> readStored() {
        List<MenuButton> stored = new ArrayList<>();
        withPatrolData((log, patrolData) ->
                           stored.addAll(MenuButton.readAll(log, patrolData.getConnection())));
        return stored;
    }

    private void blankAllSlots() {
        List<MenuButton> blanks = new ArrayList<>();
        for (int position = 1; position <= MenuButton.MAX_BUTTONS; position++) {
            blanks.add(MenuButton.empty(position));
        }
        withPatrolData((log, patrolData) ->
                           MenuButton.writeAll(log, patrolData.getConnection(), blanks));
    }

    private List<MenuButton> readStoredButtons() {
        List<MenuButton> stored = new ArrayList<>();
        withPatrolData((log, patrolData) ->
                           stored.addAll(MenuButton.readAll(log, patrolData.getConnection())));
        return stored;
    }

    private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", DIRECTOR_ID);
        session.setAttribute("loggedInResort", "Sample");
        request.setSession(session);
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger log = new Logger(ButtonMaintenanceTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
        PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
        try {
            work.accept(log, patrolData);
        }
        finally {
            patrolData.close();
        }
    }
}
