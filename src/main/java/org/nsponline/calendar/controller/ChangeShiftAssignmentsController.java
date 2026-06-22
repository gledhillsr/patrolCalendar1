package org.nsponline.calendar.controller;

import org.apache.commons.lang3.StringUtils;
import org.nsponline.calendar.store.*;
import org.nsponline.calendar.utils.*;
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
      sortedRoster.add(lastFirst);
      numToName.put(nextMember.getID(), lastFirst);
    }

    // Read today's assignment groups
    List<Assignments> assignmentGroups = patrolData.readSortedAssignments(year, month + 1, dayOfMonth);

    // Find the person currently occupying the requested position
    String newName = "";
    String newName1 = "";
    String newIdNumber = "0";
    boolean posWasEmpty = true;

    for (int ag = 0; ag < assignmentGroups.size(); ag++) {
      Assignments shift = assignmentGroups.get(ag);
      for (int ow = 0; ow < shift.getCount(); ow++) {
        String id = shift.getPosID(ow);
        if (!id.isEmpty() && id.charAt(0) == '-') {
          id = id.substring(1); // strip missed-shift marker
        }
        if ((ag + 1) == pos && ow == index) {
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
      if (posWasEmpty && !outsideEarlyLimit && !isNightShiftForPos) {
        replaceWithSomeoneElseRadioIndex++; // "Insert myself" appears first
      } else if (!posWasEmpty && !editingMyself && !outsideEarlyLimit) {
        replaceWithSomeoneElseRadioIndex++; // "Replace with myself" appears first
      }
    }

    patrolData.close();

    String date1 = buildDateAndPosString(year, month, dayOfMonth, pos);

    model.addAttribute("resort", resort);
    model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
    model.addAttribute("dayOfWeekName", StaticUtils.szDays[dayOfWeek]);
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
    model.addAttribute("date1", date1);
    model.addAttribute("maxLimitedShiftsThisMonth", MAX_DAY_SHIFTS_PER_MONTH);
    model.addAttribute("replaceWithSomeoneElseRadioIndex", replaceWithSomeoneElseRadioIndex);

    return "change-shift-assignments";
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
