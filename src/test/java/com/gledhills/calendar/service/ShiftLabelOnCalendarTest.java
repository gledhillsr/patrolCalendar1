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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The calendar shows a slot's Label when it has one and its start time when it has not.
 *
 * <p>Pinned here rather than left to the goldens because the golden fixtures happen to fall on
 * dates that carry no labels - they would pass whichever way this went. The rule has to hold on
 * <b>both</b> paths into a day cell, and those are separate code in MonthCalendarService: a day
 * drawn from its day-of-week template, and a day with saved assignments. Changing one and not the
 * other is the mistake this is here to catch, since each looks right on its own page.
 */
class ShiftLabelOnCalendarTest {

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
    when(patrolData.nextMember(anyString(), any())).thenReturn(null);
    when(patrolData.readNewIndividualAssignments(anyInt(), anyInt(), anyInt())).thenReturn(null);
    when(patrolData.readShiftDefinitions()).thenReturn(new ArrayList<>());
    when(patrolData.readSortedAssignments(anyInt(), anyInt())).thenReturn(new ArrayList<>());
  }

  /** A day drawn from its day-of-week template - MonthCalendarService's first render site. */
  @Test
  void aTemplateSlotShowsItsLabel() {
    givenTemplateEveryDay("8:00", "TL");

    assertTrue(slotTimes().contains("TL"),
               "the template's label should be drawn instead of its time: " + slotTimes());
    assertTrue(slotTimes().stream().noneMatch(text -> text.trim().equals("8:00")),
               "and the time it replaces should not also appear: " + slotTimes());
  }

  @Test
  void aTemplateSlotWithNoLabelShowsItsTime() {
    givenTemplateEveryDay("8:00", "");

    assertTrue(slotTimes().contains("8:00"),
               "with no label the start time is what the calendar has to show: " + slotTimes());
  }

  /** A day with saved assignments - MonthCalendarService's second, separate render site. */
  @Test
  void aScheduledSlotShowsItsLabel() {
    givenSavedAssignment(7, "8:00", "mentee");

    assertTrue(slotTextOn(7).stream().anyMatch(text -> text.startsWith("mentee")),
               "the assignment's label should be drawn instead of its time: " + slotTextOn(7));
  }

  @Test
  void aScheduledSlotWithNoLabelShowsItsTime() {
    givenSavedAssignment(7, "8:00", "");

    assertTrue(slotTextOn(7).stream().anyMatch(text -> text.startsWith("8:00")),
               "with no label the start time is what the calendar has to show: " + slotTextOn(7));
  }

  /**
   * A day built from a template inherits the template's label, which is what makes the label
   * survive "apply this template to the calendar" rather than being a template-only decoration.
   */
  @Test
  void aDayBuiltFromATemplateInheritsItsLabel() {
    ShiftDefinitions template =
      new ShiftDefinitions("Wednesday_1", "8:00", "16:00", 1, Assignments.DAY_TYPE, "dir", log);
    Assignments built = new Assignments("2026-01-07_a", template, log);

    assertEquals("dir", built.getLabel());
    assertEquals("dir", built.getDisplayStartString());
  }

  private void givenTemplateEveryDay(String start, String label) {
    ArrayList<ShiftDefinitions> templates = new ArrayList<>();
    for (String dayName : List.of("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday",
                                  "Friday", "Saturday")) {
      templates.add(new ShiftDefinitions(dayName + "_1", start, "16:00", 2,
                                         Assignments.DAY_TYPE, label, log));
    }
    when(patrolData.readShiftDefinitions()).thenReturn(templates);
  }

  private void givenSavedAssignment(int dayOfMonth, String start, String label) {
    ShiftDefinitions shift =
      new ShiftDefinitions("Wednesday_1", start, "16:00", 1, Assignments.DAY_TYPE, label, log);
    String date = String.format("%04d-%02d-%02d_a", YEAR, MONTH + 1, dayOfMonth);
    Assignments assignment = new Assignments(date, shift, log);
    assignment.setEventName(" ");

    ArrayList<Assignments> assignments = new ArrayList<>();
    assignments.add(assignment);
    when(patrolData.readSortedAssignments(anyInt(), anyInt())).thenReturn(assignments);
  }

  private List<String> slotTimes() {
    return build().weeks().stream()
      .flatMap(week -> week.days().stream())
      .flatMap(day -> day.shiftRows().stream())
      .flatMap(List::stream)
      .map(CalendarMonth.Slot::time)
      .toList();
  }

  private List<String> slotTextOn(int dayNumber) {
    return build().weeks().stream()
      .flatMap(week -> week.days().stream())
      .filter(day -> day.dayNumber() == dayNumber)
      .flatMap(day -> day.shiftRows().stream())
      .flatMap(List::stream)
      .map(CalendarMonth.Slot::time)
      .toList();
  }

  private CalendarMonth build() {
    return service.build("Sample", String.valueOf(MONTH), String.valueOf(YEAR), 0,
                         DIRECTOR_ID, null, patrolData, log);
  }
}
