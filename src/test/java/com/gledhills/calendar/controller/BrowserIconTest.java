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

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The browser icon is declared once, in the head fragment, and served from static/.
 *
 * Two formats on purpose: Safari does not render SVG favicons, so the PNG is its fallback. The
 * links carry the /calendar-1 context path, which is why they are built with @{...} - a bare
 * "/Injury_icon.svg" would 404 behind the context path.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class BrowserIconTest {

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
  void pagesDeclareTheIconInBothFormats() throws Exception {
    for (String url : new String[] {"/MonthCalendar", "/MemberList", "/Directors", "/ListAssignments"}) {
      String page = page(url);

      // prefix-agnostic on purpose: @{...} prepends the /calendar-1 context path at runtime, but
      // MockMvc serves from "", so asserting the prefix would only pin the test environment
      assertTrue(Pattern.compile("<link[^>]*rel=\"icon\"[^>]*Injury_icon\\.svg").matcher(page).find(),
                 url + " should link the SVG icon: " + page);
      assertTrue(Pattern.compile("<link[^>]*rel=\"icon\"[^>]*favicon-32\\.png").matcher(page).find(),
                 url + " should link the PNG fallback, which is what Safari uses");
      assertTrue(page.contains("apple-touch-icon"), url + " should link the touch icon");
    }
  }

  @Test
  void theIconFilesAreActuallyServed() throws Exception {
    for (String file : new String[] {"/Injury_icon.svg", "/favicon-32.png", "/apple-touch-icon.png"}) {
      mockMvc.perform(get(file).header("User-Agent", BROWSER))
        .andExpect(status().isOk());
    }
  }

  private String page(String url) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", MEMBER_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(get(url)
                             .param("resort", "Sample")
                             .param("ID", MEMBER_ID)
                             .header("User-Agent", BROWSER)
                             .session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }
}
