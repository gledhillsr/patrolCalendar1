package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.RosterSelection;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.PatrolData;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;

/**
 * Turns the ListPatrollers query parameters into the list of members to show.
 * Shared by ListPatrollersController and DownloadRosterController so the spreadsheet a
 * director downloads holds exactly the rows and columns the page displayed.
 */
@Service
public class RosterQueryService {

  private static final String[] CLASSIFICATION_CODES =
    { "BAS", "INA", "SR", "SRA", "ALM", "PRO", "AUX", "TRA", "CAN", "OTH" };

  /**
   * Read the filters and column choices out of the request, then load the matching members.
   *
   * @param request    request carrying the ListPatrollers query parameters
   * @param patrolData open connection for the resort being listed
   * @return the members to list plus the settings used to select them
   */
  public RosterSelection select(HttpServletRequest request, PatrolData patrolData) {
    Map<String, Boolean> displayFlags = readDisplayFlags(request);
    Map<String, Integer> dateRange = readDateRange(request);
    Map<String, String> sortOptions = readSortOptions(request);
    List<String> classifications = readClassifications(request);
    int commitment = readCommitmentFlags(request);
    int instructorFlags = readInstructorFlags(request);
    boolean listAll = request.getParameter("ALL") != null;
    boolean listDirector = request.getParameter("ListDirector") != null;
    boolean firstNameFirst = !"LAST".equals(request.getParameter("NAME"));
    int textFontSize = parseIntParam(request.getParameter("FontSize"), 14);

    String sortString = buildSortString(sortOptions, firstNameFirst);
    List<Roster> members = loadMembers(patrolData, sortString, classifications,
                                       commitment, listAll, listDirector, instructorFlags);

    if (anyShiftCountRequested(displayFlags)) {
      loadAssignments(patrolData, members, displayFlags, dateRange,
                      classifications, commitment, listAll, listDirector, instructorFlags);
    }

    if ("shiftCnt".equals(sortOptions.get("sort1"))) {
      members = sortByShiftCount(members, dateRange);
    }
    else if (dateRange.get("useMinDays") == 1) {
      members = applyMinDaysFilter(members, dateRange);
    }

    return new RosterSelection(members, displayFlags, dateRange, sortOptions, classifications,
                               commitment, instructorFlags, listAll, listDirector,
                               firstNameFirst, textFontSize);
  }

  public Map<String, Boolean> readDisplayFlags(HttpServletRequest request) {
    Map<String, Boolean> flags = new HashMap<>();
    flags.put("showClass", request.getParameter("CLASS") != null);
    flags.put("showID", request.getParameter("SHOW_ID") != null);
    flags.put("showBlank", request.getParameter("SHOW_BLANK") != null);
    flags.put("showBlankWide", request.getParameter("SHOW_BLANK2") != null);
    flags.put("showSpouse", request.getParameter("SPOUSE") != null);
    flags.put("showAddr", request.getParameter("ADDR") != null);
    flags.put("showCity", request.getParameter("CITY") != null);
    flags.put("showState", request.getParameter("STATE") != null);
    flags.put("showZip", request.getParameter("ZIP") != null);
    flags.put("showHome", request.getParameter("HOME") != null);
    flags.put("showWork", request.getParameter("WORK") != null);
    flags.put("showCell", request.getParameter("CELL") != null);
    flags.put("showPager", request.getParameter("PAGER") != null);
    flags.put("showEmail", request.getParameter("EMAIL") != null);
    flags.put("showEmergency", request.getParameter("EMERGENCY") != null);
    flags.put("showSubstitute", request.getParameter("SUBSTITUTE") != null);
    flags.put("showCommit", request.getParameter("COMMIT") != null);
    flags.put("showInstructor", request.getParameter("INSTRUCTOR") != null);
    flags.put("showDirector", request.getParameter("DIRECTOR") != null);
    flags.put("showLastUpdated", request.getParameter("LAST_UPDATED") != null);
    flags.put("showComments", request.getParameter("COMMENTS") != null);
    flags.put("showCreditDate", request.getParameter("LAST_CREDIT_UPDATE") != null);
    flags.put("showNightCnt", request.getParameter("NIGHT_CNT") != null);
    flags.put("showDayCnt", request.getParameter("DAY_CNT") != null);
    flags.put("showSwingCnt", request.getParameter("SWING_CNT") != null);
    flags.put("showTrainingCnt", request.getParameter("TRAINING_CNT") != null);
    flags.put("showOtherCnt", request.getParameter("OTHER_CNT") != null);
    flags.put("showHolidayCnt", request.getParameter("HOLIDAY_CNT") != null);
    flags.put("showNightList", request.getParameter("NIGHT_DETAILS") != null);
    flags.put("showSwingList", request.getParameter("SWING_DETAILS") != null);
    flags.put("showDayList", request.getParameter("DAY_DETAILS") != null);
    flags.put("showTrainingList", request.getParameter("TRAINING_DETAILS") != null);
    flags.put("showOtherList", request.getParameter("OTHER_DETAILS") != null);
    flags.put("showHolidayList", request.getParameter("HOLIDAY_DETAILS") != null);
    flags.put("showTeamLead", request.getParameter("TEAM_LEAD") != null);
    flags.put("showMentoring", request.getParameter("MENTORING") != null);
    flags.put("showCreditsEarned", request.getParameter("CREDITS_EARNED") != null);
    flags.put("showCanEarnCredits", request.getParameter("CAN_EARN_CREDITS") != null);
    return flags;
  }

