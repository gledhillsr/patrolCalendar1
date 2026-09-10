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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SetupListPatrollers had no render coverage at all, which matters because its page turns model
 * flags into markup and SpEL throws on a null operand rather than treating it as false. The flags
 * are primitive booleans set on the single render path, so they cannot be null; this holds that
 * true rather than leaving it to inspection.
 *
 * <p>The director-only fields were once Brighton's alone. They are every resort's now - the
 * columns behind them (teamLead, mentoring, comments) are in the roster of all 54 databases and in
 * createTablesForNewResort.sql, so there was never anything resort-specific but the checkbox.
 *
 * Read-only: the page is a form, nothing is submitted here.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class SetupListPatrollersRenderTest {

  private static final String DIRECTOR_ID = "123456";
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
  void itRendersForADirector() throws Exception {
    String page = page(DIRECTOR_ID);

    assertTrue(page.contains("Customized Patrol List"), "the page should render: " + page);
    assertTrue(page.contains("Display / Print / Email"), "with its submit button");
  }

  /** The other side of the "and": a non-director must not blow the page up either. */
  @Test
  void itRendersForAnOrdinaryMember() throws Exception {
    String page = page(MEMBER_ID);

    assertTrue(page.contains("Customized Patrol List"), "the page should render: " + page);
  }

  private String page(String id) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", id);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(get("/SetupListPatrollers")
                             .param("resort", "Sample")
                             .param("ID", id)
                             .header("User-Agent", BROWSER)
                             .session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  /**
   * The director-only fields are offered to every director, not only Brighton's.
   *
   * <p>Named individually because the failure would be silent: a director at another resort simply
   * would not see the boxes, and nothing else would complain.
   */
  @Test
  void everyDirectorIsOfferedTheDirectorOnlyFields() throws Exception {
    String page = page(DIRECTOR_ID);

    assertTrue(page.contains("(Director ONLY access)"), "the heading note should be there");
    assertTrue(page.contains("name=\"COMMENTS\""), "Private Comments");
    assertTrue(page.contains("name=\"TEAM_LEAD\""), "Team Lead Trained");
    assertTrue(page.contains("name=\"MENTORING\""), "Mentoring Status");
  }
}
