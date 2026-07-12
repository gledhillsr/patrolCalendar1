package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.*;
import com.gledhills.calendar.utils.*;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.DirectorSettings;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.ValidateCredentialsRedirectIfNeeded;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.*;

@Controller
public class ListPatrollersController {
  private static final boolean DEBUG = false;

  @RequestMapping(value = "/ListPatrollers", method = {RequestMethod.GET, RequestMethod.POST})
  public String listPatrollers(
    HttpServletRequest request,
    HttpServletResponse response,
    Model model) throws IOException {

    // Adapt jakarta.servlet to javax.servlet for legacy classes
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

    // 1. Validate resort parameter
    String resort = request.getParameter("resort");
    Logger LOG = new Logger(ListPatrollersController.class, javaxRequest, "GET", resort, Logger.INFO);

    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Resort not found: " + resort);
      return null;
    }

    // 2. Check credentials
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    ValidateCredentialsRedirectIfNeeded credentials =
      new ValidateCredentialsRedirectIfNeeded(
        sessionData, javaxRequest, javaxResponse,
        "ListPatrollersController", patrolData, LOG);

    if (credentials.hasInvalidCredentials()) {
      patrolData.close();
      return null; // Redirect already sent by ValidateCredentialsRedirectIfNeeded
    }

    String userId = sessionData.getLoggedInUserId();

    // 3. Read all query parameters
    Map<String, Boolean> displayFlags = readDisplayFlags(request);
    Map<String, Integer> dateRange = readDateRange(request);
    Map<String, String> sortOptions = readSortOptions(request);
    List<String> classificationsToDisplay = readClassifications(request);
    int commitmentToDisplay = readCommitmentFlags(request);
    int instructorFlags = readInstructorFlags(request);
    boolean listAll = request.getParameter("ALL") != null;
    boolean listDirector = request.getParameter("ListDirector") != null;
    int textFontSize = parseIntParam(request.getParameter("FontSize"), 14);
    boolean firstNameFirst = !"LAST".equals(request.getParameter("NAME"));

    // 4. Load data
    DirectorSettings directorSettings = patrolData.readDirectorSettings();
    Roster editor = patrolData.getMemberByID(userId);
    boolean isDirector = editor != null && editor.isDirector();

    configureRosterColumns(displayFlags, firstNameFirst);

    String sortString = buildSortString(sortOptions, firstNameFirst);
    List<Roster> members = loadMembers(
      patrolData, sortString, classificationsToDisplay,
      commitmentToDisplay, listAll, listDirector, instructorFlags);

    if (displayFlags.get("showNightCnt") || displayFlags.get("showDayCnt") ||
      displayFlags.get("showSwingCnt") || displayFlags.get("showTrainingCnt") ||
      displayFlags.get("showOtherCnt") || displayFlags.get("showHolidayCnt")) {
      loadAssignments(patrolData, members, displayFlags, dateRange,
                      classificationsToDisplay, commitmentToDisplay,
                      listAll, listDirector, instructorFlags);
    }

    if ("shiftCnt".equals(sortOptions.get("sort1"))) {
      members = sortByShiftCount(members, displayFlags, dateRange);
    }
    else if (dateRange.get("useMinDays") == 1) {
      List<Roster> filtered = new ArrayList<>();
      int minDays = dateRange.get("minDays");
      for (Roster member : members) {
        int totalAssignments = member.AssignmentCount[Assignments.DAY_TYPE] +
          member.AssignmentCount[Assignments.SWING_TYPE] +
          member.AssignmentCount[Assignments.TRAINING_TYPE] +
          member.AssignmentCount[Assignments.NIGHT_TYPE] +
          member.AssignmentCount[Assignments.OTHER_TYPE] +
          member.AssignmentCount[Assignments.HOLIDAY_TYPE];
        if (totalAssignments < minDays) {
          filtered.add(member);
        }
      }
      members = filtered;
    }

    patrolData.close();

    // 5. Prepare model
    model.addAttribute("resort", resort);
    model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
    model.addAttribute("userId", userId);
    model.addAttribute("isDirector", isDirector);
    model.addAttribute("emailAllAllowed",
                       directorSettings != null && directorSettings.getEmailAll());
    model.addAttribute("members", members);
    model.addAttribute("patrollersListed", members.size());
    model.addAttribute("currentDate", new Date());
    model.addAttribute("textFontSize", textFontSize);
    model.addAttribute("firstNameFirst", firstNameFirst);
    model.addAllAttributes(displayFlags);

    String emailFormUrl = buildEmailFormUrl(resort, userId, displayFlags,
                                            classificationsToDisplay, commitmentToDisplay, listAll,
                                            listDirector, instructorFlags, dateRange);
    model.addAttribute("emailFormUrl", emailFormUrl);

