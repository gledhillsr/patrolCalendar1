package com.gledhills.calendar.controller;

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

  /** Written by one test and removed after it; the inner underscore is the point of it. */
  private static final String UNDERSCORE_TEMPLATE = "ZZLate_Night";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /** The scratch template is written to the real Sample data, so it does not outlive the test. */
  @AfterEach
  void removeScratchTemplate() {
    withPatrolData(patrolData -> {
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(UNDERSCORE_TEMPLATE)) {
          patrolData.deleteShift(shift);
        }
      }
    });
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

  /**
   * Reordering a template must carry each row's Label with it.
   *
   * <p>It did not: the swap rebuilt both rows through the ShiftDefinitions constructor that leaves
   * the label blank, so the arrows emptied the Label of the two rows they touched - the same bug
   * DayShifts had, in the other shift editor. The swap test above could not see it, because the
   * row summary it compares did not include the label; it does now.
   */
  @Test
  void movingARowCarriesItsLabelWithIt() throws Exception {
    List<String> before = templateRows(MULTI_ROW_TEMPLATE);
    assertTrue(before.size() >= 2, "need at least two rows: " + before);
    String originalLabel = labelOfRow(0);

    try {
      setLabelOnRow(0, "TL");
      clickArrow("moveDown_0", before.size());

      assertEquals("TL", labelOfRow(1),
                   "the Label must travel with the row: " + templateRows(MULTI_ROW_TEMPLATE));

      clickArrow("moveUp_1", before.size()); //put the order back
      setLabelOnRow(0, originalLabel);
    }
    finally {
      setLabelOnRow(0, originalLabel);
    }

    assertEquals(before, templateRows(MULTI_ROW_TEMPLATE),
                 "the template should be restored to its original order and labels");
  }

  private String labelOfRow(int row) {
    String summary = templateRows(MULTI_ROW_TEMPLATE).get(row);
    return summary.substring(summary.indexOf("label=") + "label=".length());
  }

  /** Put a label on one row of the template, the way a director typing one would leave it. */
  private void setLabelOnRow(int row, String label) {
    withPatrolData(patrolData -> {
      int seen = 0;
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(MULTI_ROW_TEMPLATE)) {
          if (seen == row) {
            //readShiftDefinitions hands back rows that exist, so writeShift updates rather
            //than inserting - setExists is the flag it reads
            shift.setLabel(label);
            shift.setExists(true);
            patrolData.writeShift(shift);
            return;
          }
          seen++;
        }
      }
    });
  }

  /** The button is top-left and submits the template form, so pending row edits save first. */
  @Test
  void theAssignPatrollersButtonSubmitsTheTemplateForm() throws Exception {
    String page = page(MULTI_ROW_TEMPLATE);

    assertTrue(page.contains("id=\"templateForm\""), "the edit form needs an id to target");
    Matcher button = Pattern.compile("<input[^>]*name=\"assignPatrollers\"[^>]*>").matcher(page);
    assertTrue(button.find(), "the page should offer the assign button");
    assertTrue(button.group().contains("form=\"templateForm\""),
               "the button must submit the template form: " + button.group());
    assertTrue(page.indexOf("name=\"assignPatrollers\"") < page.indexOf("Change/Edit Shift Template"),
               "the button belongs above the frame, as on DayShifts");
  }

  /** The grid offers one line per slot, across every row of the template. */
  @Test
  void theGridOffersOneLinePerTemplateSlot() throws Exception {
    String grid = assignGrid();

    assertTrue(grid.contains("Assign Patrollers to " + MULTI_ROW_TEMPLATE),
               "the grid should name the template it is editing");

    int expectedSlots = 0;
    for (String row : templateRows(MULTI_ROW_TEMPLATE)) {
      expectedSlots += Integer.parseInt(row.split(" ")[3]); //"name start end count type"
    }
    //\s before name= so the Insert/Clear buttons' data-fieldname does not also match
    Matcher slots = Pattern.compile("\\sname=\"slot_\\d+_\\d+\"").matcher(grid);
    int found = 0;
    while (slots.find()) {
      found++;
    }
    assertEquals(expectedSlots, found,
                 "one field per slot, summed over the template's Patroller Counts");
  }

  /**
   * The whole point: pencil a patroller into a template slot, and it is still there next time.
   * Cleared afterwards so the template is left as found.
   */
  @Test
  void assigningAPatrollerToATemplateSlotPersists() throws Exception {
    String memberName = firstRosterName();

    try {
      saveGridWithSlotZero(memberName);

      assertTrue(assignGrid().contains("value=\"" + memberName + "\""),
                 "the grid should show the patroller after saving");
    }
    finally {
      saveGridWithSlotZero(""); //clear it again
    }

    assertFalse(assignGrid().contains("value=\"" + memberName + "\""),
                "clearing the slot should remove the patroller");
  }

  /** Post the grid with one name in slot 0 of row 0, every other slot left empty. */
  private void saveGridWithSlotZero(String memberName) throws Exception {
    String grid = assignGrid();
    MockHttpServletRequestBuilder request = post("/EditShiftTemplates")
      .param("resort", "Sample")
      .param("ID", DIRECTOR_ID)
      .param("selectedShift", MULTI_ROW_TEMPLATE)
      .param("eventName", MULTI_ROW_TEMPLATE)
      .param("saveTemplatePatrollersBtn", "yes")
      .header("User-Agent", BROWSER)
      .session(directorSession());

    Matcher slots = Pattern.compile("\\sname=\"(slot_\\d+_\\d+)\"").matcher(grid);
    boolean first = true;
    while (slots.find()) {
      request = request.param(slots.group(1), first ? memberName : "");
      first = false;
    }
    mockMvc.perform(request).andExpect(status().isOk());
  }

  private String assignGrid() throws Exception {
    return mockMvc.perform(post("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("selectedShift", MULTI_ROW_TEMPLATE)
                             .param("eventName", MULTI_ROW_TEMPLATE)
                             .param("assignPatrollers", "Assign Patrollers to this template")
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }

  /** The first name in the grid's pick list. */
  private String firstRosterName() throws Exception {
    Matcher option = Pattern.compile("<option>([^<]+)</option>").matcher(assignGrid());
    assertTrue(option.find(), "the grid should offer roster names to pick from");
    return option.group(1);
  }

  /**
   * No existing template name may <b>start</b> with an underscore.
   *
   * <p>That is the shape an unnamed row has ("_0"), and {@code ShiftDefinitions.UNNAMED_TEMPLATE}
   * finds those with {@code LIKE '\_%'} so the Site Wide Configuration job can delete them. A
   * template genuinely called "_old" would be swept away with the artifacts.
   *
   * <p>An underscore <em>inside</em> a name is deliberately not checked: it is allowed, everything
   * splits on the last underscore, and Brighton has run a template called "Saturday_old" for years.
   */
  @Test
  void noExistingTemplateNameStartsWithAnUnderscore() {
    List<String> offenders = new ArrayList<>();
    withPatrolData(patrolData -> {
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().startsWith("_")) {
          offenders.add(shift.getEventName());
        }
      }
    });

    assertTrue(offenders.isEmpty(),
               "these template names start with an underscore and the clean-up job would delete "
                 + "them: " + offenders);
  }

  /** The browser check can be bypassed, so the server must refuse a leading underscore too. */
  @Test
  void theServerRejectsATemplateNameStartingWithAnUnderscore() throws Exception {
    String before = String.join("|", templateRows(MULTI_ROW_TEMPLATE));

    String page = mockMvc.perform(post("/EditShiftTemplates")
                                    .param("resort", "Sample")
                                    .param("ID", DIRECTOR_ID)
                                    .param("selectedShift", MULTI_ROW_TEMPLATE)
                                    .param("eventName", "_BadName")
                                    .param("shiftCount", "0")
                                    .header("User-Agent", BROWSER)
                                    .session(directorSession()))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();

    //"was not saved" is the server's wording. The browser's copy of this message is inline in the
    //page's JavaScript on EVERY render, so asserting on the shared part of the sentence passes
    //whether or not the name was actually refused - which is the whole thing under test.
    assertTrue(page.contains("cannot start with an underscore (&#39;_&#39;). &#39;_BadName&#39; was not saved")
               || page.contains("was not saved"),
               "the page should say why the name was refused");
    assertEquals(before, String.join("|", templateRows(MULTI_ROW_TEMPLATE)),
                 "and nothing should have been written");
  }

  /**
   * An underscore inside the name is accepted, and the row still parses back to the right template.
   *
   * <p>This is the behaviour the old blanket ban was costing. The suffix is read with
   * {@code lastIndexOf}, so "Late_Night_2" is row 2 of "Late_Night" - and getting that wrong would
   * file the row under a template nobody asked for, which is why it is asserted rather than assumed.
   */
  @Test
  void anUnderscoreInsideTheNameIsAllowed() throws Exception {
    String page = mockMvc.perform(post("/EditShiftTemplates")
                                    .param("resort", "Sample")
                                    .param("ID", DIRECTOR_ID)
                                    .param("selectedShift", PatrolData.NEW_SHIFT_STYLE)
                                    .param("eventName", UNDERSCORE_TEMPLATE)
                                    .param("addShift", "New")
                                    .param("shiftCount", "0")
                                    .header("User-Agent", BROWSER)
                                    .session(directorSession()))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();

    //again, not the shared sentence: "was not saved" is only ever the server refusing it
    assertFalse(page.contains("was not saved"),
                "an inner underscore should not be refused: " + UNDERSCORE_TEMPLATE);

    //templateRows filters on parsedEventName(), so a row coming back at all is the round trip:
    //the name was stored as "ZZLate_Night_0" and read back as row 0 of "ZZLate_Night", not as
    //row 0 of "ZZLate" or some other template the split invented.
    List<String> rows = templateRows(UNDERSCORE_TEMPLATE);
    assertEquals(1, rows.size(), "exactly one row should have been written: " + rows);
    assertTrue(rows.get(0).startsWith(UNDERSCORE_TEMPLATE + "_0 "),
               "stored with its position appended, and filed under the full name: " + rows.get(0));
  }

  /** The name check runs in the browser as well, so a typo is caught before a round trip. */
  @Test
  void theBrowserAlsoChecksForALeadingUnderscore() throws Exception {
    String page = page(MULTI_ROW_TEMPLATE);

    // the rule moved into isUsableTemplateName, which the New button gate shares
    assertTrue(page.contains("indexOf(\"_\") == 0") || page.contains("indexOf(\"_\") != 0"),
               "the browser should reject a LEADING underscore client-side too");
    assertTrue(page.contains("function CheckEventName"),
               "and it should still gate the form's submit");
  }

  private void withPatrolData(java.util.function.Consumer<PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(EditShiftTemplatesControllerTest.class, javaxRequest, "GET", "Sample",
                            Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(patrolData);
    }
    finally {
      patrolData.close();
    }
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
        //a browser posts the Label with every row; leaving it out blanks every label on the way
        //in, which looks exactly like the bug this file is here to catch
        .param("label_" + row, namedValue(page, "label_" + row))
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
                     + " " + shift.getCount() + " " + shift.getType()
                     + " label=" + shift.getLabel());
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
