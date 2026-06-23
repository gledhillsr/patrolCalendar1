package org.nsponline.calendar.controller;

import org.nsponline.calendar.store.Assignments;
import org.nsponline.calendar.store.DirectorSettings;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

@Controller
public class PurgeAssignmentsController {

  @GetMapping("/PurgeAssignments")
  public String showPurgeForm(HttpServletRequest request, HttpServletResponse response,
                              Model model) throws IOException {
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

    String resort = request.getParameter("resort");
    Logger LOG = new Logger(PurgeAssignmentsController.class, javaxRequest, "GET", resort, Logger.INFO);

    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Resort not found: " + resort);
      return null;
    }

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    ValidateCredentialsRedirectIfNeeded credentials =
      new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                             "PurgeAssignmentsController", patrolData, LOG);
    if (credentials.hasInvalidCredentials()) {
      patrolData.close();
      return null;
    }

    String loggedInUserId = sessionData.getLoggedInUserId();
    if (!isDirector(loggedInUserId, sessionData, patrolData)) {
      patrolData.close();
      response.sendRedirect("/calendar-1/Directors?resort=" + resort + "&ID=" + loggedInUserId);
      return null;
    }

    DirectorSettings ds = patrolData.readDirectorSettings();
    int startDay = ds.getStartDay();
    int startMonth = ds.getStartMonth();
    int startYear = Calendar.getInstance().get(Calendar.YEAR);
    patrolData.close();

    List<Integer> years = new ArrayList<>();
    for (int i = 0; i < 4; i++) years.add(startYear - i);

    model.addAttribute("resort", resort);
    model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
    model.addAttribute("userId", loggedInUserId);
    model.addAttribute("startDay", startDay);
    model.addAttribute("startMonth", startMonth);
    model.addAttribute("startYear", startYear);
    model.addAttribute("years", years);
    model.addAttribute("directorsUrl", "/calendar-1/Directors?resort=" + resort + "&ID=" + loggedInUserId);

    return "purge-assignments";
  }

  @PostMapping("/PurgeAssignments")
  public String processPurge(HttpServletRequest request, HttpServletResponse response) throws IOException {
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

    String resort = request.getParameter("resort");
    Logger LOG = new Logger(PurgeAssignmentsController.class, javaxRequest, "POST", resort, Logger.INFO);

    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Resort not found: " + resort);
      return null;
    }

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    ValidateCredentialsRedirectIfNeeded credentials =
      new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                             "PurgeAssignmentsController", patrolData, LOG);
    if (credentials.hasInvalidCredentials()) {
      patrolData.close();
      return null;
    }

    String loggedInUserId = sessionData.getLoggedInUserId();
    if (!isDirector(loggedInUserId, sessionData, patrolData)) {
      patrolData.close();
      return "redirect:/Directors?resort=" + resort + "&ID=" + loggedInUserId;
    }

    int startDay   = parseIntParam(request.getParameter("startDay"), 1);
    int startMonth = parseIntParam(request.getParameter("startMonth"), 1);
    int startYear  = parseIntParam(request.getParameter("startYear"), Calendar.getInstance().get(Calendar.YEAR));

    LOG.info("Purging ALL shifts on and before " + startDay + "/" + startMonth + "/" + startYear);

    ResultSet assignmentResults = patrolData.resetAssignments();
    Assignments assignment;
    while ((assignment = patrolData.readNextAssignment(assignmentResults)) != null) {
      int year  = assignment.getYear();
      int month = assignment.getMonth();
      int day   = assignment.getDay();
      if ((year < startYear) ||
          (year == startYear && month < startMonth) ||
          (year == startYear && month == startMonth && day < startDay)) {
        patrolData.deleteAssignment(assignment);
      }
    }

    patrolData.close();
    return "redirect:/Directors?resort=" + resort + "&ID=" + loggedInUserId;
  }

  private boolean isDirector(String userId, SessionData sessionData, PatrolData patrolData) {
    if (userId.equalsIgnoreCase(sessionData.getBackDoorUser())) return true;
    Roster member = patrolData.getMemberByID(userId);
    return member != null && member.isDirector();
  }

  private int parseIntParam(String param, int defaultValue) {
    try {
      return (param != null) ? Integer.parseInt(param) : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
