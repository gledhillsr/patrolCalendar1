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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for WebSitePreferencesController.
 * Verifies authentication, authorization, form display, and preferences update.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class WebSitePreferencesControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        session = new MockHttpSession();
    }

    /**
     * Test that accessing WebSitePreferences without authentication redirects to login.
     */
    @Test
    void testWebSitePreferencesRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/WebSitePreferences")
                .param("resort", "Sample")
                .param("ID", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> {
                    String redirectUrl = result.getResponse().getRedirectedUrl();
                    assert redirectUrl != null && redirectUrl.contains("MemberLogin")
                        && redirectUrl.contains("resort=Sample")
                        : "Expected redirect to MemberLogin with resort=Sample, got: " + redirectUrl;
                });
    }

    /**
     * Test that non-director users see "not authorized" message.
     */
    @Test
    void testNonDirectorSeesNotAuthorizedMessage() throws Exception {
        // Set up session with non-director user
        session.setAttribute("loggedInUserId", "12345");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/WebSitePreferences")
                .param("resort", "Sample")
                .param("ID", "12345")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Sorry, you are -not- authorized for this menu")));
    }

    /**
     * Test that director users see the preferences form.
     * Note: Uses actual database user 123456 (Steve) who is a director.
     */
    @Test
    void testDirectorSeesPreferencesForm() throws Exception {
        // Set up session with director user
        session.setAttribute("loggedInUserId", "123456");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/WebSitePreferences")
                .param("resort", "Sample")
                .param("ID", "123456")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Email Settings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Calendar Permissions")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Season Dates")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Blackout Dates")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Save Preferences")));
    }

    /**
     * Test that director can update preferences.
     * Note: This test modifies actual database data - verify state after running.
     */
    @Test
    void testDirectorCanUpdatePreferences() throws Exception {
        // Set up session with director user
        session.setAttribute("loggedInUserId", "123456");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(post("/WebSitePreferences")
                .param("resort", "Sample")
                .param("emailPatrollers", "true")
                .param("emailReminder", "true")
                .param("reminderDays", "3")
                .param("emailAll", "true")
                .param("onlyDirectors", "false")
                .param("removeAccess", "2")
                .param("startDay", "1")
                .param("startMonth", "11")
                .param("endDay", "30")
                .param("endMonth", "4")
                .param("useBlackOut", "false")
                .param("blackOutStartDay", "1")
                .param("blackOutStartMonth", "1")
                .param("blackOutStartYear", "2026")
                .param("blackOutEndDay", "31")
                .param("blackOutEndMonth", "12")
                .param("blackOutEndYear", "2026")
                .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/WebSitePreferences?resort=Sample&ID=123456&success=true"));
    }
}