  public Map<String, Integer> readDateRange(HttpServletRequest request) {
    Map<String, Integer> range = new HashMap<>();
    range.put("startDay", parseIntParam(request.getParameter("StartDay"), 0));
    range.put("startMonth", parseIntParam(request.getParameter("StartMonth"), 0));
    range.put("startYear", parseIntParam(request.getParameter("StartYear"), 0));
    range.put("endDay", parseIntParam(request.getParameter("EndDay"), 0));
    range.put("endMonth", parseIntParam(request.getParameter("EndMonth"), 0));
    range.put("endYear", parseIntParam(request.getParameter("EndYear"), 0));
    range.put("minDays", parseIntParam(request.getParameter("MinDays"), 0));
    range.put("useMinDays", request.getParameter("MIN_DAYS") != null ? 1 : 0);
    return range;
  }

  public Map<String, String> readSortOptions(HttpServletRequest request) {
    Map<String, String> sort = new HashMap<>();
    sort.put("sort1", request.getParameter("FirstSort"));
    sort.put("sort2", request.getParameter("SecondSort"));
    sort.put("sort3", request.getParameter("ThirdSort"));
    return sort;
  }

  public List<String> readClassifications(HttpServletRequest request) {
    List<String> classifications = new ArrayList<>();
    for (String classification : CLASSIFICATION_CODES) {
      if (request.getParameter(classification) != null) {
        classifications.add(classification);
      }
    }
    return classifications;
  }

  public int readCommitmentFlags(HttpServletRequest request) {
    int commitment = 0;
    if (request.getParameter("FullTime") != null) {
      commitment += 4;
    }
    if (request.getParameter("PartTime") != null) {
      commitment += 2;
    }
    if (request.getParameter("Inactive") != null) {
      commitment += 1;
    }
    return commitment;
  }

  public int readInstructorFlags(HttpServletRequest request) {
    int flags = 0;
    if (request.getParameter("OEC") != null) {
      flags += 1;
    }
    if (request.getParameter("CPR") != null) {
      flags += 2;
    }
    if (request.getParameter("Ski") != null) {
      flags += 4;
    }
    if (request.getParameter("Toboggan") != null) {
      flags += 8;
    }
    return flags;
  }

