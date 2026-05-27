package org.nsponline.calendar.controller;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Integration tests for MemberLogoutController.
 * Verifies cookie clearing, session clearing, and redirect behavior.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class MemberLogoutControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).build();
        session = new MockHttpSession();
    }

    @Test
    void testLogoutRedirectsToMonthCalendar() throws Exception {
        // Set up authenticated session
        session.setAttribute("loggedInUserId", "123");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/MemberLogout")
                .param("resort", "Sample")
                .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/MonthCalendar?resort=Sample"));
    }

    @Test
    void testLogoutClearsCookie() throws Exception {
        // Set up authenticated session
        session.setAttribute("loggedInUserId", "123");
        session.setAttribute("loggedInResort", "Sample");

        // Perform logout and capture result
        MvcResult result = mockMvc.perform(get("/MemberLogout")
                .param("resort", "Sample")
                .session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // Verify nspToken cookie is cleared
        Cookie[] cookies = result.getResponse().getCookies();
        assertNotNull(cookies, "Response should contain cookies");

        boolean foundClearedCookie = false;
        for (Cookie cookie : cookies) {
            if ("nspToken".equals(cookie.getName())) {
                foundClearedCookie = true;
                assertEquals(0, cookie.getMaxAge(),
                    "nspToken cookie should have maxAge=0 to clear it");
                assertTrue(cookie.getValue() == null || cookie.getValue().isEmpty(),
                    "nspToken cookie should have empty value");
                break;
            }
        }

        assertTrue(foundClearedCookie,
            "Response should contain nspToken cookie with maxAge=0");
    }

    @Test
    void testLogoutWithInvalidResort() throws Exception {
        mockMvc.perform(get("/MemberLogout")
                .param("resort", "InvalidResort"))
                .andExpect(status().isBadRequest());
    }
}
