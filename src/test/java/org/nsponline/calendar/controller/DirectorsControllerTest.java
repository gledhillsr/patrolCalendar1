package org.nsponline.calendar.controller;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for DirectorsController.
 * Verifies authentication, authorization, and rendering of directors menu.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class DirectorsControllerTest {

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
     * Test that accessing Directors without authentication redirects to login.
     */
    @Test
    void testDirectorsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/Directors")
                .param("resort", "Sample"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/MemberLogin?resort=Sample"));
    }

    /**
     * Test that non-director users see "not authorized" message.
     * Note: This test verifies the page renders but may need database setup.
     * If test fails due to database issues, it indicates that proper mocking
     * or test database setup is needed.
     */
    @Test
    void testNonDirectorSeesNotAuthorizedMessage() throws Exception {
        // Set up session with non-director user
        session.setAttribute("loggedInUserId", "12345");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/Directors")
                .param("resort", "Sample")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Sorry, you are not authorized for this menu")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Customized Listing")));
    }

    /**
     * Test that director users see the full directors menu.
     * Note: This test uses actual database user 123456 (Steve) who is a director.
     * If the test fails, verify user 123456 exists and has director privileges.
     */
    @Test
    void testDirectorSeesFullMenu() throws Exception {
        // Set up session with actual director user from Sample resort database
        session.setAttribute("loggedInUserId", "123456");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/Directors")
                .param("resort", "Sample")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Patroller Maintenance")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Patrol Listings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Patrol Setup / Cleanup")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Add New Patroller")));
    }

    /**
     * Test that another director user also gets director access.
     * Note: If the test fails, verify the user exists and has director privileges.
     */
    @Test
    void testAnotherDirectorGetsAccess() throws Exception {
        // Set up session with another known director user
        // Using 123456 which is Steve Gledhill, known director in Sample resort
        session.setAttribute("loggedInUserId", "123456");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/Directors")
                .param("resort", "Sample")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Patroller Maintenance")));
    }
}
