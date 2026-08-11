package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.store.ShiftTemplatePatrollers;
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
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The end-to-end behaviour the feature exists for: a patroller pencilled into a day-of-week
 * template turns up on a calendar day built from that template.
 *
 * Works on Saturday 15 March 2025 - a date with nothing saved and outside every month the calendar
 * goldens are recorded for - and removes both the day's rows and the template patrollers afterwards.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class TemplatePatrollerFlowTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String MEMBER_ID = "200401";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  private static final String TEMPLATE = "Saturday";
  /** Something to switch the day to; not a day-of-week name, so it never auto-fills. */
  private static final String REPLACEMENT_TEMPLATE = "Midnight";
  private static final int SCRATCH_YEAR = 2025;
  private static final int SCRATCH_MONTH = 2; //0 based: March
  private static final int SCRATCH_DATE = 15;
  private static final int SCRATCH_DAY_OF_WEEK = 6;

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /** Leave both the scratch day and the Saturday template as we found them. */
  @AfterEach
  void cleanUp() {
    withPatrolData((log, patrolData) -> {
      for (Assignments assignment : patrolData.readSortedAssignments(SCRATCH_YEAR, SCRATCH_MONTH + 1)) {
        if (assignment.getDay() == SCRATCH_DATE) {
          patrolData.deleteAssignment(assignment);
        }
      }
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        String name = shift.parsedEventName();
        if (name.equals(TEMPLATE) || name.equals(REPLACEMENT_TEMPLATE)) {
          ShiftTemplatePatrollers.deleteShift(log, patrolData.getConnection(), shift.getEventName());
        }
      }
    });
  }

  /**
   * The director sees the template's patroller on a fresh day. The plain edit view only shows
   * times and counts, so this checks the group assignment grid, which is where DayShifts lists
   * who is on each slot.
   */
  @Test
  void templatePatrollerIsVisibleOnAFreshDaysAssignmentGrid() throws Exception {
    givenTemplateSlotZeroHolds(MEMBER_ID);

    String grid = assignmentGrid();

    assertTrue(grid.contains("Change Today's Assignments"), "expected the assignment grid");
    assertTrue(firstSlotFieldValue(grid).contains("Bailey"),
               "the template's patroller should be pre-filled: " + firstSlotFieldValue(grid));
  }

  /** Open the group assignment grid for the scratch day. */
  private String assignmentGrid() throws Exception {
    String page = dayShiftsPage();
    int shiftCount = hiddenInt(page, "shiftCount");

    MockHttpServletRequestBuilder request = post("/DayShifts")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
      .param("date", String.valueOf(SCRATCH_DATE))
      .param("month", String.valueOf(SCRATCH_MONTH))
      .param("year", String.valueOf(SCRATCH_YEAR))
      .param("shiftCount", String.valueOf(shiftCount))
      .param("newName", "")
      .param("selectedShift", TEMPLATE)
      .param("doAssignments", "Assign Patrollers to Today's Shifts")
      .header("User-Agent", BROWSER)
      .session(directorSession());

    for (int row = 0; row < shiftCount; row++) {
      request = request
        .param("startTime_" + row, namedValue(page, "startTime_" + row))
        .param("endTime_" + row, namedValue(page, "endTime_" + row))
        .param("count_" + row, selectedOption(page, "count_" + row))
        .param("shift_" + row, selectedOption(page, "shift_" + row));
    }
    return mockMvc.perform(request).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }

  /** ...and becomes a real assignment row once the day is saved. */
  @Test
  void savingAFreshDayWritesTheTemplatePatrollerToTheCalendar() throws Exception {
    givenTemplateSlotZeroHolds(MEMBER_ID);
    assertEquals(0, savedRows().size(), "the scratch day must start with nothing saved");

    saveTheDay();

    List<String> rows = savedRows();
    assertFalse(rows.isEmpty(), "saving should have written the day's shifts");
    assertTrue(rows.get(0).contains("p0=" + MEMBER_ID),
               "the template patroller should now be a real assignment: " + rows);
  }

  /** An existing day is never overwritten by the template - real assignments win. */
  @Test
  void templatePatrollersDoNotOverwriteADayThatIsAlreadySaved() throws Exception {
    //save the day first with nobody on it, so its rows exist
    saveTheDay();
    assertFalse(savedRows().isEmpty(), "the day should now be saved");
    assertTrue(savedRows().get(0).contains("p0=0"), "and start with slot 0 empty");

    //only now pencil somebody into the template, then save again
    givenTemplateSlotZeroHolds(MEMBER_ID);
    saveTheDay();

    assertTrue(savedRows().get(0).contains("p0=0"),
               "an already-saved day keeps its own empty slot: " + savedRows());
  }

  /** Somebody removed from the roster must not reach the calendar as a broken id. */
  @Test
  void aTemplatePatrollerNoLongerOnTheRosterIsSkipped() throws Exception {
    givenTemplateSlotZeroHolds("999999999"); //no such member

    saveTheDay();

    assertTrue(savedRows().get(0).contains("p0=0"),
               "an unknown id should be dropped rather than written: " + savedRows());
  }

  /**
   * Replacing a day's format removes the day's shifts and everybody on them, then applies the
   * chosen template - including that template's own patrollers.
   */
  @Test
  void replacingTheShiftFormatRemovesThePatrollersAndAppliesTheNewTemplate() throws Exception {
    //a saved day with somebody on it
    saveTheDay();
    putPatrollerOnSavedRowZero();
    assertTrue(savedRows().get(0).contains("p0=" + MEMBER_ID),
               "the day should start with a patroller on it: " + savedRows());

    //give the replacement template a patroller of its own, then switch to it
    givenTemplateSlotZeroHolds(MEMBER_ID, REPLACEMENT_TEMPLATE);
    replaceFormatWith(REPLACEMENT_TEMPLATE);

    List<String> after = savedRows();
    assertFalse(after.isEmpty(), "the day should have the replacement template's rows");
    assertEquals(templateRowCount(REPLACEMENT_TEMPLATE), after.size(),
                 "the day should now have exactly the replacement template's row count: " + after);
    assertTrue(after.get(0).contains("p0=" + MEMBER_ID),
               "the replacement template's own patroller should be on the day: " + after);
  }

  /** Replacing with a name that matches no template empties the day. */
  @Test
  void replacingWithAnUnknownFormatClearsTheDay() throws Exception {
    saveTheDay();
    putPatrollerOnSavedRowZero();
    assertFalse(savedRows().isEmpty(), "the day should start saved");

    replaceFormatWith("--New Shift Style--");

    assertTrue(savedRows().isEmpty(),
               "the day should have been cleared: " + savedRows());
  }

  /** The dropdown must warn before throwing patrollers off a day. */
  @Test
  void theDropdownConfirmsBeforeRemovingPatrollers() throws Exception {
    String page = dayShiftsPage();

    assertTrue(page.contains("will REMOVE them"),
               "the format dropdown should warn that patrollers will be removed");
    assertTrue(page.contains("id=\"replaceShiftFormat\""),
               "and post the confirmed change rather than navigating");
  }

  /** Both pages, and both dropdowns, must show that a template carries patrollers. */
  @Test
  void bothPagesFlagATemplateThatHasPatrollers() throws Exception {
    givenTemplateSlotZeroHolds(MEMBER_ID);

    String dayShifts = dayShiftsPage();
    assertTrue(dayShifts.contains(">" + TEMPLATE + " (1 patrollers)"),
               "the DayShifts dropdown should flag the template");
    assertTrue(dayShifts.contains("This template has 1 patroller(s) assigned to it."),
               "and Change Today's Shift should say so beside the dropdown");

    String templates = editShiftTemplatesPage();
    assertTrue(templates.contains(">" + TEMPLATE + " (1 patrollers)"),
               "the EditShiftTemplates dropdown should flag the template");
    assertTrue(templates.contains("has 1 patroller(s) assigned"),
               "and the page should show it next to the buttons");
  }

  /** A template with nobody on it must not be flagged. */
  @Test
  void aTemplateWithNoPatrollersIsNotFlagged() throws Exception {
    String dayShifts = dayShiftsPage();

    //match the decorated option text, not the phrase anywhere - a code comment mentions it too
    assertFalse(dayShifts.contains(">" + TEMPLATE + " ("),
                "no template should be flagged when none have patrollers");
    assertFalse(dayShifts.contains("This template has"),
                "and no indicator should appear");
  }

  /**
   * The dropdown text now carries a count, so the JS must send the option's value. If it sent the
   * text, the template lookup would receive "Saturday (1 patrollers)" and match nothing.
   */
  @Test
  void theDropdownSendsTheValueRatherThanTheDecoratedText() throws Exception {
    givenTemplateSlotZeroHolds(MEMBER_ID);
    String page = dayShiftsPage();

    assertTrue(page.contains("list.options[idx].value"),
               "reloadPage must read the option value");
    assertFalse(page.contains("var msg = list.options[idx].text"),
                "reading the decorated text would break the template lookup");
  }

  private String editShiftTemplatesPage() throws Exception {
    return mockMvc.perform(get("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("selectedShift", TEMPLATE)
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }

  private void replaceFormatWith(String newFormat) throws Exception {
    mockMvc.perform(post("/DayShifts")
                      .param("resort", "Sample")
                      .param("ID", DIRECTOR_ID)
                      .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
                      .param("date", String.valueOf(SCRATCH_DATE))
                      .param("month", String.valueOf(SCRATCH_MONTH))
                      .param("year", String.valueOf(SCRATCH_YEAR))
                      .param("replaceShiftFormat", newFormat)
                      .header("User-Agent", BROWSER)
                      .session(directorSession()))
      .andExpect(status().is3xxRedirection());
  }

  private void putPatrollerOnSavedRowZero() {
    withPatrolData((log, patrolData) -> {
      for (Assignments assignment : patrolData.readSortedAssignments(SCRATCH_YEAR, SCRATCH_MONTH + 1)) {
        if (assignment.getDay() == SCRATCH_DATE) {
          assignment.insertAt("Sample", 0, MEMBER_ID);
          assignment.setExisted(true);
          patrolData.writeAssignment(assignment);
          return;
        }
      }
      throw new AssertionError("expected a saved row on the scratch day");
    });
  }

  private int templateRowCount(String templateName) {
    List<Integer> count = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      int rows = 0;
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(templateName)) {
          rows++;
        }
      }
      count.add(rows);
    });
    return count.get(0);
  }

  private void givenTemplateSlotZeroHolds(String patrollerId) {
    givenTemplateSlotZeroHolds(patrollerId, TEMPLATE);
  }

  private void givenTemplateSlotZeroHolds(String patrollerId, String templateName) {
    withPatrolData((log, patrolData) -> {
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(templateName) && shift.getEventIndex() == 0) {
          ShiftTemplatePatrollers.write(log, patrolData.getConnection(), shift.getEventName(),
                                       List.of(patrollerId));
          return;
        }
      }
      throw new AssertionError("the " + templateName + " template should have a row 0");
    });
  }

  /** Press "Save Shift Changes" with the rows exactly as rendered. */
  private void saveTheDay() throws Exception {
    String page = dayShiftsPage();
    int shiftCount = hiddenInt(page, "shiftCount");

    MockHttpServletRequestBuilder request = post("/DayShifts")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
      .param("date", String.valueOf(SCRATCH_DATE))
      .param("month", String.valueOf(SCRATCH_MONTH))
      .param("year", String.valueOf(SCRATCH_YEAR))
      .param("shiftCount", String.valueOf(shiftCount))
      .param("newName", "")
      .param("selectedShift", TEMPLATE)
      .param("saveShiftBtn", "Save Shift Changes")
      .header("User-Agent", BROWSER)
      .session(directorSession());

    for (int row = 0; row < shiftCount; row++) {
      request = request
        .param("startTime_" + row, namedValue(page, "startTime_" + row))
        .param("endTime_" + row, namedValue(page, "endTime_" + row))
        .param("count_" + row, selectedOption(page, "count_" + row))
        .param("shift_" + row, selectedOption(page, "shift_" + row));
    }
    mockMvc.perform(request).andExpect(status().is3xxRedirection());
  }

  private String dayShiftsPage() throws Exception {
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

  /** "pos=... p0=<id>" per saved row of the scratch day. */
  private List<String> savedRows() {
    List<String> rows = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      for (Assignments assignment : patrolData.readSortedAssignments(SCRATCH_YEAR, SCRATCH_MONTH + 1)) {
        if (assignment.getDay() == SCRATCH_DATE) {
          rows.add("pos=" + assignment.getDate() + " p0=" + assignment.getPosID(0));
        }
      }
    });
    return rows;
  }

  /** Whatever the group-assignment grid would show for the day's first slot. */
  private String firstSlotFieldValue(String page) {
    Matcher matcher = Pattern.compile("name=\"name0_0\"[^>]*value=\"([^\"]*)\"").matcher(page);
    return matcher.find() ? matcher.group(1) : "(no name0_0 field on the page)";
  }

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(TemplatePatrollerFlowTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
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

  private String namedValue(String page, String name) {
    Matcher matcher = Pattern.compile("name=\"" + name + "\"\\s+value=\"([^\"]*)\"").matcher(page);
    return matcher.find() ? matcher.group(1) : "";
  }

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
