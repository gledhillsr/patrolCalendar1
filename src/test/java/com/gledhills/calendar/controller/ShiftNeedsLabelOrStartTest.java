package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.ShiftDefinitions;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the two shift editors refuse to save: a row with neither a Label nor a Start Time, and an
 * End Time edited to something that is not a time.
 *
 * <p>A slot is drawn as its label, or failing that its start time, so a row with neither is a
 * blank line on the calendar for a shift somebody is meant to turn up for.
 *
 * <p>The browser refuses first and says which row; these are the server's backstop, since that
 * check can be posted around. <b>Deleting is deliberately exempt</b> - it is how a director gets
 * rid of a row that has ended up in this state, and blocking it would be a trap.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ShiftNeedsLabelOrStartTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  /** Saturday 15 March 2025, the scratch date DayShiftsControllerTest uses: no saved shifts. */
  private static final int SCRATCH_YEAR = 2025;
  private static final int SCRATCH_MONTH = 2;
  private static final int SCRATCH_DATE = 15;
  private static final int SCRATCH_DAY_OF_WEEK = 6;

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /**
   * Leave the scratch date as we found it, even when an assertion fails - the same cleanup
   * DayShiftsControllerTest does.
   *
   * <p>These tests POST saves and a delete, which persist. Without this the date stops being
   * template-derived and every later run sees one row fewer, which shows up as this class quietly
   * skipping its own delete test rather than failing.
   */
  @AfterEach
  void removeAnythingWeSaved() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger cleanupLog =
      new Logger(ShiftNeedsLabelOrStartTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
    PatrolData patrolData =
      new PatrolData("Sample", new SessionData(javaxRequest, null, cleanupLog), cleanupLog);
    try {
      for (Assignments assignment
             : patrolData.readSortedAssignments(SCRATCH_YEAR, SCRATCH_MONTH + 1)) {
        if (assignment.getDay() == SCRATCH_DATE) {
          patrolData.deleteAssignment(assignment);
        }
      }
    }
    finally {
      patrolData.close();
    }
  }

  private final Logger log = mock(Logger.class);

  @Test
  void theRuleItself() {
    assertTrue(StartAndLabel.of("", "", log).hasNeitherLabelNorStart());
    assertTrue(StartAndLabel.of(null, null, log).hasNeitherLabelNorStart());
    assertTrue(StartAndLabel.of("   ", "  ", log).hasNeitherLabelNorStart());

    assertFalse(StartAndLabel.of("8:00", "", log).hasNeitherLabelNorStart(), "a time is enough");
    assertFalse(StartAndLabel.of("", "TL", log).hasNeitherLabelNorStart(), "a label is enough");
    assertFalse(StartAndLabel.of("8:00", "TL", log).hasNeitherLabelNorStart(), "both is fine");
  }

  /**
   * A label typed into the Start box moves to the Label, so it satisfies the rule rather than
   * tripping it - the row is not empty, it was just filled in the wrong box.
   */
  @Test
  void aLabelTypedIntoTheStartBoxStillCounts() {
    assertFalse(StartAndLabel.of("mentee", "", log).hasNeitherLabelNorStart());
  }

  /**
   * A shift with no patroller slots is exempt: nobody works it, so it cannot leave a blank line on
   * the calendar for somebody to turn up to - which is the only thing the rule protects against. A
   * director laying a day out needs to be able to leave such a row empty.
   */
  @Test
  void aRowWithNoPatrollersMayBeBlank() {
    ShiftDefinitions empty = new ShiftDefinitions("Saturday_0", "", "", 0, 0, "", log);

    assertEquals(0, DayShiftsController.firstRowWithNeitherLabelNorStart(List.of(empty)),
                 "a shift nobody works may have neither a label nor a start time");
  }

  /** The exemption is per row: a real shift after an exempt one is still checked. */
  @Test
  void anExemptRowDoesNotExcuseTheRowsAfterIt() {
    ShiftDefinitions exempt = new ShiftDefinitions("Saturday_0", "", "", 0, 0, "", log);
    ShiftDefinitions offender = new ShiftDefinitions("Saturday_1", "", "", 2, 0, "", log);

    assertEquals(2, DayShiftsController.firstRowWithNeitherLabelNorStart(
      List.of(exempt, offender)), "the second row is a real shift and is still named");
  }

  /** One patroller is enough to need a label or a start time. */
  @Test
  void oneIsNotZero() {
    ShiftDefinitions one = new ShiftDefinitions("Saturday_0", "", "", 1, 0, "", log);

    assertEquals(1, DayShiftsController.firstRowWithNeitherLabelNorStart(List.of(one)));
  }

  @Test
  void theOffendingRowIsNamedByNumber() {
    ShiftDefinitions good = new ShiftDefinitions("Saturday_0", "8:00", "16:00", 1, 0, "", log);
    ShiftDefinitions labelled = new ShiftDefinitions("Saturday_1", "", "16:00", 1, 0, "TL", log);
    ShiftDefinitions empty = new ShiftDefinitions("Saturday_2", "", "16:00", 1, 0, "", log);

    assertEquals(0, DayShiftsController.firstRowWithNeitherLabelNorStart(List.of(good, labelled)));
    assertEquals(3, DayShiftsController.firstRowWithNeitherLabelNorStart(
      List.of(good, labelled, empty)), "1-based, and it is the third row");
    assertTrue(StartAndLabel.rowNeedsLabelOrStartMessage(3).contains("Shift 3"));
    assertTrue(StartAndLabel.rowNeedsLabelOrStartMessage(3).contains("Nothing was saved"));
  }

  /** DayShifts: the save is refused and the page says so, rather than writing a nameless row. */
  @Test
  void dayShiftsRefusesToSaveARowWithNeither() throws Exception {
    String page = dayShiftsPage();
    int shiftCount = hiddenInt(page, "shiftCount");
    org.junit.jupiter.api.Assumptions.assumeTrue(shiftCount > 0,
                                                 "needs template rows to post back");

    var request = post("/DayShifts")
      .param("resort", "Sample").param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
      .param("date", String.valueOf(SCRATCH_DATE))
      .param("month", String.valueOf(SCRATCH_MONTH))
      .param("year", String.valueOf(SCRATCH_YEAR))
      .param("shiftCount", String.valueOf(shiftCount))
      .param("saveShiftBtn", "Save Shift Changes")
      .header("User-Agent", BROWSER).session(directorSession());

    //row 0 posted back with both boxes empty; every other row echoed as rendered
    for (int row = 0; row < shiftCount; row++) {
      request = request
        .param("startTime_" + row, row == 0 ? "" : namedValue(page, "startTime_" + row))
        .param("label_" + row, row == 0 ? "" : namedValue(page, "label_" + row))
        .param("endTime_" + row, namedValue(page, "endTime_" + row))
        .param("count_" + row, "1")
        .param("shift_" + row, "Day Shift");
    }

    String answer = mockMvc.perform(request).andExpect(status().isOk())
                           .andReturn().getResponse().getContentAsString();

    assertTrue(answer.contains("has no Label and no Start Time"),
               "the page should say which row was refused");
    assertTrue(answer.contains("Shift 1"), "and name it: " + answer.contains("Shift 1"));
  }

  /** EditShiftTemplates: same rule, its own error channel. */
  @Test
  void editShiftTemplatesRefusesToSaveARowWithNeither() throws Exception {
    String page = templatesPage();
    int shiftCount = hiddenInt(page, "shiftCount");
    org.junit.jupiter.api.Assumptions.assumeTrue(shiftCount > 0,
                                                 "needs template rows to post back");

    var request = post("/EditShiftTemplates")
      .param("resort", "Sample").param("ID", DIRECTOR_ID)
      .param("shiftCount", String.valueOf(shiftCount))
      .param("eventName", namedValue(page, "eventName"))
      .param("saveChangesBtn", "Save Changes")
      .header("User-Agent", BROWSER).session(directorSession());

    for (int row = 0; row < shiftCount; row++) {
      request = request
        .param("startTime_" + row, row == 0 ? "" : namedValue(page, "startTime_" + row))
        .param("label_" + row, row == 0 ? "" : namedValue(page, "label_" + row))
        .param("endTime_" + row, namedValue(page, "endTime_" + row))
        .param("count_" + row, "1")
        .param("shift_" + row, "Day Shift");
    }

    String answer = mockMvc.perform(request).andExpect(status().isOk())
                           .andReturn().getResponse().getContentAsString();

    assertTrue(answer.contains("has no Label and no Start Time"),
               "the page should say which row was refused");
  }

  /**
   * Delete must still work on a row with neither, because that is the row you are trying to get
   * rid of. Blocking it would leave a director unable to save and unable to remove the thing
   * stopping them.
   */
  @Test
  void deleteIsNotBlockedByTheRule() throws Exception {
    String page = dayShiftsPage();
    int shiftCount = hiddenInt(page, "shiftCount");
    org.junit.jupiter.api.Assumptions.assumeTrue(shiftCount > 1,
                                                 "needs a row to delete and one to keep");

    var request = post("/DayShifts")
      .param("resort", "Sample").param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
      .param("date", String.valueOf(SCRATCH_DATE))
      .param("month", String.valueOf(SCRATCH_MONTH))
      .param("year", String.valueOf(SCRATCH_YEAR))
      .param("shiftCount", String.valueOf(shiftCount))
      .param("newName", "")
      .param("delete_0", "Delete")
      .header("User-Agent", BROWSER).session(directorSession());

    for (int row = 0; row < shiftCount; row++) {
      request = request
        .param("startTime_" + row, row == 0 ? "" : namedValue(page, "startTime_" + row))
        .param("label_" + row, row == 0 ? "" : namedValue(page, "label_" + row))
        .param("endTime_" + row, namedValue(page, "endTime_" + row))
        .param("count_" + row, "1")
        .param("shift_" + row, "Day Shift");
    }

    //a refusal renders the page with the error (200); a delete that went through redirects
    var result = mockMvc.perform(request).andReturn();

    assertEquals(302, result.getResponse().getStatus(),
                 "deleting the offending row should proceed and redirect, not be refused: "
                   + result.getResponse().getContentAsString());
    assertFalse(result.getResponse().getContentAsString().contains("has no Label and no Start Time"),
                "and it must not be refused by the rule about the row it is removing");
  }

  /**
   * The End Time rule, through the real route - which is the half a unit test cannot reach, since
   * it depends on the controller comparing against the <i>stored</i> value rather than something
   * posted.
   */
  @Test
  void anEndTimeEditedToANonTimeIsRefused() throws Exception {
    String page = dayShiftsPage();
    int shiftCount = hiddenInt(page, "shiftCount");
    org.junit.jupiter.api.Assumptions.assumeTrue(shiftCount > 0, "needs rows to post back");

    String answer = mockMvc.perform(rowPost(page, shiftCount, "teatime"))
                           .andExpect(status().isOk())
                           .andReturn().getResponse().getContentAsString();

    assertTrue(answer.contains("is not a time"), "the page should refuse it: ");
    assertTrue(answer.contains("teatime"), "and quote what was typed");
  }

  /** The same edit, to an actual time, goes through and is stored canonically. */
  @Test
  void anEndTimeEditedToATimeIsAccepted() throws Exception {
    String page = dayShiftsPage();
    int shiftCount = hiddenInt(page, "shiftCount");
    org.junit.jupiter.api.Assumptions.assumeTrue(shiftCount > 0, "needs rows to post back");

    //a refusal renders the page with the message (200); an accepted save redirects
    assertEquals(302, mockMvc.perform(rowPost(page, shiftCount, "4:30pm"))
                             .andReturn().getResponse().getStatus(),
                 "a real time should be accepted and the save should redirect");

    assertTrue(dayShiftsPage().contains("16:30"),
               "and 4:30pm should have been stored canonically as 16:30");
  }

  /** Every row echoed as rendered, with row 0's End Time replaced. */
  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rowPost(
    String page, int shiftCount, String newEndTime) {

    var request = post("/DayShifts")
      .param("resort", "Sample").param("ID", DIRECTOR_ID)
      .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
      .param("date", String.valueOf(SCRATCH_DATE))
      .param("month", String.valueOf(SCRATCH_MONTH))
      .param("year", String.valueOf(SCRATCH_YEAR))
      .param("shiftCount", String.valueOf(shiftCount))
      .param("newName", "")
      .param("saveShiftBtn", "Save Shift Changes")
      .header("User-Agent", BROWSER).session(directorSession());

    for (int row = 0; row < shiftCount; row++) {
      request = request
        .param("startTime_" + row, namedValue(page, "startTime_" + row))
        .param("label_" + row, namedValue(page, "label_" + row))
        .param("endTime_" + row, row == 0 ? newEndTime : namedValue(page, "endTime_" + row))
        .param("count_" + row, "1")
        .param("shift_" + row, "Day Shift");
    }
    return request;
  }

  /** Both editors load the shared check, so the browser refuses before the round trip. */
  @Test
  void bothEditorsCarryTheBrowserCheck() throws Exception {
    for (String page : List.of(dayShiftsPage(), templatesPage())) {
      assertTrue(page.contains("/js/shiftTime.js"), "the shared check should be loaded");
      assertTrue(page.contains("checkLabelOrStartTime(this)"),
                 "and run on submit: " + page.contains("checkLabelOrStartTime"));
      assertTrue(page.contains("checkEndTime(this)"), "End Time is checked as the box is left");
      //every yes/no question goes through the shared dialog, so no page mixes the two styles
      assertFalse(page.contains("confirm("),
                  "the browser's confirm() should have been replaced by the centred dialog");
      assertTrue(page.contains("shift-end-time"),
                 "and carries the class the submit check collects it by");
    }
  }

  private String dayShiftsPage() throws Exception {
    return mockMvc.perform(get("/DayShifts")
                             .param("resort", "Sample").param("ID", DIRECTOR_ID)
                             .param("dayOfWeek", String.valueOf(SCRATCH_DAY_OF_WEEK))
                             .param("date", String.valueOf(SCRATCH_DATE))
                             .param("month", String.valueOf(SCRATCH_MONTH))
                             .param("year", String.valueOf(SCRATCH_YEAR))
                             .header("User-Agent", BROWSER).session(directorSession()))
                  .andExpect(status().isOk())
                  .andReturn().getResponse().getContentAsString();
  }

  private String templatesPage() throws Exception {
    return mockMvc.perform(get("/EditShiftTemplates")
                             .param("resort", "Sample").param("ID", DIRECTOR_ID)
                             .header("User-Agent", BROWSER).session(directorSession()))
                  .andExpect(status().isOk())
                  .andReturn().getResponse().getContentAsString();
  }

  private static int hiddenInt(String page, String name) {
    String value = namedValue(page, name);
    return value.isEmpty() ? 0 : Integer.parseInt(value);
  }

  /** The value attribute of the named input, as the browser would echo it back. */
  private static String namedValue(String page, String name) {
    Matcher field = Pattern.compile("<input[^>]*name=\"" + Pattern.quote(name) + "\"[^>]*>")
                           .matcher(page);
    if (!field.find()) {
      return "";
    }
    Matcher value = Pattern.compile("value=\"([^\"]*)\"").matcher(field.group());
    return value.find() ? value.group(1) : "";
  }

  private MockHttpSession directorSession() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    return session;
  }
}
