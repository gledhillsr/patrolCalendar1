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

  /**
   * Typed as "06:15" and stored as "6:15": StartAndLabel puts every time into one canonical form,
   * so two rows of the same shift typed differently still count as one shift in shiftKey.
   */
  private static final String EDITED_START_TIME = "06:15";
  //stored exactly as typed: the editors no longer rewrite a time into 24 hour canonical form
  private static final String EDITED_START_TIME_AS_STORED = EDITED_START_TIME;

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
    assertTrue(markup.contains("Label: <input"), "the label carries an inline caption");
    assertTrue(markup.contains("Start Time: <input"), "the start time carries an inline caption");
    assertTrue(markup.contains("End Time: <input"), "the end time carries an inline caption");
    assertTrue(markup.indexOf("Label: <input") < markup.indexOf("Start Time: <input"),
               "Label comes before Start Time: " + markup);
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
   * A date nobody has saved shows the day-of-week template rows, which are not in the database.
   * New used to build its row at a position the template rows were about to take, so nothing
   * appeared to be added.
   */
  @Test
  void newAddsARowOnADateWithNoSavedData() throws Exception {
    assertEquals(0, rowSummaries().size(), "the scratch date must start with nothing saved");

    String page = editPage();
    int templateRows = hiddenInt(page, "shiftCount");
    assertTrue(templateRows > 0, "the scratch date should render template rows");

    mockMvc.perform(rowRequest(page, templateRows).param("newShift", "New"))
      .andExpect(status().is3xxRedirection());

    assertEquals(templateRows + 1, rowSummaries().size(),
                 "New should have added one row to the template rows: " + rowSummaries());
  }

  /**
   * Same root cause as New: Delete found an empty list of saved rows and returned without
   * removing anything.
   */
  @Test
  void deleteRemovesARowOnADateWithNoSavedData() throws Exception {
    assertEquals(0, rowSummaries().size(), "the scratch date must start with nothing saved");

    String page = editPage();
    int templateRows = hiddenInt(page, "shiftCount");
    assertTrue(templateRows >= 2, "need at least two template rows to delete one");

    mockMvc.perform(rowRequest(page, templateRows).param("delete_0", "Delete"))
      .andExpect(status().is3xxRedirection());

    assertEquals(templateRows - 1, rowSummaries().size(),
                 "Delete should have removed one of the template rows: " + rowSummaries());
  }

  /** The end rows offer one arrow each; the middle rows offer both. */
  @Test
  void arrowsAreOfferedOnlyWhereARowCanActuallyMove() throws Exception {
    String page = editPage();
    int shiftCount = hiddenInt(page, "shiftCount");
    assertTrue(shiftCount >= 2, "need at least two rows to test reordering");
    int lastRow = shiftCount - 1;

    assertFalse(page.contains("name=\"moveUp_0\""), "the top row cannot move up");
    assertTrue(page.contains("name=\"moveDown_0\""), "the top row can move down");
    assertTrue(page.contains("name=\"moveUp_" + lastRow + "\""), "the bottom row can move up");
    assertFalse(page.contains("name=\"moveDown_" + lastRow + "\""),
                "the bottom row cannot move down");
  }

  /** The New button keeps its own row at the very bottom, below every shift. */
  @Test
  void theNewButtonStaysBelowEveryShiftRow() throws Exception {
    String page = editPage();
    int shiftCount = hiddenInt(page, "shiftCount");

    int lastRowArrow = page.indexOf("name=\"moveUp_" + (shiftCount - 1) + "\"");
    int newButton = page.indexOf("name=\"newShift\"");
    assertTrue(lastRowArrow > 0 && newButton > lastRowArrow,
               "the New button should come after the last shift row");
  }

  /**
   * Moving a row swaps it with its neighbour and carries that shift's assigned patrollers along,
   * because the patrollers belong to the shift rather than to the row position.
   */
  @Test
  void movingARowDownCarriesItsPatrollersWithIt() throws Exception {
    //give the scratch date real saved rows, then put a patroller on row 0
    String page = editPage();
    int shiftCount = hiddenInt(page, "shiftCount");
    assertTrue(shiftCount >= 2, "need at least two rows to reorder");
    saveRowsAsTyped(page, shiftCount);
    assignPatrollerToFirstSlotOfRow(0);

    List<String> before = rowSummaries();
    assertTrue(before.get(0).contains("p0=" + MEMBER_ID),
               "row 0 should start with the patroller on it: " + before);

    mockMvc.perform(rowRequest(editPage(), shiftCount).param("moveDown_0", "\u25BC"))
      .andExpect(status().is3xxRedirection());

    List<String> after = rowSummaries();
    assertEquals(before.get(1).replaceFirst("^pos=\\S+ ", ""),
                 after.get(0).replaceFirst("^pos=\\S+ ", ""),
                 "the old row 1 should now be first: " + after);
    assertEquals(before.get(0).replaceFirst("^pos=\\S+ ", ""),
                 after.get(1).replaceFirst("^pos=\\S+ ", ""),
                 "and the old row 0, patroller included, should now be second: " + after);
    assertTrue(after.get(1).contains("p0=" + MEMBER_ID),
               "the patroller must have travelled with the shift: " + after);
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
    assertTrue(grid.contains(EDITED_START_TIME_AS_STORED),
               "the grid should show the edited start time as typed: expected "
                 + EDITED_START_TIME_AS_STORED);

    assertEquals(EDITED_START_TIME_AS_STORED, savedStartTimeOnDisk(),
                 "and the edit should have been written to the database exactly as typed");
  }

  private static final String MEMBER_ID = "200401";

  /** A POST echoing every shift row back exactly as rendered, with no action button set. */
  /**
   * Reducing a row's Patroller Count keeps everybody who still fits, by closing the gaps.
   *
   * <p>Slots are a grid rather than a queue - three patrollers can sit in slots 1, 3 and 5 - so
   * going from five slots to three used to leave the one in slot 5 stored but no longer drawn:
   * off the calendar, out of every count, and still on the row. This is the whole path, through
   * the controller and back out of the database.
   */
  @Test
  void reducingAShiftsCountMovesPatrollersDownIntoTheSlotsThatRemain() throws Exception {
    String page = editPage();
    int shiftCount = hiddenInt(page, "shiftCount");
    saveRowsAsTyped(page, shiftCount);

    //five slots on row 0, with people in the 1st, 3rd and 5th
    withPatrolData(patrolData -> {
      Assignments row = scratchRows(patrolData).get(0);
      row.setCount(5);
      row.insertAt("Sample", 0, MEMBER_ID);
      row.insertAt("Sample", 2, DIRECTOR_ID);
      row.insertAt("Sample", 4, "200402");
      row.setExisted(true);
      patrolData.writeAssignment(row);
    });

    mockMvc.perform(rowRequest(editPage(), shiftCount, java.util.Map.of("count_0", "3"))
                      .param("saveShiftBtn", "Save Shift Changes"))
      .andExpect(status().is3xxRedirection());

    withPatrolData(patrolData -> {
      Assignments row = scratchRows(patrolData).get(0);
      assertEquals(3, row.getCount());
      assertEquals(MEMBER_ID, row.getPosID(0));
      assertEquals(DIRECTOR_ID, row.getPosID(1), "the one in slot 3 moves up, it is not lost");
      assertEquals("200402", row.getPosID(2), "and so does the one in slot 5");
      for (int slot = 3; slot < Assignments.MAX_ASSIGNMENT_SIZE; slot++) {
        assertEquals("0", row.getPosID(slot), "slot " + slot + " past the count must be emptied");
      }
    });
  }

  /** What the browser needs to decide whether to warn: how many are really on each row. */
  @Test
  void theCountDropdownCarriesHowManyPatrollersAreOnTheRow() throws Exception {
    String page = editPage();
    saveRowsAsTyped(page, hiddenInt(page, "shiftCount"));
    assignPatrollerToFirstSlotOfRow(0);

    assertTrue(editPage().contains("data-filled=\"1\""),
               "the count dropdown should say how many patrollers the row holds");
  }

  private MockHttpServletRequestBuilder rowRequest(String page, int shiftCount) {
    return rowRequest(page, shiftCount, java.util.Map.of());
  }

  /**
   * @param overrides field name to value, replacing what the page rendered. They have to be
   *   substituted rather than appended: Spring hands getParameter the FIRST of two values for the
   *   same name, so an extra count_0 alongside the page's own would be silently ignored.
   */
  private MockHttpServletRequestBuilder rowRequest(String page, int shiftCount,
                                                   java.util.Map<String, String> overrides) {
    MockHttpServletRequestBuilder request = post("/DayShifts")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
      .param("date", String.valueOf(SCRATCH_DATE))
      .param("month", String.valueOf(SCRATCH_MONTH))
      .param("year", String.valueOf(SCRATCH_YEAR))
      .param("shiftCount", String.valueOf(shiftCount))
      .param("newName", "")
      .header("User-Agent", BROWSER)
      .session(directorSession());

    for (int row = 0; row < shiftCount; row++) {
      request = request
        .param("startTime_" + row, overrides.getOrDefault("startTime_" + row,
                                                          namedValue(page, "startTime_" + row)))
        .param("endTime_" + row, overrides.getOrDefault("endTime_" + row,
                                                        namedValue(page, "endTime_" + row)))
        .param("count_" + row, overrides.getOrDefault("count_" + row,
                                                      selectedOption(page, "count_" + row)))
        .param("shift_" + row, overrides.getOrDefault("shift_" + row,
                                                      selectedOption(page, "shift_" + row)));
    }
    return request;
  }

  /** Persist the rows exactly as rendered, so the scratch date holds real saved assignments. */
  private void saveRowsAsTyped(String page, int shiftCount) throws Exception {
    mockMvc.perform(rowRequest(page, shiftCount).param("saveShiftBtn", "Save Shift Changes"))
      .andExpect(status().is3xxRedirection());
  }

  /** Put a known member on slot 0 of the given saved row. */
  private void assignPatrollerToFirstSlotOfRow(int row) {
    withPatrolData(patrolData -> {
      List<Assignments> rows = scratchRows(patrolData);
      Assignments target = rows.get(row);
      target.insertAt("Sample", 0, MEMBER_ID);
      target.setExisted(true);
      patrolData.writeAssignment(target);
    });
  }

  /** One line per saved row: its position key, times, count, type and slot-0 patroller. */
  private List<String> rowSummaries() {
    List<String> summaries = new ArrayList<>();
    withPatrolData(patrolData -> {
      for (Assignments row : scratchRows(patrolData)) {
        summaries.add("pos=" + row.getDate()
                        + " start=" + row.getStartingTimeString()
                        + " end=" + row.getEndingTimeString()
                        + " count=" + row.getCount()
                        + " type=" + row.getType()
                        + " p0=" + row.getPosID(0));
      }
    });
    return summaries;
  }

  private List<Assignments> scratchRows(PatrolData patrolData) {
    List<Assignments> rows = new ArrayList<>();
    for (Assignments assignment : patrolData.readSortedAssignments(SCRATCH_YEAR, SCRATCH_MONTH + 1)) {
      if (assignment.getDay() == SCRATCH_DATE) {
        rows.add(assignment);
      }
    }
    return rows;
  }

  private void withPatrolData(java.util.function.Consumer<PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(DayShiftsControllerTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(patrolData);
    }
    finally {
      patrolData.close();
    }
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
