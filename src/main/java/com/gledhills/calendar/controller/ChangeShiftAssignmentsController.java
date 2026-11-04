package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.*;
import com.gledhills.calendar.utils.*;
import org.apache.commons.lang3.StringUtils;
import com.gledhills.calendar.store.*;
import com.gledhills.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.*;

@Controller
public class ChangeShiftAssignmentsController {

  private static final String LIMIT_DAY_SHIFTS_FOR_RESORT = "Brightonzz"; // currently disabled
  private static final int MAX_DAY_SHIFTS_PER_MONTH = 30;

  /**
   * Which shift this page is changing, as "(dir) 8:00 - 16:30", for the heading.
   *
   * <p><b>Falls back to the day-of-week template.</b> A day nobody has been scheduled on yet has no
   * {@code assignments} rows - the calendar draws it from the template for that weekday - and that
   * is exactly the day somebody is most likely to be opening this page on, to put the first name
   * in. Reading only the assignments table left the heading blank on every empty day.
   *
   * <p>{@code pos} is 1-based and comes off the URL, so it is checked rather than trusted: a
   * hand-typed one out of range gives no heading rather than an exception on a page that is
   * otherwise perfectly able to do its job.
   */
  static String shiftHeading(List<Assignments> assignmentGroups, int pos,
                             ShiftDefinitions templateShift) {
    if (assignmentGroups != null && pos >= 1 && pos <= assignmentGroups.size()) {
      return assignmentGroups.get(pos - 1).describeLabelAndTimes();
    }
    return templateShift == null ? "" : templateShift.describeLabelAndTimes();
  }

