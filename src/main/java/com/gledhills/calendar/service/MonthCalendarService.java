package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.CalendarMonth;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.DirectorSettings;
import com.gledhills.calendar.store.NewIndividualAssignment;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.utils.EventNameSanitizer;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.ResortTimeZones;
import com.gledhills.calendar.utils.StaticUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the month calendar view model.
 *
 * This is the port of the legacy MonthCalendar servlet's readData/printCell/printName methods,
 * with the HTML removed: every decision it used to bake into a string of markup is now a field on
 * CalendarMonth, and month-calendar.html does the drawing.
 */
@Service
public class MonthCalendarService {

  /** Patroller id 1 in the first slot is the "we are closed this day" flag. */
  private static final String CLOSED_FLAG_ID = "1";

  private static final int DAYS_PER_WEEK = 7;
  private static final int[] DAYS_IN_MONTH = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };

  /**
   * @param resort        resort key
   * @param requestedMonth 0 based month from the query string, may be out of range
   * @param requestedYear  year from the query string, may be out of range
   * @param textLen        row-length override; anything above 0 forces the dense layout
   * @param patrollerId    logged-in member, never null (the controller redirects first)
   * @param backDoorUser   support login from the credentials file, may be null
   * @param patrolData     open connection for this resort
   */
  public CalendarMonth build(String resort, String requestedMonth, String requestedYear,
                             int textLen, String patrollerId, String backDoorUser,
                             PatrolData patrolData, Logger LOG) {

    DirectorSettings directorSettings = patrolData.readDirectorSettings();

    //"Today" is taken in the RESORT's zone, not the server's. It decides which days are in the
    //past and therefore frozen for members, so a resort three hours behind must not have tonight's
    //shift treated as past because the server's clock has already rolled over. Same rule as
    //ShiftTimeCleanup.cleanScheduledDays.
    GregorianCalendar today = new GregorianCalendar(
      TimeZone.getTimeZone(ResortTimeZones.inForce(resort, directorSettings)));
    int realMonth = today.get(Calendar.MONTH);
    int realYear = today.get(Calendar.YEAR);

    int month = realMonth;
    int year = realYear;
    if (isDateUsable(requestedMonth, requestedYear, realYear)) {
      month = Integer.parseInt(requestedMonth);
      year = Integer.parseInt(requestedYear);
    }
    Hashtable<Integer, String> names = new Hashtable<>(Roster.MAX_MEMBERS);
    boolean isDirector = readRoster(patrolData, patrollerId, backDoorUser, names);

    Map<String, NewIndividualAssignment> needsReplacement =
      patrolData.readNewIndividualAssignments(year, month + 1, 0);
    if (needsReplacement == null) {
      needsReplacement = new HashMap<>();
    }

    Assignments[][] monthData = new Assignments[32][ShiftDefinitions.MAX + 5];
    int maxAssignmentCount = populateMonthData(patrolData, monthData, month, year);
    ShiftDefinitions[][] predefinedDayShifts = readPredefinedDayShifts(patrolData);

    boolean dense = isDenseLayout(resort, textLen, maxAssignmentCount);
    int dayWidth = dense ? 11 : 14;
    int weekendWidth = dense ? 22 : 14;

    List<CalendarMonth.Week> weeks = buildWeeks(resort, month, year, dense, monthData, predefinedDayShifts,
                                                directorSettings, names, needsReplacement,
                                                patrollerId, isDirector, realMonth, realYear,
                                                today.get(Calendar.DATE), LOG);

    return new CalendarMonth(
      PatrolData.getResortFullName(resort, LOG),
      StaticUtils.szMonthsFull[month],
      month,
      year,
      weeks,
      dense,
      dayWidth,
      weekendWidth,
      10,
      buildNav(resort, month, year, patrollerId, realMonth, realYear),
      PatrolData.USING_TESTING_ADDRESS
        ? "ERROR!!! Using to older database!!! Contact Steve Gledhill (801)209-5974 ASAP" : "");
  }

  /**
   * The legacy rule: a month/year is honored only when it parses and the year sits within two
   * years back and one year forward. Anything else silently shows the current month.
   */
  private boolean isDateUsable(String month, String year, int realYear) {
    if (month == null || year == null) {
      return false;
    }
    try {
      int parsedMonth = Integer.parseInt(month);
      int parsedYear = Integer.parseInt(year);
      return parsedMonth >= 0 && parsedMonth <= 12
        && parsedYear >= realYear - 2 && parsedYear <= realYear + 1;
    }
    catch (NumberFormatException e) {
      return false;
    }
  }

  /** Fills the id-to-"name, phone" lookup and reports whether the viewer is a director. */
  private boolean readRoster(PatrolData patrolData, String patrollerId, String backDoorUser,
                             Hashtable<Integer, String> names) {
    boolean isDirector = false;
    Roster member;
    var rosterResults = patrolData.resetRoster();

    while ((member = patrolData.nextMember("", rosterResults)) != null) {
      names.put(member.getIdNum(), member.getFullName() + ", " + member.getHomePhone());
      //the legacy code also OR-ed in a backDoorUser test here, but did it inside this loop, so
      //the support login ended up with whatever the LAST roster row's director flag happened to
      //be. Match on the id being viewed instead.
      if (member.getID().equals(patrollerId)) {
        isDirector = member.isDirector();
      }
    }

    if (backDoorUser != null && !backDoorUser.isEmpty() && backDoorUser.equals(patrollerId)) {
      isDirector = true; //support login always gets the director view
    }
    return isDirector;
  }

  /** @return the largest number of slots any single day holds, which decides the dense layout */
  private int populateMonthData(PatrolData patrolData, Assignments[][] monthData, int month, int year) {
    int maxAssignmentCount = 0;
    int oldYear = 0;
    int oldMonth = 0;
    int oldDay = 0;
    int pos = 0;
    int slotsThisDay = 0;

    for (Assignments assignment : patrolData.readSortedAssignments(year, month + 1)) {
      int assignmentYear = assignment.getYear();
      int assignmentMonth = assignment.getMonth();
      int day = assignment.getDay();

      if (assignmentYear == oldYear && assignmentMonth == oldMonth && day == oldDay) {
        ++pos;
      }
      else {
        pos = 0;
        slotsThisDay = 0;
      }
      slotsThisDay += assignment.getCount();

      if (assignmentYear == year && assignmentMonth == month + 1 && pos < monthData[day].length) {
        monthData[day][pos] = assignment;
        maxAssignmentCount = Math.max(maxAssignmentCount, slotsThisDay);
      }

      oldYear = assignmentYear;
      oldMonth = assignmentMonth;
      oldDay = day;
    }
    return maxAssignmentCount;
  }

  /** Shift templates indexed by Calendar.SUNDAY..Calendar.SATURDAY. */
  private ShiftDefinitions[][] readPredefinedDayShifts(PatrolData patrolData) {
    ShiftDefinitions[][] shiftsByDayOfWeek = new ShiftDefinitions[8][ShiftDefinitions.MAX];
    int lastDayOfWeek = -1;
    int pos = 0;

    for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
      int dayOfWeek = dayOfWeekOrNegativeOne(shift.parsedEventName());
      pos = (dayOfWeek == lastDayOfWeek) ? pos + 1 : 0;
      lastDayOfWeek = dayOfWeek;

      if (dayOfWeek >= 0 && pos < ShiftDefinitions.MAX) {
        shiftsByDayOfWeek[dayOfWeek][pos] = shift;
      }
    }
    return shiftsByDayOfWeek;
  }

  private int dayOfWeekOrNegativeOne(String name) {
    return switch (name == null ? "" : name) {
      case "Sunday" -> Calendar.SUNDAY;
      case "Monday" -> Calendar.MONDAY;
      case "Tuesday" -> Calendar.TUESDAY;
      case "Wednesday" -> Calendar.WEDNESDAY;
      case "Thursday" -> Calendar.THURSDAY;
      case "Friday" -> Calendar.FRIDAY;
      case "Saturday" -> Calendar.SATURDAY;
      default -> -1;
    };
  }

  /** Resort-specific layout choices, carried over unchanged from the legacy servlet. */
  private boolean isDenseLayout(String resort, int textLen, int maxAssignmentCount) {
    if (resort.contains("Jackson")) {
      return false;
    }
    return textLen > 0 || maxAssignmentCount > 12 || resort.equals("Brighton");
  }

  private List<CalendarMonth.Week> buildWeeks(
    String resort, int month, int year, boolean dense, Assignments[][] monthData,
    ShiftDefinitions[][] predefinedDayShifts, DirectorSettings directorSettings,
    Hashtable<Integer, String> names, Map<String, NewIndividualAssignment> needsReplacement,
    String patrollerId, boolean isDirector, int realMonth, int realYear, int realDate, Logger LOG) {

    GregorianCalendar firstOfMonth = new GregorianCalendar(TimeZone.getDefault());
    firstOfMonth.set(year, month, 1);
    int daysInMonth = daysInMonth(month, year);

    List<CalendarMonth.Week> weeks = new ArrayList<>();
    int day = 2 - firstOfMonth.get(Calendar.DAY_OF_WEEK); //negative or zero pads the first row

    while (day <= daysInMonth) {
      List<CalendarMonth.Day> days = new ArrayList<>(DAYS_PER_WEEK);
      for (int dayOfWeek = 0; dayOfWeek < DAYS_PER_WEEK; dayOfWeek++) {
        boolean weekend = (dayOfWeek == 0 || dayOfWeek == 6);
        if (day > 0 && day <= daysInMonth) {
          days.add(buildDay(resort, month, year, day, dayOfWeek, weekend, dense, monthData,
                            predefinedDayShifts, directorSettings, names, needsReplacement,
                            patrollerId, isDirector, realMonth, realYear, realDate, LOG));
        }
        else {
          days.add(new CalendarMonth.Day(0, weekend, "", false, null, null, List.of()));
        }
        day++;
      }
      weeks.add(new CalendarMonth.Week(days));
    }
    return weeks;
  }

  private int daysInMonth(int month, int year) {
    if (month == 1 && new GregorianCalendar().isLeapYear(year)) {
      return 29;
    }
    return DAYS_IN_MONTH[month];
  }

  private CalendarMonth.Day buildDay(
    String resort, int month, int year, int day, int dayOfWeekZeroBased, boolean weekend,
    boolean dense, Assignments[][] monthData, ShiftDefinitions[][] predefinedDayShifts,
    DirectorSettings directorSettings, Hashtable<Integer, String> names,
    Map<String, NewIndividualAssignment> needsReplacement, String patrollerId, boolean isDirector,
    int realMonth, int realYear, int realDate, Logger LOG) {

    int dayOfWeek = dayOfWeekZeroBased + 1; //Calendar.SUNDAY..SATURDAY, as the shift tables use
    int assignmentCount = assignmentCountForDay(monthData, day);
    String rawEventName = assignmentCount > 0 && monthData[day][0] != null
      ? monthData[day][0].getEventName() : null;

    boolean namedEvent = rawEventName != null && rawEventName.length() > 1
      && !"Closed".equalsIgnoreCase(rawEventName);
    String dateLink = isDirector ? dayShiftsUrl(resort, dayOfWeekZeroBased, day, month, year, patrollerId) : null;

    boolean inSeason = isInSeason(directorSettings, month + 1, day);
    boolean blackout = isBlackout(directorSettings, dayOfWeek, month + 1, day, year);

    List<CalendarMonth.Slot> shifts = new ArrayList<>();
    String closedLabel = null;

    if (monthData[day][0] == null) {
      //nothing saved for this day: offer the day-of-week template, unless the day is not bookable
      if (predefinedDayShifts[dayOfWeek][0] != null) {
        if (!inSeason) {
          closedLabel = "CLOSED";
        }
        else if (blackout) {
          closedLabel = "Not Yet Available";
        }
        else {
          shifts = templateSlots(resort, month, year, day, dayOfWeekZeroBased, dayOfWeek,
                                 predefinedDayShifts, directorSettings, needsReplacement,
                                 patrollerId, isDirector, realMonth, realYear, realDate);
        }
      }
      else if (!inSeason) {
        closedLabel = "CLOSED";
      }
    }
    else if (CLOSED_FLAG_ID.equals(monthData[day][0].getPosID(0))
      || "Closed".equalsIgnoreCase(rawEventName)) {
      closedLabel = "CLOSED";
    }
    else {
      shifts = savedSlots(resort, month, year, day, dayOfWeekZeroBased, dayOfWeek, monthData,
                          assignmentCount, names, directorSettings, needsReplacement, patrollerId,
                          isDirector, realMonth, realYear, realDate, LOG);
    }

    //a "Closed" comment is the flag itself, not a comment worth repeating under the date
    String eventNameHtml = namedEvent ? EventNameSanitizer.sanitize(rawEventName) : "";

    return new CalendarMonth.Day(day, weekend, eventNameHtml, namedEvent, closedLabel, dateLink,
                                 groupIntoRows(shifts, dense && weekend));
  }

  /**
   * Weekend cells in the dense layout show two slots side by side; everything else is one slot
   * per row. Grouping here keeps the template free of index arithmetic.
   */
  private List<List<CalendarMonth.Slot>> groupIntoRows(List<CalendarMonth.Slot> slots, boolean paired) {
    int perRow = paired ? 2 : 1;
    List<List<CalendarMonth.Slot>> rows = new ArrayList<>();
    for (int index = 0; index < slots.size(); index += perRow) {
      rows.add(List.copyOf(slots.subList(index, Math.min(index + perRow, slots.size()))));
    }
    return rows;
  }

  private int assignmentCountForDay(Assignments[][] monthData, int day) {
    int count = 0;
    while (count < ShiftDefinitions.MAX && monthData[day][count] != null) {
      count++;
    }
    return count;
  }

  /** Empty slots offered by the day-of-week template, for a day with nothing saved yet. */
  private List<CalendarMonth.Slot> templateSlots(
    String resort, int month, int year, int day, int dayOfWeekZeroBased, int dayOfWeek,
    ShiftDefinitions[][] predefinedDayShifts, DirectorSettings directorSettings,
    Map<String, NewIndividualAssignment> needsReplacement, String patrollerId, boolean isDirector,
    int realMonth, int realYear, int realDate) {

    List<CalendarMonth.Slot> slots = new ArrayList<>();
    boolean editable = mayEdit(resort, directorSettings, isDirector, month, year, day,
                               realMonth, realYear, realDate);

    for (int shiftIndex = 0; shiftIndex < ShiftDefinitions.MAX; shiftIndex++) {
      ShiftDefinitions shift = predefinedDayShifts[dayOfWeek][shiftIndex];
      if (shift == null) {
        break;
      }
      for (int slotIndex = 0; slotIndex < shift.getCount(); slotIndex++) {
        String editLink = editable
          ? changeShiftUrl(resort, dayOfWeekZeroBased, day, month, year, patrollerId,
                           shiftIndex + 1, slotIndex) : null;
        //label when the template row has one, start time otherwise - see getDisplayStartString
        slots.add(new CalendarMonth.Slot(shift.getDisplayStartString(), "", "", false, false, editLink));
      }
    }
    return slots;
  }

  /** Slots for a day that has saved assignments. */
  private List<CalendarMonth.Slot> savedSlots(
    String resort, int month, int year, int day, int dayOfWeekZeroBased, int dayOfWeek,
    Assignments[][] monthData, int assignmentCount, Hashtable<Integer, String> names,
    DirectorSettings directorSettings, Map<String, NewIndividualAssignment> needsReplacement,
    String patrollerId, boolean isDirector, int realMonth, int realYear, int realDate, Logger LOG) {

    List<CalendarMonth.Slot> slots = new ArrayList<>();
    boolean editable = mayEdit(resort, directorSettings, isDirector, month, year, day,
                               realMonth, realYear, realDate);

    for (int shiftIndex = 0; shiftIndex < assignmentCount; shiftIndex++) {
      Assignments assignment = monthData[day][shiftIndex];
      //label when this row has one, start time otherwise - the same rule as the template path
      String time = assignment.getDisplayStartString() + " ";

      for (int slotIndex = 0; slotIndex < assignment.getCount(); slotIndex++) {
        String rawId = assignment.getPosID(slotIndex);
        int id;
        try {
          id = Integer.parseInt(rawId);
        }
        catch (Exception e) {
          LOG.logException("Error, bad data for monthData[" + day + "][" + shiftIndex
                             + "].getPosID(" + slotIndex + ") = (" + rawId + ")", e);
          continue;
        }

        boolean missed = id < 0;
        int memberId = Math.abs(id);
        String tooltip = memberId == 0 ? "" : names.get(memberId);
        String memberName;
        if (memberId == 0) {
          memberName = "";
          tooltip = "";
        }
        else if (tooltip == null) {
          memberName = "Err id (" + memberId + ")";
          tooltip = "";
        }
        else {
          memberName = tooltip.substring(0, tooltip.lastIndexOf(','));
        }

        String editLink = editable
          ? changeShiftUrl(resort, dayOfWeekZeroBased, day, month, year, patrollerId,
                           shiftIndex + 1, slotIndex) : null;

        slots.add(new CalendarMonth.Slot(time, memberName, tooltip, missed,
                                         flaggedForReplacement(needsReplacement, year, month, day,
                                                               shiftIndex + 1, slotIndex, memberId),
                                         editLink));
      }
    }
    return slots;
  }

  private boolean flaggedForReplacement(Map<String, NewIndividualAssignment> needsReplacement,
                                        int year, int month, int day, int pos, int index, int memberId) {
    NewIndividualAssignment flagged =
      needsReplacement.get(NewIndividualAssignment.buildKey(year, month + 1, day, pos, index));
    if (flagged == null || !flagged.getNeedsReplacement()) {
      return false;
    }
    try {
      return Integer.parseInt(flagged.getPatrollerId()) == memberId;
    }
    catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * Who may change an assignment: directors always, everyone else only when the resort allows
   * member changes and <b>the day has not already happened</b>.
   *
   * <p>A day in the past is frozen for members at every resort. It used to be frozen at two -
   * the servlet this was migrated from hard-coded {@code KellyCanyon} and {@code Brighton}, with
   * its author's own note beside it reading "this part is a hack. put info in directors page" and
   * "todo fix this crap" - and the migration carried the hack across faithfully. Everywhere else,
   * a member could sign themselves into a shift that finished last March, or take their name off
   * one they had already worked, and the roster's history would quietly change underneath the
   * director who had been reading it.
   *
   * <p>Today itself stays editable: the comparison is {@code day >= realDate}, so somebody can
   * still pick up or drop tonight's shift. "Today" is the resort's own date - see build().
   *
   * <p>This covers empty slots as much as filled ones, because both slot paths ask this one
   * question: a member cannot add themselves to a vacancy on a day that has gone either.
   *
   * <p>Directors are unaffected and can still fix the past, which is the point of them being the
   * exception - a shift somebody actually worked sometimes has to be corrected afterwards.
   */
  private boolean mayEdit(String resort, DirectorSettings directorSettings, boolean isDirector,
                          int month, int year, int day, int realMonth, int realYear, int realDate) {
    if (isDirector) {
      return true;
    }
    if (directorSettings.getDirectorsOnlyChange()) {
      return false;
    }
    if (year != realYear) {
      return year > realYear;
    }
    if (month != realMonth) {
      return month > realMonth;
    }
    return day >= realDate;
  }

  private boolean isInSeason(DirectorSettings settings, int month, int day) {
    int startMonth = settings.getStartMonth();
    int startDay = settings.getStartDay();
    int endMonth = settings.getEndMonth();
    int endDay = settings.getEndDay();

    boolean beforeSeasonStart = month < startMonth || (month == startMonth && day < startDay);
    boolean afterSeasonEnd = month > endMonth || (month == endMonth && day > endDay);
    return !(beforeSeasonStart && afterSeasonEnd);
  }

  /** Blackout only ever applies to weekends, matching the legacy behavior. */
  @SuppressWarnings("deprecation")
  private boolean isBlackout(DirectorSettings settings, int dayOfWeek, int month, int day, int year) {
    if (!settings.getUseBlackOut() || (dayOfWeek != Calendar.SUNDAY && dayOfWeek != Calendar.SATURDAY)) {
      return false;
    }
    Date start = new Date(settings.getBlackOutStartYear() - 1900, settings.getBlackOutStartMonth() - 1,
                          settings.getBlackOutStartDay());
    Date end = new Date(settings.getBlackOutEndYear() - 1900, settings.getBlackOutEndMonth() - 1,
                        settings.getBlackOutEndDay());
    Date thisDay = new Date(year - 1900, month - 1, day);
    return thisDay.getTime() >= start.getTime() && thisDay.getTime() <= end.getTime();
  }

  private String dayShiftsUrl(String resort, int dayOfWeek, int day, int month, int year, String patrollerId) {
    return "DayShifts?resort=" + resort + "&dayOfWeek=" + dayOfWeek + "&date=" + day
      + "&month=" + month + "&year=" + year + idTag(patrollerId);
  }

  private String changeShiftUrl(String resort, int dayOfWeek, int day, int month, int year,
                                String patrollerId, int pos, int index) {
    return "ChangeShiftAssignments?resort=" + resort + "&dayOfWeek=" + dayOfWeek + "&date=" + day
      + "&month=" + month + "&year=" + year + idTag(patrollerId) + "&pos=" + pos + "&index=" + index;
  }

  private CalendarMonth.Nav buildNav(String resort, int month, int year, String patrollerId,
                                     int realMonth, int realYear) {
    int previousMonth = (month == 0) ? 11 : month - 1;
    int previousYear = (month == 0) ? year - 1 : year;
    int nextMonth = (month == 11) ? 0 : month + 1;
    int nextYear = (month == 11) ? year + 1 : year;

    return new CalendarMonth.Nav(
      monthUrl(resort, previousMonth, previousYear, patrollerId),
      monthUrl(resort, nextMonth, nextYear, patrollerId),
      monthUrl(resort, realMonth, realYear, patrollerId),
      "Return to " + StaticUtils.szMonthsFull[realMonth] + " " + realYear);
  }

  private String monthUrl(String resort, int month, int year, String patrollerId) {
    return "MonthCalendar?resort=" + resort + idTag(patrollerId) + "&month=" + month + "&year=" + year;
  }

  private String idTag(String patrollerId) {
    return StaticUtils.isNotEmpty(patrollerId) ? "&ID=" + patrollerId : "";
  }
}
