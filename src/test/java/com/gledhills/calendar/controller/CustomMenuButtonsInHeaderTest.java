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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The resort-configured buttons, drawn in the header's light grey bar.
 *
 * The bar is drawn only when this viewer has a button to see, so a resort that has configured
 * none - which is every resort but Brighton today - gets no empty grey strip. A "Member only"
 * button is kept from visitors, so the bar can be absent for the public and present once somebody
 * logs in.
 *
 * Writes to Sample's menubuttons table and clears it afterwards. Brighton's two rows are the real
 * migration of its old hardcoded pair and are left alone.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class CustomMenuButtonsInHeaderTest {

    private static final String DIRECTOR_ID = "123456";
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

    /** Brighton's Volunteer Application and Weather are rows now, not markup. */
    @Test
    void brightonsButtonsComeFromTheTable() throws Exception {
        String bar = greyBar(header("Brighton", "285985"));

        assertTrue(bar.contains("Volunteer Application"), "Volunteer Application should be drawn: " + bar);
        assertTrue(bar.contains("/Brighton/Volunteer.htm"), "at its configured url");
        assertTrue(bar.contains("Weather"), "and Weather beside it");
        assertTrue(bar.contains("http://ccweather.com/w/index.php"), "at its configured url");
    }

    @Test
    void theHeaderNoLongerHardcodesThem() throws Exception {
        String markup = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/resources/templates/fragments/header.html"));

        assertFalse(markup.contains("/Brighton/Volunteer.htm"),
                    "the url should live in the table, not the template");
        assertFalse(markup.contains("ccweather.com"), "same for Weather");
    }

    /** A resort with nothing configured gets no empty grey strip. */
    @Test
    void aResortWithNoButtonsHasNoGreyBar() throws Exception {
        assertFalse(header("Sample", DIRECTOR_ID).contains("class=\"nav-public\""),
                    "there should be no grey bar at all");
    }

    @Test
    void aConfiguredButtonAppearsForThatResort() throws Exception {
        store(new MenuButton(1, "Trail Map", "https://example.com/map", false, true));

        assertTrue(greyBar(header("Sample", DIRECTOR_ID)).contains("Trail Map"),
                   "the configured button should be drawn");
    }

    /** Enabled is the switch: a complete but disabled button draws nothing, and empties the bar. */
    @Test
    void aDisabledButtonIsNotDrawn() throws Exception {
        store(new MenuButton(1, "Trail Map", "https://example.com/map", false, false));

        assertFalse(header("Sample", DIRECTOR_ID).contains("Trail Map"), "a disabled button is hidden");
        assertFalse(header("Sample", DIRECTOR_ID).contains("class=\"nav-public\""),
                    "and with nothing left to show, so is the bar");
    }

    /** Member-only buttons belong in the members' bar, not the public grey one. */
    @Test
    void aMemberOnlyButtonGoesInTheMembersBar() throws Exception {
        store(new MenuButton(1, "Members Handbook", "/handbook.pdf", true, true));

        assertTrue(memberBar(header("Sample", DIRECTOR_ID)).contains("Members Handbook"),
                   "a member should see it in the members bar");
        assertFalse(header("Sample", DIRECTOR_ID).contains("class=\"nav-public\""),
                    "and it must not put a public grey bar on screen");
    }

    /** Where exactly: after Directors, before Logout. */
    @Test
    void memberOnlyButtonsSitBetweenDirectorsAndLogout() throws Exception {
        store(new MenuButton(1, "Members Handbook", "/handbook.pdf", true, true));

        String bar = memberBar(header("Sample", DIRECTOR_ID));
        int directors = bar.indexOf("Directors");
        int handbook = bar.indexOf("Members Handbook");
        int logout = bar.indexOf("Logout");

        assertTrue(directors < handbook && handbook < logout,
                   "order should be Directors, custom, Logout: " + bar);
    }

    /** Brighton's Refresher/Training is one of these now, and lands in the same place. */
    @Test
    void brightonsRefresherTrainingIsAMemberOnlyButton() throws Exception {
        String bar = memberBar(header("Brighton", "285985"));

        assertTrue(bar.contains("Refresher/Training"), "it should be in the members bar: " + bar);
        assertTrue(bar.indexOf("Directors") < bar.indexOf("Refresher/Training"), "after Directors");
        assertTrue(bar.indexOf("Refresher/Training") < bar.indexOf("Logout"), "and before Logout");
    }

    /**
     * A link into this app has to say who is asking. The built-in menu items build that in; a
     * configured url is a fixed string, so {ID} and {RESORT} are substituted when it is drawn.
     * Without it, Brighton's Refresher/Training arrived with no member on it and the page sent
     * the director back to the login screen they had just come through.
     */
    @Test
    void placeholdersAreFilledInWithTheViewerAndResort() throws Exception {
        store(new MenuButton(1, "Training",
                             "/calendar-1/RefresherTraining?resort={RESORT}&ID={ID}", true, true));

        String bar = memberBar(header("Sample", DIRECTOR_ID));
        assertTrue(bar.contains("resort=Sample"), "the resort should be filled in: " + bar);
        assertTrue(bar.contains("ID=" + DIRECTOR_ID), "and the signed-in member");
        assertFalse(bar.contains("{ID}"), "no placeholder should survive to the page");
    }

    /** Brighton's real row uses them, so its button carries the member through. */
    @Test
    void brightonsTrainingLinkCarriesTheMember() throws Exception {
        String bar = memberBar(header("Brighton", "285985"));

        assertTrue(bar.contains("ID=285985"), "the link should identify the member: " + bar);
        assertTrue(bar.contains("resort=Brighton"), "and the resort");
    }

    /** An off-site link opens in a new tab; one on this site does not. */
    @Test
    void offSiteLinksOpenInANewTab() throws Exception {
        store(new MenuButton(1, "Weather", "https://example.com/w", false, true),
              new MenuButton(2, "Handbook", "/handbook.pdf", false, true));

        String bar = greyBar(header("Sample", DIRECTOR_ID));
        assertTrue(Pattern.compile("https://example\\.com/w\"[^>]*target=\"_blank\"").matcher(bar).find(),
                   "off-site should be _blank: " + bar);
        assertTrue(Pattern.compile("/handbook\\.pdf\"[^>]*target=\"_self\"").matcher(bar).find(),
                   "on-site should be _self: " + bar);
    }

    // ---------- helpers ----------

    private String header(String resort, String patrollerId) throws Exception {
        MockHttpSession session = new MockHttpSession();
        if (patrollerId != null) {
            session.setAttribute("loggedInUserId", patrollerId);
            session.setAttribute("loggedInResort", resort);
        }
        var builder = get("/MonthCalendar").param("resort", resort);
        if (patrollerId != null) {
            builder = builder.param("ID", patrollerId);
        }
        return mockMvc.perform(builder.header("User-Agent", BROWSER).session(session))
            .andReturn().getResponse().getContentAsString();
    }

    private String memberBar(String page) {
        return bar(page, "nav-members");
    }

    private String greyBar(String page) {
        return bar(page, "nav-public");
    }

    /**
     * One bar's markup, with HTML comments stripped - the template's own comment names both
     * "Directors" and "Logout", which an index-of ordering check would otherwise measure.
     */
    private String bar(String page, String cssClass) {
        Matcher matcher = Pattern.compile("<div class=\"" + cssClass + "\">(.*?)</div>",
                                          Pattern.DOTALL).matcher(page);
        return matcher.find() ? matcher.group(1).replaceAll("(?s)<!--.*?-->", "")
                              : "(no " + cssClass + " bar)";
    }

    private void store(MenuButton... buttons) {
        List<MenuButton> all = new ArrayList<>();
        for (int position = 1; position <= MenuButton.MAX_BUTTONS; position++) {
            all.add(MenuButton.empty(position));
        }
        for (MenuButton button : buttons) {
            all.set(button.getPosition() - 1, button);
        }
        withPatrolData((log, patrolData) -> MenuButton.writeAll(log, patrolData.getConnection(), all));
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
        Logger log = new Logger(CustomMenuButtonsInHeaderTest.class, javaxRequest, "GET", "Sample",
                                Logger.INFO);
        PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
        try {
            work.accept(log, patrolData);
        }
        finally {
            patrolData.close();
        }
    }
}
