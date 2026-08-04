package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.RosterSelection;
import com.gledhills.calendar.service.RosterQueryService;
import com.gledhills.calendar.store.*;
import com.gledhills.calendar.utils.*;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Controller
public class ListPatrollersController {
  private static final boolean DEBUG = false;

  private final RosterQueryService rosterQueryService;

  public ListPatrollersController(RosterQueryService rosterQueryService) {
    this.rosterQueryService = rosterQueryService;
  }

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

    // 3. Load data. The same selection drives the roster download, so the spreadsheet a
    //    director downloads holds exactly the rows and columns rendered here.
    DirectorSettings directorSettings = patrolData.readDirectorSettings();
    Roster editor = patrolData.getMemberByID(userId);
    boolean isDirector = editor != null && editor.isDirector();

    configureRosterColumns(rosterQueryService.readDisplayFlags(request),
                           !"LAST".equals(request.getParameter("NAME")));

    RosterSelection selection = rosterQueryService.select(request, patrolData);
    Map<String, Boolean> displayFlags = selection.displayFlags();
    Map<String, Integer> dateRange = selection.dateRange();
    List<String> classificationsToDisplay = selection.classifications();
    int commitmentToDisplay = selection.commitment();
    int instructorFlags = selection.instructorFlags();
    boolean listAll = selection.listAll();
    boolean listDirector = selection.listDirector();
    boolean firstNameFirst = selection.firstNameFirst();
    int textFontSize = selection.textFontSize();
    List<Roster> members = selection.members();

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
    model.addAttribute("downloadRosterUrl", buildDownloadRosterUrl(request, resort, userId));

    return "list-patrollers";
  }

  /**
   * Link for the .xlsx download button. Every filter and column parameter this page was
   * rendered with is echoed through, so DownloadRosterController rebuilds the same table.
   * Reads the parameter map rather than the query string because SetupListPatrollers POSTs.
   */
  private String buildDownloadRosterUrl(HttpServletRequest request, String resort, String userId) {
    StringBuilder url = new StringBuilder("DownloadRoster?resort=").append(encode(resort))
      .append("&ID=").append(encode(userId));

    for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
      String name = parameter.getKey();
      if (name.equals("resort") || name.equals("ID")) {
        continue; //already set above, from the session rather than the request
      }
      for (String value : parameter.getValue()) {
        url.append("&").append(encode(name)).append("=").append(encode(value));
      }
    }

    return url.toString();
  }

  private String encode(String value) {
    return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
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
