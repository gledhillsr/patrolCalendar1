package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the shift change screen reached by clicking a name on the month calendar.
 *
 * Runs against the live Sample database. November 2024 is the month with a missed shift on it:
 * 7 November position 1/slot 1 holds Jane Doe, flagged missed. 13 November position 1/slot 1
 * holds an ordinary, unflagged patroller. Both slots must be occupied - the option does not
 * render for an empty slot.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ChangeShiftAssignmentsControllerTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /**
   * The missedShift transaction toggles the flag, so on an already-missed slot the option has to
   * say it will clear it.
   */
  @Test
  void anAlreadyMissedShiftOffersToRemoveTheFlag() throws Exception {
    String page = slotPage(7, 1, 1);

    //match the rendered label, not the phrase wherever it appears - the template carries an
    //explanatory comment containing the same words
    assertTrue(page.contains("<b>Remove Missed Shift</b>"),
               "a slot already flagged as missed should offer to remove the flag");
  }

  @Test
  void anOrdinaryShiftOffersToFlagItAsMissed() throws Exception {
    String page = slotPage(13, 1, 1);

    assertTrue(page.contains("<b>Missed Shift</b>"),
               "an unflagged slot should offer to mark the shift missed");
    assertFalse(page.contains("<b>Remove Missed Shift</b>"),
                "and it should not offer to remove a flag that is not set");
  }

  /** The given day of November 2024, shift group and slot, viewed as a director. */
  private String slotPage(int dayOfMonth, int pos, int index) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    MockHttpServletRequestBuilder request = get("/ChangeShiftAssignments")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("dayOfWeek", "4")
      .param("date", String.valueOf(dayOfMonth))
      .param("month", "10")
      .param("year", "2024")
      .param("pos", String.valueOf(pos))
      .param("index", String.valueOf(index))
      .header("User-Agent", BROWSER)
      .session(session);

    return mockMvc.perform(request)
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }
}
