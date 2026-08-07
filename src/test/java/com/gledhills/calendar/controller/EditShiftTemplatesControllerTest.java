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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the shift template editor. Runs against the live Sample database, where 123456 is a
 * director.
 *
 * These are read-only: the page writes template rows on POST, so the tests only GET.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class EditShiftTemplatesControllerTest {

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

  @Test
  void patrollerCountOffersZeroThroughTen() throws Exception {
    List<String> options = countOptions(page());

    assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), options,
                 "the patroller count dropdown should run from 0 to 10");
  }

  /** Every row must preselect exactly one count, and never fall back to a blank selection. */
  @Test
  void everyRowHasExactlyOneSelectedCount() throws Exception {
    String page = page();

    Matcher selects = Pattern.compile("<select name=\"count_\\d+\".*?</select>", Pattern.DOTALL)
      .matcher(page);
    int rows = 0;
    while (selects.find()) {
      rows++;
      String select = selects.group();
      int selected = select.split("selected=\"selected\"", -1).length - 1;
      assertEquals(1, selected, "row " + rows + " should have exactly one option selected");
    }
    assertTrue(rows > 0, "Sample should have at least one template row to check");
  }

  private String page() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(get("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .header("User-Agent", BROWSER)
                             .session(session))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }

  /** The values offered by the first patroller-count dropdown on the page. */
  private List<String> countOptions(String page) {
    Matcher select = Pattern.compile("<select name=\"count_0\".*?</select>", Pattern.DOTALL)
      .matcher(page);
    assertTrue(select.find(), "the page should hold a patroller count dropdown");

    List<String> values = new ArrayList<>();
    Matcher option = Pattern.compile("value=\"(\\d+)\"").matcher(select.group());
    while (option.find()) {
      values.add(option.group(1));
    }
    return values;
  }
}
