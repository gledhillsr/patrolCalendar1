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
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nobody may hold two slots on the same shift, on either grid that assigns patrollers in bulk.
 *
 * The browser stops it before the name goes in; these cover the server side, which has the last
 * word because that check can be bypassed. A shift is identified by its times rather than its row,
 * since a shift needing more than 10 patrollers is entered as several rows sharing a start and end.
 *
 * Works on Saturday 15 March 2025 - nothing saved there, and outside every month the calendar
 * goldens are recorded for - and clears the day and the template patrollers afterwards.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class NoDuplicateOnAShiftTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String MEMBER_ID = "200401";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  private static final String TEMPLATE = "Saturday";
  private static final int YEAR = 2025;
  private static final int MONTH = 2; //0 based: March
  private static final int DATE = 15;
  private static final int DAY_OF_WEEK = 6;

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @AfterEach
  void cleanUp() {
    withPatrolData((log, patrolData) -> {
      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH + 1)) {
        if (assignment.getDay() == DATE) {
          patrolData.deleteAssignment(assignment);
        }
      }
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(TEMPLATE)) {
          ShiftTemplatePatrollers.deleteShift(log, patrolData.getConnection(), shift.getEventName());
        }
      }
    });
  }

  // ---------- the template grid, on EditShiftTemplates ----------

  @Test
  void aTemplateShiftKeepsOnlyTheFirstOfTwoIdenticalNames() throws Exception {
    String name = memberName();

    saveTemplateSlots(Map.of("slot_0_0", name, "slot_0_1", name));

    assertEquals(List.of(MEMBER_ID), templateRowZeroIds(),
                 "the second slot on the same shift should have been dropped");
  }

  @Test
  void aTemplatePatrollerMayHoldTwoShiftsAtDifferentTimes() throws Exception {
    String name = memberName();
    assertTrue(templateRowsDiffer(), "rows 0 and 1 of " + TEMPLATE + " should be different shifts");

    saveTemplateSlots(Map.of("slot_0_0", name, "slot_1_0", name));

    assertEquals(List.of(MEMBER_ID), templateRowZeroIds(), "row 0 keeps them");
    assertTrue(templateIdsFor(1).contains(MEMBER_ID),
               "and a different shift may hold them too: " + templateIdsFor(1));
  }

  /** The grid has to carry the grouping the browser check reads. */
  @Test
  void theTemplateGridTagsEverySlotWithItsShift() throws Exception {
    String grid = templateAssignGrid();

    assertTrue(grid.contains("data-shift="), "slots should carry data-shift: " + grid);
    assertTrue(grid.contains("alreadyOnShift"), "and the page should carry the browser check");
  }

  @Test
  void theTemplateGridOffersClearAll() throws Exception {
    String grid = templateAssignGrid();

    assertTrue(grid.contains("value=\"Clear all\""), "the grid should have a Clear all button");
    assertTrue(grid.contains("clearAllNames(this.form)"), "wired to the clear-all handler");
    assertTrue(grid.contains("function clearAllNames"), "and the handler should be on the page");
  }

  // ---------- the day grid, on DayShifts ----------

  @Test
  void aDayShiftKeepsOnlyTheFirstOfTwoIdenticalNames() throws Exception {
    String name = memberName();

    saveDayAssignments(Map.of("name0_0", name, "name0_1", name));

    assertEquals(List.of(MEMBER_ID), dayRowIds(0),
                 "the second slot on the same shift should have been dropped");
  }

  @Test
  void aPatrollerMayWorkTwoShiftsOnTheSameDay() throws Exception {
    String name = memberName();

    saveDayAssignments(Map.of("name0_0", name, "name1_0", name));

    assertTrue(dayRowIds(0).contains(MEMBER_ID), "the morning shift keeps them");
    assertTrue(dayRowIds(1).contains(MEMBER_ID),
               "and the afternoon shift may hold them too: " + dayRowIds(1));
  }

  @Test
  void theDayGridTagsEverySlotWithItsShift() throws Exception {
    String grid = dayAssignGrid();

    assertTrue(grid.contains("data-shift="), "slots should carry data-shift");
    assertTrue(grid.contains("alreadyOnShift"), "and the page should carry the browser check");
  }

  @Test
  void theDayGridOffersClearAll() throws Exception {
    String grid = dayAssignGrid();

    assertTrue(grid.contains("value=\"Clear all\""), "the grid should have a Clear all button");
    assertTrue(grid.contains("clearAllNames(this.form)"), "wired to the clear-all handler");
    assertTrue(grid.contains("function clearAllNames"), "and the handler should be on the page");
  }

  // ---------- driving the two grids ----------

  /** POST the template grid with the given slot fields, everything else blank. */
  private void saveTemplateSlots(Map<String, String> slots) throws Exception {
    MockHttpServletRequestBuilder request = post("/EditShiftTemplates")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("selectedShift", TEMPLATE)
      .param("eventName", TEMPLATE)
      .param("saveTemplatePatrollersBtn", "yes")
      .header("User-Agent", BROWSER)
      .session(directorSession());

    for (Map.Entry<String, String> slot : slots.entrySet()) {
      request = request.param(slot.getKey(), slot.getValue());
    }
    mockMvc.perform(request).andExpect(status().isOk());
  }

  /** Save the day first so its rows exist, then POST the assignment grid. */
  private void saveDayAssignments(Map<String, String> names) throws Exception {
    saveTheDay();

    MockHttpServletRequestBuilder request = post("/DayShifts")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(DAY_OF_WEEK))
      .param("date", String.valueOf(DATE))
      .param("month", String.valueOf(MONTH))
      .param("year", String.valueOf(YEAR))
      .param("saveAssignmentBtn", "yes")
      .header("User-Agent", BROWSER)
      .session(directorSession());

    for (Map.Entry<String, String> name : names.entrySet()) {
      request = request.param(name.getKey(), name.getValue());
    }
    mockMvc.perform(request).andExpect(status().is3xxRedirection());
  }

  private String templateAssignGrid() throws Exception {
    return mockMvc.perform(post("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("selectedShift", TEMPLATE)
                             .param("eventName", TEMPLATE)
                             .param("assignPatrollers", "Assign Patrollers to this template")
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  private String dayAssignGrid() throws Exception {
    String page = dayShiftsPage();
    int shiftCount = hiddenInt(page, "shiftCount");

    MockHttpServletRequestBuilder request = post("/DayShifts")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(DAY_OF_WEEK))
      .param("date", String.valueOf(DATE))
      .param("month", String.valueOf(MONTH))
      .param("year", String.valueOf(YEAR))
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

  private void saveTheDay() throws Exception {
    String page = dayShiftsPage();
    int shiftCount = hiddenInt(page, "shiftCount");

    MockHttpServletRequestBuilder request = post("/DayShifts")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(DAY_OF_WEEK))
      .param("date", String.valueOf(DATE))
      .param("month", String.valueOf(MONTH))
      .param("year", String.valueOf(YEAR))
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
                             .param("dayOfWeek", String.valueOf(DAY_OF_WEEK))
                             .param("date", String.valueOf(DATE))
                             .param("month", String.valueOf(MONTH))
                             .param("year", String.valueOf(YEAR))
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  // ---------- reading back what was stored ----------

  /** The non-empty patroller ids on one row of the saved day. */
  private List<String> dayRowIds(int row) {
    List<String> ids = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      List<Assignments> rows = new ArrayList<>();
      for (Assignments assignment : patrolData.readSortedAssignments(YEAR, MONTH + 1)) {
        if (assignment.getDay() == DATE) {
          rows.add(assignment);
        }
      }
      assertTrue(row < rows.size(), "the day should have a row " + row + ", found " + rows.size());

      Assignments assignment = rows.get(row);
      for (int slot = 0; slot < assignment.getCount(); slot++) {
        String id = assignment.getPosID(slot);
        if (id != null && !id.equals("0") && !id.isEmpty()) {
          ids.add(id);
        }
      }
    });
    return ids;
  }

  private List<String> templateRowZeroIds() {
    return templateIdsFor(0);
  }

  /** The non-empty patroller ids stored against one row of the template. */
  private List<String> templateIdsFor(int row) {
    List<String> ids = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      Map<String, List<String>> all =
        ShiftTemplatePatrollers.readAll(log, patrolData.getConnection());
      List<String> slots =
        all.getOrDefault(ShiftDefinitions.createShiftName(TEMPLATE, row), List.of());

      for (String id : slots) {
        if (!ShiftTemplatePatrollers.isEmpty(id)) {
          ids.add(id);
        }
      }
    });
    return ids;
  }

  /** Rows 0 and 1 of the template must be different shifts for the "two shifts" test to mean it. */
  private boolean templateRowsDiffer() {
    List<String> times = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(TEMPLATE) && shift.getEventIndex() < 2) {
          times.add(shift.getStartString() + " - " + shift.getEndString());
        }
      }
    });
    return times.size() == 2 && !times.get(0).equals(times.get(1));
  }

  private String memberName() {
    List<String> name = new ArrayList<>();
    withPatrolData((log, patrolData) ->
                     name.add(patrolData.getMemberByID(MEMBER_ID).getFullName_lastNameFirst()));
    return name.get(0);
  }

  // ---------- plumbing ----------

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

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(NoDuplicateOnAShiftTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
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
}