  @GetMapping("/ChangeShiftAssignments")
  public String showChangeShiftForm(HttpServletRequest request, HttpServletResponse response,
                                    Model model) throws IOException {
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

    String resort = request.getParameter("resort");
    Logger LOG = new Logger(ChangeShiftAssignmentsController.class, javaxRequest, "GET", resort, Logger.INFO);

    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Resort not found: " + resort);
      return null;
    }

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    ValidateCredentialsRedirectIfNeeded credentials =
      new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                             "ChangeShiftAssignmentsController", patrolData, LOG);
    if (credentials.hasInvalidCredentials()) {
      patrolData.close();
      return null;
    }

    int dayOfWeek = parseIntParam(request.getParameter("dayOfWeek"), 0);
    int dayOfMonth = parseIntParam(request.getParameter("date"), 1);
    int month = parseIntParam(request.getParameter("month"), 0);   // 0-based
    int year = parseIntParam(request.getParameter("year"), 2024);
    int pos = parseIntParam(request.getParameter("pos"), 1);       // 1-based
    int index = parseIntParam(request.getParameter("index"), 0);   // 0-based

    String loggedInUserId = sessionData.getLoggedInUserId();
    Roster loggedInMember = patrolData.getMemberByID(loggedInUserId);
    String myName = loggedInMember != null ? loggedInMember.getFullName() : "";

    DirectorSettings ds = patrolData.readDirectorSettings();
    int removeAccess = ds.getRemoveAccess();
    boolean allowEditing = !ds.getDirectorsOnlyChange() || (loggedInMember != null && loggedInMember.isDirector());
    boolean isDirector = loggedInUserId.equalsIgnoreCase(sessionData.getBackDoorUser());

    // Build sorted roster and id→name lookup
    List<String> sortedRoster = new ArrayList<>();
    Hashtable<String, String> numToName = new Hashtable<>();
    ResultSet rosterResults = patrolData.resetRoster();
    Roster nextMember;
    while ((nextMember = patrolData.nextMember("", rosterResults)) != null) {
      if (nextMember.getID().equals(loggedInUserId)) {
        isDirector = nextMember.isDirector();
      }
      String lastFirst = nextMember.getFullName_lastNameFirst();

      //Inactive members are not offered in the Replace/Insert list - nobody should be put onto a
      //shift they are not patrolling this season. hasActiveCommitment() is the same test
      //ListPatrollers and the Patrollers tab use, so the three cannot drift.
      if (nextMember.hasActiveCommitment()) {
        sortedRoster.add(lastFirst);
      }

      //numToName is NOT filtered, and that is the point: it resolves whoever currently holds the
      //slot, and posWasEmpty below is derived from it. An inactive member already on a shift would
      //otherwise come back blank, the page would decide the slot was empty, and it would offer
      //"Insert someone else" instead of "Replace <name>" - so a director could no longer take them
      //off the shift at all.
      numToName.put(nextMember.getID(), lastFirst);
    }

    // Read today's assignment groups
    List<Assignments> assignmentGroups = patrolData.readSortedAssignments(year, month + 1, dayOfMonth);

    // Find the person currently occupying the requested position
    String newName = "";
    String newName1 = "";
    String newIdNumber = "0";
    boolean posWasEmpty = true;
    boolean wasMarkedAsMissed = false;

    for (int ag = 0; ag < assignmentGroups.size(); ag++) {
      Assignments shift = assignmentGroups.get(ag);
      for (int ow = 0; ow < shift.getCount(); ow++) {
        String id = shift.getPosID(ow);
        boolean missed = !id.isEmpty() && id.charAt(0) == '-';
        if (missed) {
          id = id.substring(1); // strip missed-shift marker
        }
        if ((ag + 1) == pos && ow == index) {
          wasMarkedAsMissed = missed;
          newIdNumber = id;
          newName = numToName.getOrDefault(id, "");
          if ("0".equals(id)) {
            newName1 = id;
            posWasEmpty = true;
          } else {
            Roster md = patrolData.getMemberByID(id);
            newName1 = (md != null) ? md.getFullName() : id;
            posWasEmpty = StringUtils.isBlank(newName);
          }
        }
      }
    }

    boolean editingMyself = !newName1.isEmpty() && newName1.equals(myName);
    boolean isNightShiftForPos = isNightShift(patrolData, assignmentGroups, dayOfWeek, pos, LOG);
    boolean outsideEarlyLimit = shouldLimitAndInsertIsMaxedOut(resort, loggedInUserId, month, year, patrolData);

    // Compute valid remove date range
    @SuppressWarnings("deprecation")
    long calendarDay = new Date(year - 1900, month, dayOfMonth).getTime() / 1000 / 3600 / 24;
    Date now = new Date();
    @SuppressWarnings("deprecation")
    long firstRemoveDay = new Date(now.getYear(), now.getMonth(), now.getDate()).getTime() / 1000 / 3600 / 24;
    firstRemoveDay += removeAccess;
    boolean validRemoveRange = (firstRemoveDay < calendarDay) || removeAccess == 0;

    // Check if position is marked as needing a replacement
    HashMap<String, NewIndividualAssignment> monthNewAssignments =
      patrolData.readNewIndividualAssignments(year, month + 1, dayOfMonth);
    String key = NewIndividualAssignment.buildKey(year, month + 1, dayOfMonth, pos, index);
    boolean wasMarkedAsNeedingReplacement =
      monthNewAssignments.containsKey(key) && monthNewAssignments.get(key).getNeedsReplacement();

    // Count visible radio buttons before "replace with someone else" for JS autoSelectRadioBtn
    int replaceWithSomeoneElseRadioIndex = 0;
    if (allowEditing && !myName.isEmpty()) {
      if (posWasEmpty && (!outsideEarlyLimit || isNightShiftForPos)) {
        replaceWithSomeoneElseRadioIndex++; // "Insert myself" appears first
      } else if (!posWasEmpty && !editingMyself && !outsideEarlyLimit) {
        replaceWithSomeoneElseRadioIndex++; // "Replace with myself" appears first
      }
    }

    // Worked out while the connection is still open: an unscheduled day's heading comes from the
    // shift templates, which is another read. Building it after the close below found no
    // templates and silently left the heading blank on exactly those days.
    String shiftHeading = shiftHeading(assignmentGroups, pos,
                                       templateShiftFor(patrolData, dayOfWeek, pos));

    patrolData.close();

    String date1 = buildDateAndPosString(year, month, dayOfMonth, pos);

    model.addAttribute("resort", resort);
    model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
    model.addAttribute("dayOfWeekName", StaticUtils.szDays[dayOfWeek]);
    model.addAttribute("shiftHeading", shiftHeading);
    model.addAttribute("monthFullName", StaticUtils.szMonthsFull[month]);
    model.addAttribute("dayOfMonth", dayOfMonth);
    model.addAttribute("month", month);
    model.addAttribute("year", year);
    model.addAttribute("pos", pos);
    model.addAttribute("index", index);
    model.addAttribute("loggedInUserId", loggedInUserId);
    model.addAttribute("myName", myName);
    model.addAttribute("newName1", newName1);
    model.addAttribute("newIdNumber", newIdNumber);
    model.addAttribute("sortedRoster", sortedRoster);
    model.addAttribute("posWasEmpty", posWasEmpty);
    model.addAttribute("outsideEarlyLimit", outsideEarlyLimit);
    model.addAttribute("isNightShift", isNightShiftForPos);
    model.addAttribute("allowEditing", allowEditing);
    model.addAttribute("editingMyself", editingMyself);
    model.addAttribute("validRemoveRange", validRemoveRange);
    model.addAttribute("isDirector", isDirector);
    model.addAttribute("wasMarkedAsNeedingReplacement", wasMarkedAsNeedingReplacement);
    //the missedShift transaction toggles, so the label has to say which way it will go
    model.addAttribute("wasMarkedAsMissed", wasMarkedAsMissed);
    model.addAttribute("date1", date1);
    model.addAttribute("maxLimitedShiftsThisMonth", MAX_DAY_SHIFTS_PER_MONTH);
    model.addAttribute("replaceWithSomeoneElseRadioIndex", replaceWithSomeoneElseRadioIndex);

    return "change-shift-assignments";
  }

  /**
   * The day-of-week template row for this slot, or null when there is not one.
   *
   * <p>Looked up by its exact name - "Thursday_0" - which is how {@link #isNightShift} has always
   * found the same row. That is deliberate: the alternative walks every template counting
   * positions and depends on rows of one weekday being adjacent in whatever order the database
   * returns them, which is an assumption this does not need to make.
   */
  private ShiftDefinitions templateShiftFor(PatrolData patrol, int dayOfWeek, int pos) {
    if (dayOfWeek < 0 || dayOfWeek >= StaticUtils.szDays.length || pos < 1) {
      return null;
    }
    String eventName = StaticUtils.szDays[dayOfWeek] + "_" + (pos - 1);
    for (ShiftDefinitions shift : patrol.readShiftDefinitions()) {
      if (eventName.equals(shift.getEventName())) {
        return shift;
      }
    }
    return null;
  }

  private boolean isNightShift(PatrolData patrol, List<Assignments> assignmentGroups,
                               int dayOfWeek, int pos, Logger LOG) {
    if (assignmentGroups.isEmpty()) {
      String eventName = StaticUtils.szDays[dayOfWeek] + "_" + (pos - 1);
      for (ShiftDefinitions sd : patrol.readShiftDefinitions()) {
        if (eventName.equals(sd.getEventName())) {
          return sd.getType() == 2;
        }
      }
      return false;
    }
    if (pos < 1 || pos > assignmentGroups.size()) {
      LOG.error("isNightShift: pos=" + pos + " out of range, size=" + assignmentGroups.size());
      return false;
    }
    return assignmentGroups.get(pos - 1).isNightShift();
  }

  private boolean shouldLimitAndInsertIsMaxedOut(String resort, String userId, int month, int year,
                                                 PatrolData patrol) {
    if (!LIMIT_DAY_SHIFTS_FOR_RESORT.equals(resort)) {
      return false;
    }
    int foundCount = 0;
    for (Assignments assignment : patrol.readSortedAssignments(year, month + 1)) {
      if (assignment.getPosIndex(userId) >= 0 && (assignment.isDayShift() || assignment.isSwingShift())) {
        foundCount++;
      }
    }
    return foundCount >= MAX_DAY_SHIFTS_PER_MONTH;
  }

  static String buildDateAndPosString(int year, int month, int dayOfMonth, int pos) {
    StringBuilder sb = new StringBuilder();
    sb.append(year).append("-");
    if (month + 1 < 10) sb.append("0");
    sb.append(month + 1).append("-");
    if (dayOfMonth < 10) sb.append("0");
    sb.append(dayOfMonth);
    sb.append("_").append(PatrolData.IndexToString(pos));
    return sb.toString();
  }

  private int parseIntParam(String param, int defaultValue) {
    try {
      return (param != null) ? Integer.parseInt(param) : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
