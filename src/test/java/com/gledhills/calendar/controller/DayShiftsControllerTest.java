package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the single-day shift editor, in particular that switching to the group assignment
 * grid saves pending row edits instead of dropping them.
 *
 * These POST, so they write to the live Sample database. They work on 15 March 2025 - a date with
 * no saved assignments and outside every month the calendar goldens are recorded for - and delete
 * whatever they created afterwards.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class DayShiftsControllerTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  /** Saturday 15 March 2025: in season, no saved assignments, not used by any golden. */
  private static final int SCRATCH_YEAR = 2025;
  private static final int SCRATCH_MONTH = 2; //0 based: March
  private static final int SCRATCH_DATE = 15;
  private static final int SCRATCH_DAY_OF_WEEK = 6; //0 based: Saturday

  private static final String EDITED_START_TIME = "06:15";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /** Always leave the scratch date as we found it, even when an assertion fails. */
  @AfterEach
  void removeAnythingWeSaved() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(DayShiftsControllerTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);

    try {
      for (Assignments assignment : patrolData.readSortedAssignments(SCRATCH_YEAR, SCRATCH_MONTH + 1)) {
        if (assignment.getDay() == SCRATCH_DATE) {
          patrolData.deleteAssignment(assignment);
        }
      }
    }
    finally {
      patrolData.close();
    }
  }

  /**
   * The button must belong to the shift edit form, otherwise the row fields are never submitted
   * and there is nothing to save.
   */
  @Test
  void theAssignButtonSubmitsTheShiftEditForm() throws Exception {
    String page = editPage();

    assertTrue(page.contains("id=\"editShiftForm\""), "the edit form needs an id to target");
    Matcher button = Pattern.compile("<input[^>]*name=\"doAssignments\"[^>]*>").matcher(page);
    assertTrue(button.find(), "the page should offer the assign button");
    assertTrue(button.group().contains("form=\"editShiftForm\""),
               "the button must submit the edit form: " + button.group());
  }

  /** The shift rows are laid out like EditShiftTemplates: inline labels, same column order. */
  @Test
  void shiftRowsMatchTheShiftTemplateEditorLayout() throws Exception {
    String page = editPage();
    Matcher row = Pattern.compile("<tr>(?:(?!</tr>).)*?name=\"delete_0\".*?</tr>", Pattern.DOTALL)
      .matcher(page);
    assertTrue(row.find(), "the page should hold a shift row");
    String markup = row.group().replaceAll("\\s+", " ");

    assertTrue(markup.contains("value=\"Delete\""), "the delete button reads Delete: " + markup);
    assertTrue(markup.contains("Start: <input"), "the start time carries an inline label");
    assertTrue(markup.contains("End: <input"), "the end time carries an inline label");
    assertTrue(markup.contains("Patroller&nbsp;Count:&nbsp;"), "the count carries an inline label");
    assertTrue(markup.contains("maxlength=\"11\" size=\"7\""),
               "the time fields match the template editor's size");
    //Patroller Count comes before the shift type, as it does on EditShiftTemplates
    assertTrue(markup.indexOf("name=\"count_0\"") < markup.indexOf("name=\"shift_0\""),
               "count should precede shift type: " + markup);
    //the type dropdown shows the full names, not the old abbreviations
    assertTrue(markup.contains(">Day Shift</option>"), "shift types show their full names");
  }

  /**
   * The point of the earlier change: edit a start time, click straight through to the assignment
   * grid, and the edit is saved rather than discarded.
   */
  @Test
  void switchingToTheAssignmentGridSavesPendingShiftEdits() throws Exception {
    String page = editPage();
    int shiftCount = hiddenInt(page, "shiftCount");
    assertTrue(shiftCount > 0, "the scratch date should offer template rows to edit");

    String grid = mockMvc.perform(assignRequest(page, shiftCount))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();

    assertTrue(grid.contains("Change Today's Assignments"),
               "we should have landed on the group assignment grid");
    assertTrue(grid.contains(EDITED_START_TIME),
               "the grid should show the edited start time: expected " + EDITED_START_TIME);

    assertEquals(EDITED_START_TIME, savedStartTimeOnDisk(),
                 "and the edit should have been written to the database");
  }

  private MockHttpServletRequestBuilder assignRequest(String page, int shiftCount) {
    MockHttpServletRequestBuilder request = post("/DayShifts")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
      .param("date", String.valueOf(SCRATCH_DATE))
      .param("month", String.valueOf(SCRATCH_MONTH))
      .param("year", String.valueOf(SCRATCH_YEAR))
      .param("shiftCount", String.valueOf(shiftCount))
      .param("newName", "")
      .param("doAssignments", "Assign Patrollers to Today's Shifts")
      .header("User-Agent", BROWSER)
      .session(directorSession());

    //echo the rows back as the browser would, with row 0's start time changed
    for (int row = 0; row < shiftCount; row++) {
      request = request
        .param("startTime_" + row, row == 0 ? EDITED_START_TIME : namedValue(page, "startTime_" + row))
        .param("endTime_" + row, namedValue(page, "endTime_" + row))
        .param("count_" + row, selectedOption(page, "count_" + row))
        .param("shift_" + row, selectedOption(page, "shift_" + row));
    }
    return request;
  }

  private String editPage() throws Exception {
    return mockMvc.perform(get("/DayShifts")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
                             .param("date", String.valueOf(SCRATCH_DATE))
                             .param("month", String.valueOf(SCRATCH_MONTH))
                             .param("year", String.valueOf(SCRATCH_YEAR))
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }

  /** Row 0's start time as actually stored for the scratch date. */
  private String savedStartTimeOnDisk() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(DayShiftsControllerTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);

    try {
      List<Assignments> monthly = patrolData.readSortedAssignments(SCRATCH_YEAR, SCRATCH_MONTH + 1);
      for (Assignments assignment : monthly) {
        if (assignment.getDay() == SCRATCH_DATE) {
          return assignment.getStartingTimeString();
        }
      }
      return "(nothing saved for the scratch date)";
    }
    finally {
      patrolData.close();
    }
  }

  private MockHttpSession directorSession() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    return session;
  }

  private int hiddenInt(String page, String name) {
    Matcher matcher = Pattern.compile("name=\"" + name + "\" value=\"(\\d+)\"").matcher(page);
    assertTrue(matcher.find(), "the page should carry a hidden " + name);
    return Integer.parseInt(matcher.group(1));
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
