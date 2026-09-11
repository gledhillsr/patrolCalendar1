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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating a template from scratch: pick "--New Shift Style--", type a name into "Event Name/Day
 * of Week", press New.
 *
 * The name is resolved by processFormSubmission, and the page has to be rendered from *that*, not
 * from the selectedShift the dropdown posted. When it was not, the new row was written to the
 * database and then the page showed neither it nor the typed name - the name came back as
 * "--New Shift Style--" and the row list was empty.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class NewShiftTemplateFlowTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String NEW_TEMPLATE = "ZZTestTemplate";
  private static final String NEW_SHIFT_STYLE = "--New Shift Style--";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

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
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().startsWith(NEW_TEMPLATE)) {
          patrolData.deleteShift(shift);
        }
      }
    });
  }

  /** The dropdown label is a menu entry, not a name - the box starts empty and says it is needed. */
  @Test
  void theNameFieldStartsEmptyForANewTemplate() throws Exception {
    String page = openTemplate(NEW_SHIFT_STYLE);

    assertEquals("", eventNameField(page),
                 "the name box should be blank, not prefilled with the dropdown label");
    assertTrue(page.contains("Name this shift template - required"), "with a placeholder saying so");
    assertTrue(page.contains("required before you can add a shift"), "and a visible hint");
  }

  /** New writes a row named after the field, so it stays disabled until the field is usable. */
  @Test
  void theNewButtonIsGatedOnTheName() throws Exception {
    String page = openTemplate(NEW_SHIFT_STYLE);

    assertTrue(page.contains("id=\"addShiftBtn\""), "the New button should be addressable");
    assertTrue(page.contains("function syncNewButton"), "and the page should carry the gate");
    assertTrue(page.contains("function isUsableTemplateName"), "and the rule it gates on");
  }

  /** The gate is in the browser, so the save has to refuse it too. */
  @Test
  void pressingNewWithNoNameIsRefused() throws Exception {
    // counted as a change, not an absolute: the sample data already carries one empty-named row
    // ("_0"), the artifact this guard exists to stop being created again
    int before = savedRowsFor("");

    String page = pressNew(NEW_SHIFT_STYLE, "", 0);

    assertTrue(page.contains("must have a valid value"),
               "a blank name should be refused: " + page);
    assertEquals(before, savedRowsFor(""), "and must not write another unnamed template");
  }

  /** Typing the menu label by hand is no more a name than leaving it prefilled was. */
  @Test
  void pressingNewNamedAfterTheMenuLabelIsRefused() throws Exception {
    String page = pressNew(NEW_SHIFT_STYLE, NEW_SHIFT_STYLE, 0);

    assertTrue(page.contains("must have a valid value"),
               "the dropdown label should be refused as a name: " + page);
    assertEquals(0, savedRowsFor(NEW_SHIFT_STYLE), "and must not be written");
  }

  /**
   * Not just New: Save Changes with the name blank used to rename the template's rows to "_0",
   * "_1" and so on, because createShiftName only appends the position to whatever it is given.
   */
  @Test
  void savingWithNoNameIsRefusedAndChangesNothing() throws Exception {
    pressNew(NEW_SHIFT_STYLE, NEW_TEMPLATE, 0);          //a scratch template to save over
    String before = String.join("|", rowNamesFor(NEW_TEMPLATE));
    assertEquals(NEW_TEMPLATE + "_0", before, "the scratch template should have one row");

    //a real save: the row fields are what get rewritten, so the name has to be posted with them
    String page = saveRow(NEW_TEMPLATE, "", "8:00", "16:00");

    assertTrue(page.contains("must have a valid value"),
               "the page should say the name field is required: " + page);
    assertEquals(before, String.join("|", rowNamesFor(NEW_TEMPLATE)),
                 "and the row must not have been renamed to \"_0\"");
  }

  /** The browser stops it first, with a message box naming the field. */
  @Test
  void theBrowserBlocksASaveWithNoName() throws Exception {
    String page = openTemplate("Saturday");

    assertTrue(page.contains("must have a valid value to continue"),
               "the alert should name the requirement");
    assertTrue(page.contains("return CheckEventName()"),
               "and every submit of the template form should go through it");
  }

  @Test
  void theTypedNameSurvivesPressingNew() throws Exception {
    String page = pressNew(NEW_SHIFT_STYLE, NEW_TEMPLATE, 0);

    assertEquals(NEW_TEMPLATE, eventNameField(page),
                 "the name typed into Event Name/Day of Week should still be there");
  }

  @Test
  void theNewRowIsShownUnderTheTypedName() throws Exception {
    String page = pressNew(NEW_SHIFT_STYLE, NEW_TEMPLATE, 0);

    assertEquals(1, rowCount(page), "pressing New should show a row to fill in");
    // lookaheads so the assertion does not depend on which attribute Thymeleaf emits first
    assertTrue(Pattern.compile("<option(?=[^>]*value=\"" + NEW_TEMPLATE + "\")(?=[^>]*selected)")
                 .matcher(page).find(),
               "the dropdown should be on the new template: " + page);
  }

  @Test
  void theNewTemplateIsPersisted() throws Exception {
    pressNew(NEW_SHIFT_STYLE, NEW_TEMPLATE, 0);

    assertEquals(1, savedRowsFor(NEW_TEMPLATE),
                 "the row should have been written under the typed name");
  }

  /** A second New adds a second row rather than starting over. */
  @Test
  void pressingNewAgainAddsAnotherRow() throws Exception {
    pressNew(NEW_SHIFT_STYLE, NEW_TEMPLATE, 0);
    String page = pressNew(NEW_TEMPLATE, NEW_TEMPLATE, 1);

    assertEquals(NEW_TEMPLATE, eventNameField(page), "the name should still be there");
    assertEquals(2, rowCount(page), "the template should now have two rows");
    assertEquals(2, savedRowsFor(NEW_TEMPLATE), "and both should be saved");
  }

  /**
   * A rejected name must leave the director on the template they came from, not adopt the bad
   * name - the guard that made adopting the processed name safe in the first place.
   */
  @Test
  void aRejectedNameStaysOnTheOriginalTemplate() throws Exception {
    String page = pressNew("Saturday", "_BadName", 0);

    assertTrue(page.contains("cannot start with an underscore"), "the name should be rejected");
    assertEquals("Saturday", eventNameField(page),
                 "and the page should stay on the template it came from");
    assertEquals(0, savedRowsFor("_BadName"), "nothing should have been written under the bad name");
  }

  private String openTemplate(String selectedShift) throws Exception {
    return mockMvc.perform(get("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("selectedShift", selectedShift)
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  /** Press "Save Changes" with one row's fields filled in and the name field as given. */
  private String saveRow(String selectedShift, String eventName, String start, String end)
      throws Exception {
    return mockMvc.perform(post("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("selectedShift", selectedShift)
                             .param("eventName", eventName)
                             .param("shiftCount", "1")
                             .param("startTime_0", start)
                             .param("endTime_0", end)
                             .param("count_0", "8")
                             .param("shift_0", "Day")
                             .param("SaveChangesBtn", "Save Changes")
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  /** Every stored row name for a template, in order - "Saturday_0", "Saturday_1", ... */
  private List<String> rowNamesFor(String templateName) {
    List<String> names = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(templateName)) {
          names.add(shift.getEventName());
        }
      }
    });
    return names;
  }

  private String pressNew(String selectedShift, String eventName, int shiftCount) throws Exception {
    return mockMvc.perform(post("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("selectedShift", selectedShift)
                             .param("eventName", eventName)
                             .param("shiftCount", String.valueOf(shiftCount))
                             .param("addShift", "New")
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  /** Whatever the "Event Name/Day of Week" box would show. */
  private String eventNameField(String page) {
    Matcher matcher = Pattern.compile("name=\"eventName\"[^>]*?value=\"([^\"]*)\"", Pattern.DOTALL)
      .matcher(page);
    return matcher.find() ? matcher.group(1) : "(no eventName field)";
  }

  private int rowCount(String page) {
    Matcher matcher = Pattern.compile("name=\"startTime_\\d+\"").matcher(page);
    int rows = 0;
    while (matcher.find()) {
      rows++;
    }
    return rows;
  }

  private int savedRowsFor(String templateName) {
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

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(NewShiftTemplateFlowTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
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
