package com.gledhills.calendar.service;

import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.store.ShiftTemplatePatrollers;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.StaticUtils;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a day-of-week shift template onto real calendar days, patrollers included.
 *
 * Only the Sunday..Saturday templates can be applied: the weekday in the name is what says which
 * days of the range to fill, so a "Monday" template lands on every Monday between the two dates and
 * nowhere else.
 *
 * This is the only place a template turns into actual {@code assignments} rows in bulk, and those
 * rows are what ListPatrollers counts and DailyReminder emails about, so a matching day that is
 * already scheduled is never quietly rewritten. The preview lists those days by date and the
 * director picks:
 *
 * <ul>
 *   <li>{@link ConflictMode#SKIP} - leave them exactly as they are, fill in only the empty days</li>
 *   <li>{@link ConflictMode#OVERWRITE} - delete their saved rows, taking anybody scheduled on them
 *       with it, and write the template fresh</li>
 * </ul>
 *
 * Overwriting is only offered when the template actually carries patrollers: an empty template has
 * nothing to push out, so clobbering a day that is already set up could only lose work.
 */
@Service
public class ShiftTemplateApplyService {

  /** Assignment row positions are 1 based, unlike the template's 0 based rows. */
  private static final int FIRST_ASSIGNMENT_POSITION = 1;

  private static final DateTimeFormatter ASSIGNMENT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  /** What to do about matching days that are already scheduled. */
  public enum ConflictMode {
    /** Leave them alone; only empty days get the template. The default, and the safe one. */
    SKIP,
    /** Delete what is on them and rebuild from the template, losing the patrollers on those rows. */
    OVERWRITE;

    /** Anything but a literal "overwrite" means skip, so a bad parameter cannot delete a shift. */
    public static ConflictMode of(String value) {
      return OVERWRITE.name().equalsIgnoreCase(value) ? OVERWRITE : SKIP;
    }
  }

  /**
   * What applying a template over a date range would do.
   *
   * @param dayOfWeekName        the weekday the template fills, or null when the template is not a
   *                             day-of-week template and so cannot be applied this way
   * @param daysToWrite          empty dates that would gain the template's shifts
   * @param daysInConflict       matching dates that already have shifts saved on them
   * @param patrollersPerDay     how many patrollers each written day would receive
   * @param patrollersInConflict how many patrollers are scheduled on {@code daysInConflict}, and so
   *                             would lose their shift if those days were overwritten
   * @param mode                 what would happen to {@code daysInConflict}
   */
  public record Preview(String dayOfWeekName,
                        List<LocalDate> daysToWrite,
                        List<LocalDate> daysInConflict,
                        int patrollersPerDay,
                        int patrollersInConflict,
                        ConflictMode mode) {

    public boolean isApplicable() {
      return dayOfWeekName != null;
    }

    public int getDaysToWriteCount() {
      return daysToWrite.size();
    }

    public int getDaysInConflictCount() {
      return daysInConflict.size();
    }

    public boolean hasConflicts() {
      return !daysInConflict.isEmpty();
    }

    /**
     * Whether overwriting is even worth offering. An empty template has nothing to push out onto a
     * day that is already set up, so the choice is not put to the director at all.
     */
    public boolean isCanOverwrite() {
      return patrollersPerDay > 0;
    }

    /**
     * True when the director still has the skip/overwrite question in front of them, which is what
     * decides whether the page shows that dialog's buttons or a plain confirm.
     */
    public boolean isAskingConflictQuestion() {
      return hasConflicts() && isCanOverwrite();
    }

    /** True when confirming this preview would delete real assignments. */
    public boolean isOverwriting() {
      return mode == ConflictMode.OVERWRITE && hasConflicts() && isCanOverwrite();
    }

    /** Every day that would be touched, empty ones and overwritten ones together, in date order. */
    public List<LocalDate> getDaysAffected() {
      List<LocalDate> all = new ArrayList<>(daysToWrite);
      if (isOverwriting()) {
        all.addAll(daysInConflict);
      }
      all.sort(LocalDate::compareTo);
      return all;
    }

    public int getDaysAffectedCount() {
      return getDaysAffected().size();
    }

    /** Every conflicting date, spelled out - the dialog names them rather than counting them. */
    public List<String> getConflictDates() {
      List<String> dates = new ArrayList<>();
      for (LocalDate date : daysInConflict) {
        dates.add(date.toString());
      }
      return dates;
    }

    /** The first few dates, so the director can sanity-check what they are about to commit. */
    public List<String> getSampleDates() {
      List<LocalDate> affected = getDaysAffected();
      List<String> sample = new ArrayList<>();

      for (int i = 0; i < Math.min(5, affected.size()); i++) {
        sample.add(affected.get(i).toString());
      }
      if (affected.size() > 5) {
        sample.add("... and " + (affected.size() - 5) + " more");
      }
      return sample;
    }
  }

  /** Whether a template name is one of the Sunday..Saturday names that can be applied at all. */
  public boolean isDayOfWeekTemplate(String templateName) {
    return dayOfWeekFor(templateName) != null;
  }

  /**
   * Work out which days a template would fill, without writing anything.
   *
   * @param templateName the template's name, e.g. "Saturday"
   * @param mode         what to do about days that already have shifts
   */
  public Preview preview(String resort, PatrolData patrolData, String templateName, LocalDate from,
                         LocalDate to, ConflictMode mode, Logger LOG) {
    DayOfWeek dayOfWeek = dayOfWeekFor(templateName);
    if (dayOfWeek == null || from == null || to == null || to.isBefore(from)) {
      return notApplicable();
    }

    List<ShiftDefinitions> rows = templateRows(patrolData, templateName);
    if (rows.isEmpty()) {
      return notApplicable();
    }

    Map<LocalDate, List<Assignments>> saved = savedAssignmentsByDate(patrolData, from, to);
    List<LocalDate> toWrite = new ArrayList<>();
    List<LocalDate> conflicts = new ArrayList<>();
    int patrollersInConflict = 0;

    for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
      if (date.getDayOfWeek() != dayOfWeek) {
        continue;
      }
      List<Assignments> existing = saved.get(date);

      if (existing == null) {
        toWrite.add(date);
        continue;
      }
      conflicts.add(date);
      for (Assignments assignment : existing) {
        patrollersInConflict += assignment.getUseCount(resort);
      }
    }

    return new Preview(templateName, toWrite, conflicts,
                       countTemplatePatrollers(patrolData, rows, LOG), patrollersInConflict, mode);
  }

  /**
   * Write the template onto every day the matching {@link #preview} listed, clearing first the days
   * it listed for overwriting.
   *
   * @return how many days were written
   */
  public int apply(String resort, PatrolData patrolData, String templateName, LocalDate from,
                   LocalDate to, ConflictMode mode, Logger LOG) {
    Preview preview = preview(resort, patrolData, templateName, from, to, mode, LOG);
    if (!preview.isApplicable()) {
      LOG.info("not applying '" + templateName + "': it is not a day-of-week template");
      return 0;
    }

    List<ShiftDefinitions> rows = templateRows(patrolData, templateName);
    Map<String, List<String>> patrollersByShift =
      ShiftTemplatePatrollers.readAll(LOG, patrolData.getConnection());
    Set<String> rosterIds = rosterIds(patrolData);

    //the days being overwritten have to be cleared first: their rows are what hold the patrollers
    if (preview.isOverwriting()) {
      Map<LocalDate, List<Assignments>> saved = savedAssignmentsByDate(patrolData, from, to);
      for (LocalDate date : preview.daysInConflict()) {
        List<Assignments> existing = saved.getOrDefault(date, List.of());
        for (int i = existing.size() - 1; i >= 0; i--) {
          patrolData.deleteAssignment(existing.get(i));
        }
      }
    }

    for (LocalDate date : preview.getDaysAffected()) {
      int position = FIRST_ASSIGNMENT_POSITION;
      for (ShiftDefinitions row : rows) {
        String assignmentDate =
          Assignments.createAssignmentName(date.format(ASSIGNMENT_DATE), position);
        Assignments assignment = new Assignments(assignmentDate, row, LOG);
        assignment.setEventName(" ");

        applyPatrollers(assignment, resort, rosterIds,
                        patrollersByShift.get(row.getEventName()), LOG);

        patrolData.writeAssignment(assignment);
        position++;
      }
    }

    LOG.info("applied template '" + templateName + "' to " + preview.getDaysAffectedCount()
               + " day(s) between " + from + " and " + to + "; " + preview.getDaysInConflictCount()
               + " already-scheduled day(s) were "
               + (preview.isOverwriting()
                    ? "overwritten, removing " + preview.patrollersInConflict() + " patroller(s)"
                    : "skipped"));
    return preview.getDaysAffectedCount();
  }

  private Preview notApplicable() {
    return new Preview(null, List.of(), List.of(), 0, 0, ConflictMode.SKIP);
  }

  /** The weekday a template fills in, or null when its name is not a day of the week. */
  private DayOfWeek dayOfWeekFor(String templateName) {
    if (templateName == null) {
      return null;
    }
    for (int day = 0; day < StaticUtils.szDays.length; day++) {
      if (StaticUtils.szDays[day].equals(templateName)) {
        //szDays is Sunday-first; DayOfWeek is Monday-first
        return day == 0 ? DayOfWeek.SUNDAY : DayOfWeek.of(day);
      }
    }
    return null;
  }

  private List<ShiftDefinitions> templateRows(PatrolData patrolData, String templateName) {
    List<ShiftDefinitions> rows = new ArrayList<>();
    for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
      if (shift.parsedEventName().equals(templateName)) {
        rows.add(shift);
      }
    }
    return rows;
  }

  /**
   * Everything already scheduled in the range, grouped by date. Read a month at a time rather than
   * a day at a time, which is one query per month instead of one per day.
   */
  private Map<LocalDate, List<Assignments>> savedAssignmentsByDate(PatrolData patrolData,
                                                                   LocalDate from, LocalDate to) {
    Map<LocalDate, List<Assignments>> saved = new LinkedHashMap<>();
    LocalDate month = from.withDayOfMonth(1);

    while (!month.isAfter(to)) {
      for (Assignments assignment : patrolData.readSortedAssignments(month.getYear(),
                                                                     month.getMonthValue())) {
        LocalDate date =
          LocalDate.of(assignment.getYear(), assignment.getMonth(), assignment.getDay());
        saved.computeIfAbsent(date, key -> new ArrayList<>()).add(assignment);
      }
      month = month.plusMonths(1);
    }
    return saved;
  }

  private int countTemplatePatrollers(PatrolData patrolData, List<ShiftDefinitions> rows, Logger LOG) {
    Map<String, List<String>> patrollersByShift =
      ShiftTemplatePatrollers.readAll(LOG, patrolData.getConnection());
    Set<String> rosterIds = rosterIds(patrolData);

    int count = 0;
    for (ShiftDefinitions row : rows) {
      List<String> slots = patrollersByShift.get(row.getEventName());
      if (slots == null) {
        continue;
      }
      for (int slot = 0; slot < slots.size() && slot < row.getCount(); slot++) {
        if (!ShiftTemplatePatrollers.isEmpty(slots.get(slot)) && rosterIds.contains(slots.get(slot))) {
          count++;
        }
      }
    }
    return count;
  }

  /** Skips empty slots, slots past the shift's count, and anybody no longer on the roster. */
  private void applyPatrollers(Assignments assignment, String resort, Set<String> rosterIds,
                               List<String> templateSlots, Logger LOG) {
    if (templateSlots == null) {
      return;
    }
    for (int slot = 0; slot < templateSlots.size() && slot < assignment.getCount(); slot++) {
      String patrollerId = templateSlots.get(slot);
      if (ShiftTemplatePatrollers.isEmpty(patrollerId) || !rosterIds.contains(patrollerId)) {
        continue;
      }
      assignment.insertAt(resort, slot, patrollerId);
    }
  }

  private Set<String> rosterIds(PatrolData patrolData) {
    Set<String> ids = new HashSet<>();
    ResultSet rosterResults = patrolData.resetRoster();
    Roster member;

    while ((member = patrolData.nextMember("", rosterResults)) != null) {
      ids.add(member.getID());
    }
    return ids;
  }
}
