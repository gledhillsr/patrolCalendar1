package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.utils.Logger;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shift being changed, shown at the top of ChangeShiftAssignments beside the day.
 *
 * <p>Reads "(dir) 8:00 - 16:30", or just the times when the shift has no label - empty brackets
 * would look like a value had gone missing rather than like a shift that never had a label.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ChangeShiftAssignmentsHeadingTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  private final Logger log = mock(Logger.class);

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  private Assignments shift(String label, String start, String end) {
    return new Assignments("2026-01-15_a",
                           new ShiftDefinitions("Thursday_0", start, end, 1,
                                                Assignments.DAY_TYPE, label, log), log);
  }

  @Test
  void aLabelIsBracketedBeforeTheTimes() {
    assertEquals("(dir) 8:00 - 16:30", shift("dir", "8:00", "16:30").describeLabelAndTimes());
  }

  @Test
  void withNoLabelThereAreNoBrackets() {
    assertEquals("8:00 - 16:30", shift("", "8:00", "16:30").describeLabelAndTimes());
  }

  /**
   * Half a time range keeps its dash, on the side the missing time would have been.
   *
   * <p>"18:00 -" is a shift with no end and "- 18:00" one with no start; without the dash the two
   * read identically, and both read like a complete shift.
   */
  @Test
  void onlyOneOfTheTwoTimesKeepsTheDash() {
    assertEquals("(dir) 8:00 -", shift("dir", "8:00", "").describeLabelAndTimes());
    assertEquals("(dir) - 16:30", shift("dir", "", "16:30").describeLabelAndTimes());
    assertEquals("8:00 -", shift("", "8:00", "").describeLabelAndTimes());
    assertEquals("- 16:30", shift("", "", "16:30").describeLabelAndTimes());
  }

  /** No times at all is the one case with no dash: the label alone, in brackets. */
  @Test
  void noTimesAtAllMeansNoDash() {
    assertEquals("(mentee)", shift("mentee", "", "").describeLabelAndTimes());
    assertEquals("(mentee)", shift("mentee", null, null).describeLabelAndTimes());
  }

  @Test
  void nothingAtAllSaysNothing() {
    assertEquals("", shift("", "", "").describeLabelAndTimes());
    assertEquals("", shift("", null, null).describeLabelAndTimes());
  }

  /**
   * pos comes off the URL and is 1-based, so a hand-typed one out of range must give no heading
   * rather than take the page down.
   */
  @Test
  void anOutOfRangePositionIsNotAnError() {
    List<Assignments> shifts = List.of(shift("dir", "8:00", "16:30"));

    assertEquals("(dir) 8:00 - 16:30",
                 ChangeShiftAssignmentsController.shiftHeading(shifts, 1, null));
    assertEquals("", ChangeShiftAssignmentsController.shiftHeading(shifts, 0, null));
    assertEquals("", ChangeShiftAssignmentsController.shiftHeading(shifts, 2, null));
    assertEquals("", ChangeShiftAssignmentsController.shiftHeading(shifts, -1, null));
    assertEquals("", ChangeShiftAssignmentsController.shiftHeading(null, 1, null));
  }

  /**
   * A day nobody has been scheduled on has no assignment rows, so the heading comes from the
   * day-of-week template instead - which is the day somebody is most likely to be on this page
   * for, since they are putting the first name in.
   */
  @Test
  void anUnscheduledDayFallsBackToItsTemplate() {
    ShiftDefinitions template =
      new ShiftDefinitions("Saturday_0", "8:00", "16:30", 2, Assignments.DAY_TYPE, "dir", log);

    assertEquals("(dir) 8:00 - 16:30",
                 ChangeShiftAssignmentsController.shiftHeading(List.of(), 1, template),
                 "with no assignments the template describes the slot");
    assertEquals("(dir) 8:00 - 16:30",
                 ChangeShiftAssignmentsController.shiftHeading(null, 1, template));
  }

  /** A saved assignment wins: it is what is actually scheduled, template or not. */
  @Test
  void aScheduledDayIgnoresTheTemplate() {
    ShiftDefinitions template =
      new ShiftDefinitions("Saturday_0", "6:00", "12:00", 2, Assignments.DAY_TYPE, "swing", log);

    assertEquals("(dir) 8:00 - 16:30",
                 ChangeShiftAssignmentsController.shiftHeading(
                   List.of(shift("dir", "8:00", "16:30")), 1, template));
  }

  /** The two row types must describe a shift identically, or the heading changes as a day fills up. */
  @Test
  void aTemplateRowAndAScheduledRowReadTheSame() {
    ShiftDefinitions template =
      new ShiftDefinitions("Saturday_0", "8:00", "16:30", 2, Assignments.DAY_TYPE, "dir", log);

    assertEquals(shift("dir", "8:00", "16:30").describeLabelAndTimes(),
                 template.describeLabelAndTimes());
  }

  /**
   * The page draws it, beside the day of the week.
   *
   * <p>5 November 2024 is a Sample day with saved assignments whose first shift runs 10:00-14:00
   * and carries no label - the plain form, with no brackets.
   */
  @Test
  void thePageShowsAnUnlabelledShiftBesideTheDay() throws Exception {
    String heading = headingCellOf(page(2, 5, 10, 2024, 1));

    assertTrue(heading.contains("10:00 - 14:00"),
               "expected the shift's times in the heading, got: " + heading);
    assertFalse(heading.contains("("), "an unlabelled shift gets no brackets: " + heading);
  }

  /**
   * 6 October 2017 has a shift whose label is HCS - one of the values the migration lifted out of
   * the Start column, which is why its start is empty and only the end time shows.
   */
  @Test
  void thePageBracketsALabel() throws Exception {
    String heading = headingCellOf(page(5, 6, 9, 2017, 3));

    assertTrue(heading.contains("(HCS)"),
               "expected the label in brackets, got: " + heading);
  }

  /**
   * 1 January 2026 is a Thursday with no saved assignments in Sample, but the Thursday template
   * has rows - so the heading comes from the template rather than being blank.
   */
  @Test
  void anEmptyDayStillShowsItsTemplateShift() throws Exception {
    String heading = headingCellOf(page(4, 1, 0, 2026, 1));

    assertTrue(heading.contains(":"),
               "an unscheduled day should still describe its template shift, got: " + heading);
  }

  /** The heading cell is one of three sized so the middle one is centred in the window. */
  @Test
  void theHeadingIsCentredInTheWindow() throws Exception {
    String page = page(2, 5, 10, 2024, 1);
    String header = page.substring(page.indexOf("<table"), page.indexOf("</tr>"));

    assertTrue(header.contains("width=\"30%\""), "the outer cells need equal widths: " + header);
    assertTrue(header.contains("width=\"40%\""), "and the heading cell the middle: " + header);
    assertEquals(2, header.split("width=\"30%\"", -1).length - 1,
                 "both outer cells, or the middle one is not centred: " + header);
  }

  private String page(int dayOfWeek, int date, int month, int year, int pos) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(get("/ChangeShiftAssignments")
                             .param("resort", "Sample").param("ID", DIRECTOR_ID)
                             .param("dayOfWeek", String.valueOf(dayOfWeek))
                             .param("date", String.valueOf(date))
                             .param("month", String.valueOf(month))
                             .param("year", String.valueOf(year))
                             .param("pos", String.valueOf(pos)).param("index", "0")
                             .header("User-Agent", BROWSER).session(session))
                  .andExpect(status().isOk())
                  .andReturn().getResponse().getContentAsString();
  }

  /** The centred cell only, so the assertions cannot pass on text from elsewhere on the page. */
  private String headingCellOf(String page) {
    int start = page.indexOf("align=\"CENTER\"");
    assertTrue(start > page.indexOf("Thursday") || start > 0,
               "the heading cell should exist, to the right of the day");
    return page.substring(start, page.indexOf("</td>", start));
  }
}
