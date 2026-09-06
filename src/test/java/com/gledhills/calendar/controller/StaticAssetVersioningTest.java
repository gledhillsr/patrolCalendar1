package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A changed script or stylesheet has to reach a browser that is already holding the old one.
 *
 * <p>This exists because it went wrong: {@code shiftTime.js} gained the End Time check while
 * browsers held the copy from the Start Time work, so {@code onblur="checkEndTime(this)"} called a
 * function that was not there. Nothing said so - the error is silent, the submit check passed the
 * bad value through, and the first thing to object was the server, at save time.
 *
 * <p>Two halves, and the bug is in having only one of them: the content strategy in
 * application.properties serves the hashed URL, and {@link
 * com.gledhills.calendar.config.StaticAssetConfig} registers the filter that makes pages ask for
 * it. Boot does not register that filter by itself, so with the property alone every page goes on
 * asking for the unhashed name and nothing changes.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class StaticAssetVersioningTest {

    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";
    private static final String DIRECTOR_ID = "123456";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        //MockMvc does not pick up registered filters, so the one under test is added by hand
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                                 .addFilters(new ResourceUrlEncodingFilter()).build();
    }

    /** Without this bean the property is set and silently does nothing. */
    @Test
    void theRewritingFilterIsRegistered() {
        assertEquals(1,
                     webApplicationContext.getBeanNamesForType(ResourceUrlEncodingFilter.class).length,
                     "Boot does not register ResourceUrlEncodingFilter on its own - "
                       + "StaticAssetConfig has to, or no page ever asks for a hashed URL");
    }

    @Test
    void theShiftEditorAsksForAHashedScript() throws Exception {
        String source = assetUrl(page(), "src=\"([^\"]*shiftTime[^\"]*)\"");

        assertTrue(source.matches("/js/shiftTime-[0-9a-f]{32}\\.js"),
                   "the script should carry a content hash, got: " + source);
        assertEquals(200, mockMvc.perform(get(source).header("User-Agent", BROWSER))
                                 .andReturn().getResponse().getStatus(),
                     "and the hashed URL has to actually serve the file");
    }

    /** The shared dialog script is versioned too - it is edited as often as shiftTime.js. */
    @Test
    void theDialogScriptIsHashedAndServed() throws Exception {
        String source = assetUrl(page(), "src=\"([^\"]*dialogs[^\"]*)\"");

        assertTrue(source.matches("/js/dialogs-[0-9a-f]{32}\\.js"),
                   "the dialog script should carry a content hash, got: " + source);
        assertEquals(200, mockMvc.perform(get(source).header("User-Agent", BROWSER))
                                 .andReturn().getResponse().getStatus());
    }

    @Test
    void stylesheetsAreVersionedTheSameWay() throws Exception {
        String href = assetUrl(page(), "href=\"([^\"]*header[^\"]*\\.css)\"");

        assertTrue(href.matches("/css/header-[0-9a-f]{32}\\.css"),
                   "the stylesheet should carry a content hash, got: " + href);
        assertEquals(200, mockMvc.perform(get(href).header("User-Agent", BROWSER))
                                 .andReturn().getResponse().getStatus());
    }

    /**
     * Every page that says or asks something uses the one dialog, so the site has a single style.
     *
     * <p>Named individually rather than scanned, because the failure this guards against is a new
     * page quietly reaching for {@code alert()} again - and a scan of the templates directory would
     * pass just as happily with these pages deleted.
     */
    @Test
    void noPageFallsBackToTheBrowsersOwnDialogs() throws Exception {
        List<String> templates =
            List.of("day-shifts", "edit-shift-templates", "site-wide-configuration",
                    "purge-assignments", "update-info", "email-form", "member-list",
                    "member-login", "setup-list-patrollers", "shift-conflict",
                    "volunteer-form-success");

        List<String> offenders = new ArrayList<>();
        for (String template : templates) {
            String source =
                Files.readString(Path.of("src/main/resources/templates/" + template + ".html"));
            //the comments explaining what these replaced are allowed to name them
            String code = source.replaceAll("<!--.*?-->", "").replaceAll("//[^\n]*", "");
            if (code.contains("alert(") || code.contains("confirm(")) {
                offenders.add(template);
            }
            if (!code.contains("/js/dialogs.js")) {
                offenders.add(template + " (does not load dialogs.js)");
            }
        }
        assertEquals(List.of(), offenders,
                     "these should use showMessageDialog / askQuestionDialog / confirmActionDialog");
    }

    private String page() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", DIRECTOR_ID);
        session.setAttribute("loggedInResort", "Sample");

        return mockMvc.perform(get("/EditShiftTemplates")
                                 .param("resort", "Sample").param("ID", DIRECTOR_ID)
                                 .header("User-Agent", BROWSER).session(session))
                      .andReturn().getResponse().getContentAsString();
    }

    private String assetUrl(String page, String pattern) {
        Matcher found = Pattern.compile(pattern).matcher(page);
        assertTrue(found.find(), "the page should link the asset: " + pattern);
        return found.group(1);
    }
}
