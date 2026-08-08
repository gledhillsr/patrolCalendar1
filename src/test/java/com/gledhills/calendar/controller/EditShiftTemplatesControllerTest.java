package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the shift template editor. Runs against the live Sample database, where 123456 is a
 * director.
 *
 * The reorder test writes, and puts the template back afterwards. It works on "Midnight", chosen
 * because it has three rows and is NOT a day-of-week name: templates called Sunday..Saturday fill
 * in empty days on the month calendar, so reordering one would change the calendar goldens.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class EditShiftTemplatesControllerTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  /** Three rows, and not a day-of-week template, so the month calendar is unaffected. */
  private static final String MULTI_ROW_TEMPLATE = "Midnight";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Test
  void patrollerCountOffersZeroThroughTen() throws Exception {
    List<String> options = countOptions(page(MULTI_ROW_TEMPLATE));

    assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), options,
                 "the patroller count dropdown should run from 0 to 10");
  }

  /** Every row must preselect exactly one count, and never fall back to a blank selection. */
  @Test
  void everyRowHasExactlyOneSelectedCount() throws Exception {
    String page = page(MULTI_ROW_TEMPLATE);

    Matcher selects = Pattern.compile("<select name=\"count_\\d+\".*?</select>", Pattern.DOTALL)
      .matcher(page);
    int rows = 0;
    while (selects.find()) {
      rows++;
      int selected = selects.group().split("selected=\"selected\"", -1).length - 1;
      assertEquals(1, selected, "row " + rows + " should have exactly one option selected");
    }
    assertTrue(rows > 0, "Sample should have at least one template row to check");
  }

  /** The end rows offer one arrow each, matching DayShifts. */
  @Test
  void arrowsAreOfferedOnlyWhereARowCanActuallyMove() throws Exception {
    String page = page(MULTI_ROW_TEMPLATE);
    int rows = countRows(page);
    assertTrue(rows >= 2, "need at least two rows in " + MULTI_ROW_TEMPLATE + ", found " + rows);
    int lastRow = rows - 1;

    assertFalse(page.contains("name=\"moveUp_0\""), "the top row cannot move up");
    assertTrue(page.contains("name=\"moveDown_0\""), "the top row can move down");
    assertTrue(page.contains("name=\"moveUp_" + lastRow + "\""), "the bottom row can move up");
    assertFalse(page.contains("name=\"moveDown_" + lastRow + "\""),
                "the bottom row cannot move down");
  }

  /** The New button keeps its own row at the very bottom, below every shift. */
  @Test
  void theNewButtonStaysBelowEveryShiftRow() throws Exception {
    String page = page(MULTI_ROW_TEMPLATE);
    int lastArrow = page.indexOf("name=\"moveUp_" + (countRows(page) - 1) + "\"");
    int newButton = page.indexOf("name=\"addShift\"");

    assertTrue(lastArrow > 0 && newButton > lastArrow,
               "the New button should come after the last shift row");
  }

  /**
   * Clicking a down arrow swaps the row with the one below it. A template carries no patrollers,
   * so only the definition moves.
   */
  @Test
  void movingARowDownSwapsItWithTheRowBelow() throws Exception {
    List<String> before = templateRows(MULTI_ROW_TEMPLATE);
    assertTrue(before.size() >= 2, "need at least two rows: " + before);

    try {
      clickArrow("moveDown_0", before.size());

      List<String> after = templateRows(MULTI_ROW_TEMPLATE);
      assertEquals(before.size(), after.size(), "a move must not add or drop rows");
      assertEquals(definitionOf(before.get(1)), definitionOf(after.get(0)),
                   "row 1's definition should now be first: " + after);
      assertEquals(definitionOf(before.get(0)), definitionOf(after.get(1)),
                   "row 0's definition should now be second: " + after);
    }
    finally {
      clickArrow("moveUp_1", before.size()); //put the template back as we found it
    }

    assertEquals(before, templateRows(MULTI_ROW_TEMPLATE),
                 "the template should be restored to its original order");
  }

  /** Submit the template form with one arrow pressed, echoing the rows back unchanged. */
  private void clickArrow(String arrow, int rows) throws Exception {
    String page = page(MULTI_ROW_TEMPLATE);

    MockHttpServletRequestBuilder request = post("/EditShiftTemplates")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("selectedShift", MULTI_ROW_TEMPLATE)
      .param("eventName", MULTI_ROW_TEMPLATE)
      .param("shiftCount", String.valueOf(rows))
      .param(arrow, "move")
      .header("User-Agent", BROWSER)
      .session(directorSession());

    for (int row = 0; row < rows; row++) {
      request = request
        .param("startTime_" + row, namedValue(page, "startTime_" + row))
        .param("endTime_" + row, namedValue(page, "endTime_" + row))
        .param("count_" + row, selectedOption(page, "count_" + row))
        .param("shift_" + row, selectedOption(page, "shift_" + row));
    }
    mockMvc.perform(request).andExpect(status().isOk());
  }

  /** "name start end count type" for every row of a template, in stored order. */
  private List<String> templateRows(String template) {
    List<String> rows = new ArrayList<>();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(EditShiftTemplatesControllerTest.class, javaxRequest, "GET", "Sample",
                            Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);

    try {
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(template)) {
          rows.add(shift.getEventName() + " " + shift.getStartString() + " " + shift.getEndString()
                     + " " + shift.getCount() + " " + shift.getType());
        }
      }
    }
    finally {
      patrolData.close();
    }
    return rows;
  }

  /** A row summary without its position-carrying name, so two positions can be compared. */
  private String definitionOf(String rowSummary) {
    return rowSummary.substring(rowSummary.indexOf(' ') + 1);
  }

  private String page(String template) throws Exception {
    return mockMvc.perform(get("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("selectedShift", template)
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }

  private int countRows(String page) {
    Matcher matcher = Pattern.compile("name=\"delete_(\\d+)\"").matcher(page);
    int rows = 0;
    while (matcher.find()) {
      rows++;
    }
    return rows;
  }

  private MockHttpSession directorSession() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    return session;
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

  /** The value attribute of a named text input. */
  private String namedValue(String page, String name) {
    Matcher matcher = Pattern.compile("name=\"" + name + "\"\\s+value=\"([^\"]*)\"").matcher(page);
    return matcher.find() ? matcher.group(1) : "";
  }

  /** The selected option of a named select. */
  private String selectedOption(String page, String name) {
    Matcher select = Pattern.compile("<select name=\"" + name + "\".*?</select>", Pattern.DOTALL)
      .matcher(page);
    if (!select.find()) {
      return "";
    }
    Matcher option = Pattern.compile("value=\"([^\"]*)\"\\s*\\n?\\s*selected=\"selected\"")
      .matcher(select.group());
    return option.find() ? option.group(1) : "";
  }
}
