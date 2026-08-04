package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.CalendarMonth;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.DirectorSettings;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the day-cell decisions, with the database stubbed out.
 *
 * These cover the branches the Sample data cannot reach - above all a day comment carrying an XSS
 * payload, which must come back escaped because month-calendar.html renders the comment unescaped.
 */
class MonthCalendarServiceTest {

  private static final String DIRECTOR_ID = "123456";
  private static final int YEAR = 2026;
  private static final int MONTH = 0; //January

  private MonthCalendarService service;
  private PatrolData patrolData;
  private Logger log;

  @BeforeEach
  void setUp() {
    service = new MonthCalendarService();
    log = mock(Logger.class);
    patrolData = mock(PatrolData.class);

    DirectorSettings settings = mock(DirectorSettings.class);
    when(settings.getStartMonth()).thenReturn(11);
    when(settings.getStartDay()).thenReturn(1);
    when(settings.getEndMonth()).thenReturn(4);
    when(settings.getEndDay()).thenReturn(30);
    when(settings.getUseBlackOut()).thenReturn(false);
    when(settings.getDirectorsOnlyChange()).thenReturn(false);

    when(patrolData.readDirectorSettings()).thenReturn(settings);
    when(patrolData.resetRoster()).thenReturn(null);
    when(patrolData.nextMember(anyString(), any())).thenReturn(null); //empty roster
    when(patrolData.readNewIndividualAssignments(anyInt(), anyInt(), anyInt())).thenReturn(null);
    when(patrolData.readShiftDefinitions()).thenReturn(new ArrayList<>());
    when(patrolData.readSortedAssignments(anyInt(), anyInt())).thenReturn(new ArrayList<>());
  }

  @Test
  void escapesAScriptTagInADayComment() {
    givenDayComment(7, "<script>alert('pwned')</script>");

    CalendarMonth.Day day = dayOfMonth(7);

    assertFalse(day.eventNameHtml().contains("<script"),
                "the comment is rendered unescaped, so a script tag must not survive: "
                  + day.eventNameHtml());
    assertTrue(day.eventNameHtml().contains("&lt;script&gt;"),
               "it should show as literal text: " + day.eventNameHtml());
  }

  @Test
  void keepsBasicFormattingInADayComment() {
    givenDayComment(7, "<b>Powder</b> day<br>all lifts");

    assertEquals("<b>Powder</b> day<br>all lifts", dayOfMonth(7).eventNameHtml());
  }

  @Test
  void aCommentedDayIsHighlighted() {
    givenDayComment(7, "bus tour");

    assertTrue(dayOfMonth(7).highlighted(), "a day with a comment gets the accent background");
    assertFalse(dayOfMonth(8).highlighted(), "a day without one does not");
  }

  /** "Closed" in the comment is the flag itself, not a comment to repeat under the date. */
  @Test
  void closedIsAStateNotAComment() {
    givenDayComment(7, "Closed");

    CalendarMonth.Day day = dayOfMonth(7);
    assertEquals("CLOSED", day.closedLabel());
    assertEquals("", day.eventNameHtml());
    assertFalse(day.highlighted());
  }

  @Test
  void directorsGetADateLinkAndOthersDoNot() {
    givenDayComment(7, "bus tour");

    assertNotNull(dayOfMonth(7).dateLink(), "the support login is treated as a director");

    CalendarMonth asMember = service.build("Sample", "0", String.valueOf(YEAR), 0,
                                           "999", null, patrolData, log);
    assertTrue(asMember.weeks().stream().flatMap(week -> week.days().stream())
                 .filter(day -> !day.isBlank())
                 .allMatch(day -> day.dateLink() == null),
               "a non-director gets no date links");
  }

  @Test
  void weeksAreAlwaysSevenDaysAndCoverTheWholeMonth() {
    CalendarMonth january = build();

    assertTrue(january.weeks().stream().allMatch(week -> week.days().size() == 7),
               "every row must hold seven cells");
    assertEquals(31, january.weeks().stream()
      .flatMap(week -> week.days().stream())
      .filter(day -> !day.isBlank())
      .count(), "January has 31 days");
  }