  public int parseIntParam(String param, int defaultValue) {
    try {
      return (param != null) ? Integer.parseInt(param) : defaultValue;
    }
    catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /** True when at least one shift-count column was requested, so assignments must be read. */
  public boolean anyShiftCountRequested(Map<String, Boolean> flags) {
    return flags.get("showNightCnt") || flags.get("showDayCnt") ||
      flags.get("showSwingCnt") || flags.get("showTrainingCnt") ||
      flags.get("showOtherCnt") || flags.get("showHolidayCnt");
  }

  public String buildSortString(Map<String, String> sort, boolean firstNameFirst) {
    String sort1 = sort.get("sort1");
    String sortString;

    if (sort1 == null || sort1.equals("Name") || sort1.equals("shiftCnt")) {
      sortString = firstNameFirst ? "FirstName,LastName" : "LastName,FirstName";
    }
    else if (sort1.equals("Class")) {
      sortString = "ClassificationCode";
    }
    else if (sort1.equals("Comm")) {
      sortString = "Commitment";
    }
    else if (sort1.equals("Updt")) {
      sortString = "lastUpdated";
    }
    else {
      sortString = "FirstName,LastName";
    }

    String sort2 = sort.get("sort2");
    if (sort2 != null) {
      if ("Name".equals(sort2) && !"shiftCnt".equals(sort1)) {
        sortString += firstNameFirst ? ",FirstName,LastName" : ",LastName,FirstName";
      }
      else if (sort2.equals("Class")) {
        sortString += ",ClassificationCode";
      }
      else if (sort2.equals("Comm")) {
        sortString += ",Commitment";
      }
      else if (sort2.equals("Updt")) {
        sortString += ",lastUpdated";
      }
    }

    String sort3 = sort.get("sort3");
    if (sort3 != null) {
      if (sort3.equals("Name")) {
        sortString += firstNameFirst ? ",FirstName,LastName" : ",LastName,FirstName";
      }
      else if (sort3.equals("Class")) {
        sortString += ",ClassificationCode";
      }
      else if (sort3.equals("Comm")) {
        sortString += ",Commitment";
      }
      else if (sort3.equals("Updt")) {
        sortString += ",lastUpdated";
      }
    }

    return sortString;
  }

  public List<Roster> loadMembers(
    PatrolData patrol, String sortString,
    List<String> classifications, int commitment,
    boolean listAll, boolean listDirector, int instructorFlags) {

    List<Roster> members = new ArrayList<>();
    Vector<String> classVector = new Vector<>(classifications);
    ResultSet rosterResults = patrol.resetRoster(sortString);
    Roster member;

    while ((member = patrol.nextMember("&nbsp;", rosterResults)) != null) {
      if (member.okToDisplay(false, false, listAll,
                             classVector, commitment, listDirector, instructorFlags, 0)) {
        members.add(member);
      }
    }

    return members;
  }

  public void loadAssignments(
    PatrolData patrol, List<Roster> members,
    Map<String, Boolean> flags, Map<String, Integer> dateRange,
    List<String> classifications, int commitment,
    boolean listAll, boolean listDirector, int instructorFlags) {

    Hashtable<String, Roster> memberHash = new Hashtable<>();
    for (Roster member : members) {
      memberHash.put(member.getID(), member);
    }

    ResultSet assignmentResults = patrol.resetAssignments();
    GregorianCalendar date = new GregorianCalendar(
      dateRange.get("startYear"),
      dateRange.get("startMonth"),
      dateRange.get("startDay"));

    long startMillis = 0;
    long endMillis = 99999999999999L;

    if (dateRange.get("startYear") != 0) {
      startMillis = date.getTimeInMillis();
    }

    date = new GregorianCalendar(
      dateRange.get("endYear"),
      dateRange.get("endMonth"),
      dateRange.get("endDay"));

    if (dateRange.get("endYear") != 0) {
      endMillis = date.getTimeInMillis();
    }

    Vector<String> classVector = new Vector<>(classifications);
    Assignments assignment;

    while ((assignment = patrol.readNextAssignment(assignmentResults)) != null) {
      date = new GregorianCalendar(assignment.getYear(), assignment.getMonth(), assignment.getDay());
      long currMillis = date.getTimeInMillis();

      if (startMillis <= currMillis && currMillis <= endMillis) {
        for (int i = 0; i < Assignments.MAX_ASSIGNMENT_SIZE; i++) {
          Roster member = memberHash.get(assignment.getPosID(i));
          if (member != null && member.okToDisplay(false, false, listAll,
                                                   classVector, commitment, listDirector, instructorFlags, 0)) {

            if (flags.get("showDayCnt") && assignment.isDayShift()) {
              member.AssignmentCount[Assignments.DAY_TYPE]++;
              member.szAssignments[Assignments.DAY_TYPE] += assignment.getMyFormattedDate() + " ";
            }
            if (flags.get("showSwingCnt") && assignment.isSwingShift()) {
              member.AssignmentCount[Assignments.SWING_TYPE]++;
              member.szAssignments[Assignments.SWING_TYPE] += assignment.getMyFormattedDate() + " ";
            }
            if (flags.get("showNightCnt") && assignment.isNightShift()) {
              member.AssignmentCount[Assignments.NIGHT_TYPE]++;
              member.szAssignments[Assignments.NIGHT_TYPE] += assignment.getMyFormattedDate() + " ";
            }
            if (flags.get("showTrainingCnt") && assignment.isTrainingShift()) {
              member.AssignmentCount[Assignments.TRAINING_TYPE]++;
              member.szAssignments[Assignments.TRAINING_TYPE] += assignment.getMyFormattedDate() + " ";
            }
            if (flags.get("showOtherCnt") && assignment.isOtherShift()) {
              member.AssignmentCount[Assignments.OTHER_TYPE]++;
              member.szAssignments[Assignments.OTHER_TYPE] += assignment.getMyFormattedDate() + " ";
            }
            if (flags.get("showHolidayCnt") && assignment.isHolidayShift()) {
              member.AssignmentCount[Assignments.HOLIDAY_TYPE]++;
              member.szAssignments[Assignments.HOLIDAY_TYPE] += assignment.getMyFormattedDate() + " ";
            }
          }
        }
      }
    }
  }

  /** Most shifts worked first; also honors the "fewer than N days" filter. */
  public List<Roster> sortByShiftCount(List<Roster> members, Map<String, Integer> dateRange) {
    TreeMap<String, Roster> treeMap = new TreeMap<>(Collections.reverseOrder());

    for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
      Roster member = members.get(memberIndex);
      Integer totalAssignments = totalAssignments(member);
      String key = String.format("%04d-%d", totalAssignments, memberIndex);
      treeMap.put(key, member);
    }

    List<Roster> sortedMembers = new ArrayList<>();
    int minDays = dateRange.get("minDays");
    boolean useMinDays = dateRange.get("useMinDays") == 1;

    for (String key : treeMap.keySet()) {
      String cntStr = key.substring(0, 4).replaceFirst("^0+(?!$)", "");
      int currentAssignmentCount = Integer.parseInt(cntStr);

      if (!useMinDays || currentAssignmentCount < minDays) {
        sortedMembers.add(treeMap.get(key));
      }
    }

    return sortedMembers;
  }

  /** Keep only members who worked fewer than the requested number of shifts. */
  public List<Roster> applyMinDaysFilter(List<Roster> members, Map<String, Integer> dateRange) {
    List<Roster> filtered = new ArrayList<>();
    int minDays = dateRange.get("minDays");
    for (Roster member : members) {
      if (totalAssignments(member) < minDays) {
        filtered.add(member);
      }
    }
    return filtered;
  }

  private int totalAssignments(Roster member) {
    return member.AssignmentCount[Assignments.DAY_TYPE] +
      member.AssignmentCount[Assignments.SWING_TYPE] +
      member.AssignmentCount[Assignments.TRAINING_TYPE] +
      member.AssignmentCount[Assignments.NIGHT_TYPE] +
      member.AssignmentCount[Assignments.OTHER_TYPE] +
      member.AssignmentCount[Assignments.HOLIDAY_TYPE];
  }
}