    return "list-patrollers";
  }

  private Map<String, Boolean> readDisplayFlags(HttpServletRequest request) {
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

  private Map<String, Integer> readDateRange(HttpServletRequest request) {
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

  private Map<String, String> readSortOptions(HttpServletRequest request) {
    Map<String, String> sort = new HashMap<>();
    sort.put("sort1", request.getParameter("FirstSort"));
    sort.put("sort2", request.getParameter("SecondSort"));
    sort.put("sort3", request.getParameter("ThirdSort"));
    return sort;
  }

  private List<String> readClassifications(HttpServletRequest request) {
    String[] incList = { "BAS", "INA", "SR", "SRA", "ALM", "PRO", "AUX", "TRA", "CAN", "OTH" };
    List<String> classifications = new ArrayList<>();
    for (String classification : incList) {
      if (request.getParameter(classification) != null) {
        classifications.add(classification);
      }
    }
    return classifications;
  }

  private int readCommitmentFlags(HttpServletRequest request) {
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

  private int readInstructorFlags(HttpServletRequest request) {
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

  private int parseIntParam(String param, int defaultValue) {
    try {
      return (param != null) ? Integer.parseInt(param) : defaultValue;
    }
    catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private String buildSortString(Map<String, String> sort, boolean firstNameFirst) {
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

  private void configureRosterColumns(Map<String, Boolean> flags, boolean firstNameFirst) {
    Roster.addColumn(-1);
    Roster.addColumn(firstNameFirst ? Roster.FIRST : Roster.LAST);
    if (flags.get("showBlank")) {
      Roster.addColumn(Roster.BLANK);
    }
    if (flags.get("showBlankWide")) {
      Roster.addColumn(Roster.BLANK_WIDE);
    }
    if (flags.get("showClass")) {
      Roster.addColumn(Roster.CLASSIFICATION);
    }
    if (flags.get("showID")) {
      Roster.addColumn(Roster.ID_NUM);
    }
    if (flags.get("showSpouse")) {
      Roster.addColumn(Roster.SPOUSE);
    }
    if (flags.get("showAddr")) {
      Roster.addColumn(Roster.ADDRESS);
    }
    if (flags.get("showCity")) {
      Roster.addColumn(Roster.CITY);
    }
    if (flags.get("showState")) {
      Roster.addColumn(Roster.STATE);
    }
    if (flags.get("showZip")) {
      Roster.addColumn(Roster.ZIPCODE);
    }
    if (flags.get("showHome")) {
      Roster.addColumn(Roster.HOMEPHONE);
    }
    if (flags.get("showWork")) {
      Roster.addColumn(Roster.WORKPHONE);
    }
    if (flags.get("showCell")) {
      Roster.addColumn(Roster.CELLPHONE);
    }
    if (flags.get("showPager")) {
      Roster.addColumn(Roster.PAGER);
    }
    if (flags.get("showEmail")) {
      Roster.addColumn(Roster.EMAIL);
    }
    if (flags.get("showEmergency")) {
      Roster.addColumn(Roster.EMERGENCY);
    }
    if (flags.get("showSubstitute")) {
      Roster.addColumn(Roster.SUB);
    }
    if (flags.get("showCommit")) {
      Roster.addColumn(Roster.COMMITMENT);
    }
    if (flags.get("showInstructor")) {
      Roster.addColumn(Roster.INSTRUCTOR);
    }
    if (flags.get("showDirector")) {
      Roster.addColumn(Roster.DIRECTOR);
    }
    if (flags.get("showLastUpdated")) {
      Roster.addColumn(Roster.LAST_UPDATED);
    }
    if (flags.get("showComments")) {
      Roster.addColumn(Roster.COMMENTS);
    }
    if (flags.get("showCanEarnCredits")) {
      Roster.addColumn(Roster.CAN_EARN_CREDITS);
    }
    if (flags.get("showCreditsEarned")) {
      Roster.addColumn(Roster.CREDITS_EARNED);
    }
    if (flags.get("showCreditDate")) {
      Roster.addColumn(Roster.LAST_CREDIT_UPDATE);
    }
    if (flags.get("showTeamLead")) {
      Roster.addColumn(Roster.TEAM_LEAD);
    }
    if (flags.get("showMentoring")) {
      Roster.addColumn(Roster.MENTORING);
    }
    if (flags.get("showDayCnt")) {
      Roster.addColumn(Roster.SHOW_DAY_CNT);
    }
    if (flags.get("showDayList")) {
      Roster.addColumn(Roster.SHOW_DAY_LIST);
    }
    if (flags.get("showSwingCnt")) {
      Roster.addColumn(Roster.SHOW_SWING_CNT);
    }
    if (flags.get("showSwingList")) {
      Roster.addColumn(Roster.SHOW_SWING_LIST);
    }
    if (flags.get("showNightCnt")) {
      Roster.addColumn(Roster.SHOW_NIGHT_CNT);
    }
    if (flags.get("showNightList")) {
      Roster.addColumn(Roster.SHOW_NIGHT_LIST);
    }
    if (flags.get("showTrainingCnt")) {
      Roster.addColumn(Roster.SHOW_TRAINING_CNT);
    }
    if (flags.get("showTrainingList")) {
      Roster.addColumn(Roster.SHOW_TRAINING_LIST);
    }
    if (flags.get("showOtherCnt")) {
      Roster.addColumn(Roster.SHOW_OTHER_CNT);
    }
    if (flags.get("showOtherList")) {
      Roster.addColumn(Roster.SHOW_OTHER_LIST);
    }
    if (flags.get("showHolidayCnt")) {
      Roster.addColumn(Roster.SHOW_HOLIDAY_CNT);
    }
    if (flags.get("showHolidayList")) {
      Roster.addColumn(Roster.SHOW_HOLIDAY_LIST);
    }
  }

  private List<Roster> loadMembers(
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

  private void loadAssignments(
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

  private List<Roster> sortByShiftCount(
    List<Roster> members, Map<String, Boolean> flags,
    Map<String, Integer> dateRange) {

    TreeMap<String, Roster> treeMap = new TreeMap<>(Collections.reverseOrder());

    for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
      Roster member = members.get(memberIndex);
      Integer totalAssignments = member.AssignmentCount[Assignments.DAY_TYPE] +
        member.AssignmentCount[Assignments.SWING_TYPE] +
        member.AssignmentCount[Assignments.TRAINING_TYPE] +
        member.AssignmentCount[Assignments.NIGHT_TYPE] +
        member.AssignmentCount[Assignments.OTHER_TYPE] +
        member.AssignmentCount[Assignments.HOLIDAY_TYPE];
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

  private String buildEmailFormUrl(
    String resort, String userId, Map<String, Boolean> flags,
    List<String> classifications, int commitment,
    boolean listAll, boolean listDirector, int instructorFlags,
    Map<String, Integer> dateRange) {

    StringBuilder url = new StringBuilder("EmailForm?resort=" + resort + "&ID=" + userId);

    for (String classification : classifications) {
      url.append("&").append(classification).append("=1");
    }

    if ((commitment & 4) == 4) {
      url.append("&FullTime=1");
    }
    if ((commitment & 2) == 2) {
      url.append("&PartTime=1");
    }
    if ((commitment & 1) == 1) {
      url.append("&Inactive=1");
    }

    if (listAll) {
      url.append("&ALL=1");
    }
    if (listDirector) {
      url.append("&ListDirector=1");
    }
    if ((instructorFlags & 1) == 1) {
      url.append("&OEC=1");
    }
    if ((instructorFlags & 2) == 2) {
      url.append("&CPR=1");
    }
    if ((instructorFlags & 4) == 4) {
      url.append("&Ski=1");
    }
    if ((instructorFlags & 8) == 8) {
      url.append("&Toboggan=1");
    }

    if (dateRange.get("useMinDays") == 1 &&
      (flags.get("showDayCnt") || flags.get("showSwingCnt") || flags.get("showNightCnt"))) {
      url.append("&MIN_DAYS=1&MinDays=").append(dateRange.get("minDays"));
      if (flags.get("showDayCnt")) {
        url.append("&DAY_CNT=1");
      }
      if (flags.get("showNightCnt")) {
        url.append("&NIGHT_CNT=1");
      }
      if (flags.get("showSwingCnt")) {
        url.append("&SWING_CNT=1");
      }
      if (flags.get("showTrainingCnt")) {
        url.append("&TRAINING_CNT=1");
      }
      if (flags.get("showOtherCnt")) {
        url.append("&OTHER_CNT=1");
      }
      if (flags.get("showHolidayCnt")) {
        url.append("&HOLIDAY_CNT=1");
      }
      url.append("&StartDay=").append(dateRange.get("startDay"));
      url.append("&StartMonth=").append(dateRange.get("startMonth"));
      url.append("&StartYear=").append(dateRange.get("startYear"));
      url.append("&EndDay=").append(dateRange.get("endDay"));
      url.append("&EndMonth=").append(dateRange.get("endMonth"));
      url.append("&EndYear=").append(dateRange.get("endYear"));
    }

    return url.toString();
  }
}