  @Test
  void outOfSeasonDaysAreClosedWhenATemplateExists() {
    //Sample's season is 01-11 to 30-04, so July is outside it
    when(patrolData.readShiftDefinitions()).thenReturn(shiftTemplateForEveryDay());

    CalendarMonth july = service.build("Sample", "6", String.valueOf(YEAR), 0,
                                       DIRECTOR_ID, DIRECTOR_ID, patrolData, log);

    assertTrue(july.weeks().stream().flatMap(week -> week.days().stream())
                 .filter(day -> !day.isBlank())
                 .allMatch(CalendarMonth.Day::isClosed),
               "every July day should be closed");
  }

  @Test
  void denseLayoutPairsWeekendSlotsAndWidensTheColumns() {
    when(patrolData.readShiftDefinitions()).thenReturn(shiftTemplateForEveryDay());

    CalendarMonth normal = build();
    CalendarMonth dense = service.build("Sample", "0", String.valueOf(YEAR), 20,
                                        DIRECTOR_ID, DIRECTOR_ID, patrolData, log);

    assertEquals(14, normal.dayWidth());
    assertEquals(14, normal.weekendWidth());
    assertEquals(11, dense.dayWidth());
    assertEquals(22, dense.weekendWidth());

    CalendarMonth.Day denseSaturday = dense.weeks().get(1).days().get(6);
    assertEquals(2, denseSaturday.shiftRows().get(0).size(),
                 "a dense weekend cell puts two slots on a row");

    CalendarMonth.Day normalSaturday = normal.weeks().get(1).days().get(6);
    assertEquals(1, normalSaturday.shiftRows().get(0).size(),
                 "the normal layout is one slot per row");
  }

  @Test
  void navigationWrapsAroundTheYear() {
    CalendarMonth january = build();

    assertTrue(january.nav().previousUrl().contains("month=11")
                 && january.nav().previousUrl().contains("year=" + (YEAR - 1)),
               "before January is December of the previous year: " + january.nav().previousUrl());
    assertTrue(january.nav().nextUrl().contains("month=1")
                 && january.nav().nextUrl().contains("year=" + YEAR),
               "after January is February: " + january.nav().nextUrl());
  }

  @Test
  void anUnusableMonthOrYearFallsBackToToday() {
    CalendarMonth fallback = service.build("Sample", "0", "1999", 0,
                                           DIRECTOR_ID, DIRECTOR_ID, patrolData, log);

    int thisYear = new java.util.GregorianCalendar().get(java.util.Calendar.YEAR);
    assertEquals(thisYear, fallback.year(), "1999 is outside the accepted window");
  }

  private CalendarMonth build() {
    //the support login is passed as backDoorUser so the view is a director's
    return service.build("Sample", String.valueOf(MONTH), String.valueOf(YEAR), 0,
                         DIRECTOR_ID, DIRECTOR_ID, patrolData, log);
  }

  private CalendarMonth.Day dayOfMonth(int dayNumber) {
    return build().weeks().stream()
      .flatMap(week -> week.days().stream())
      .filter(day -> day.dayNumber() == dayNumber)
      .findFirst()
      .orElseThrow(() -> new AssertionError("day " + dayNumber + " is missing from the month"));
  }

  /** One saved assignment on the given day, carrying the given comment. */
  private void givenDayComment(int dayOfMonth, String comment) {
    ShiftDefinitions shift = new ShiftDefinitions("Wednesday_1", "08:00", "16:00", 1,
                                                  Assignments.DAY_TYPE, log);
    String date = String.format("%04d-%02d-%02d_a", YEAR, MONTH + 1, dayOfMonth);
    Assignments assignment = new Assignments(date, shift, log);
    assignment.setEventName(comment);

    ArrayList<Assignments> assignments = new ArrayList<>();
    assignments.add(assignment);
    when(patrolData.readSortedAssignments(anyInt(), anyInt())).thenReturn(assignments);
  }

  /** A two-slot template on every day of the week, so cells have slots to lay out. */
  private ArrayList<ShiftDefinitions> shiftTemplateForEveryDay() {
    ArrayList<ShiftDefinitions> templates = new ArrayList<>();
    for (String dayName : List.of("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday",
                                  "Friday", "Saturday")) {
      templates.add(new ShiftDefinitions(dayName + "_1", "08:00", "16:00", 2,
                                         Assignments.DAY_TYPE, log));
    }
    return templates;
  }
}
