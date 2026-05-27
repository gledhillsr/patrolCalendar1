package org.nsponline.calendar.controller;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for DirectorsController.
 * Verifies authentication, authorization, and rendering of directors menu.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class DirectorsControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private MockHttpSession session;

    @Before
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        session = new MockHttpSession();
    }

    /**
     * Test that accessing Directors without authentication redirects to login.
     */
    @Test
    public void testDirectorsRequiresAuthentication() throws Exception {
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
    public void testNonDirectorSeesNotAuthorizedMessage() throws Exception {
        // Set up session with non-director user
        session.setAttribute("loggedInUserId", "12345");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/Directors")
                .param("resort", "Sample")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Sorry, you are -not- authorized for this menu")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Customized Listing")));
    }

    /**
     * Test that director users see the full directors menu.
     * Note: This test verifies the page structure but may need database setup.
     */
    @Test
    public void testDirectorSeesFullMenu() throws Exception {
        // Set up session with director user
        session.setAttribute("loggedInUserId", "DIRECTOR_001");
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
     * Test that backdoor user gets director access.
     * The backdoor user is defined in credentials.properties.
     * Note: This test may need proper backdoor user configuration to pass.
     */
    @Test
    public void testBackdoorUserGetsDirectorAccess() throws Exception {
        // Set up session with backdoor user identifier
        // The actual backdoor user ID comes from credentials.properties
        session.setAttribute("loggedInUserId", "backdoor");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/Directors")
                .param("resort", "Sample")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Patroller Maintenance")));
    }
}
