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
}
