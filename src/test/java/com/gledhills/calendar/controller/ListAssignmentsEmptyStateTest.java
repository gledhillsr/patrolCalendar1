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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A member with nothing scheduled in the range gets told so, and still gets the rest of the page -
 * the season toggle above all, since widening the range is the obvious next thing to try.
 *
 * Read-only; the sample roster's own data is enough, and nothing here writes.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ListAssignmentsEmptyStateTest {

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
  void anEmptyRangeSaysSoInsteadOfDrawingAnEmptyTable() throws Exception {
    String page = assignmentsPage(false);

    assertTrue(page.contains("No shift assignments found for the selected date range"),
               "the empty range should be spelled out: " + page);
    assertFalse(page.contains("No shifts were scheduled"),
                "the old one-row-table wording should be gone");
    assertFalse(page.contains("<table border=\"1\" width=\"550\""),
                "and no empty assignment table should be drawn");
  }

  /** The point of the change: everything round the message stays put. */
  @Test
  void theRestOfThePageSurvivesAnEmptyRange() throws Exception {
    String page = assignmentsPage(false);

    assertTrue(page.contains("Shift Summary"), "the page heading should still be there");
    assertTrue(page.contains("Assignment Schedule for"), "and the member's headline");
    assertTrue(page.contains("Change to display all assignments"),
               "and the season toggle, which is the way out of an empty range");
    assertTrue(page.contains("Total shifts"), "and the totals line");
  }

  /** With shifts to show it is the table as before, and no empty-state message. */
  @Test
  void aRangeWithShiftsStillDrawsTheTable() throws Exception {
    String page = assignmentsPage(true);

    assertTrue(page.contains("<table border=\"1\" width=\"550\""),
               "all-time should hold shifts for " + MEMBER_ID + ": " + page);
    assertFalse(page.contains("No shift assignments found for the selected date range"),
                "and must not claim the range is empty");
  }

  private String assignmentsPage(boolean displayAll) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", MEMBER_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(get("/ListAssignments")
                             .param("resort", "Sample")
                             .param("ID", MEMBER_ID)
                             .param("DisplayAllAssignments", String.valueOf(displayAll))
                             .header("User-Agent", BROWSER)
                             .session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }
}
