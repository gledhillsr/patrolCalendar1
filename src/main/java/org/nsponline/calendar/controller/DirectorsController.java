package org.nsponline.calendar.controller;

import org.nsponline.calendar.controller.ServletAdapter;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.nsponline.calendar.utils.ResortData;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot controller for directors menu.
 * Migrated from OuterDirector servlet.
 *
 * Handles GET /Directors - displays database maintenance menu for patrol directors
 */
@Controller
public class DirectorsController {

  @GetMapping("/Directors")
  public String showDirectorsMenu(HttpServletRequest request, HttpServletResponse response,
                                  Model model) throws IOException {
    // 1. Read resort parameter
    String resort = request.getParameter("resort");

    // 2. Use ServletAdapter for legacy code compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(DirectorsController.class, javaxRequest, "GET", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    try {
      // 3. Get logged-in user info
      String patrollerId = sessionData.getLoggedInUserId();

      // TODO: Add proper Spring Security authentication check here
      if (patrollerId == null || patrollerId.isEmpty()) {
        response.sendRedirect("MemberLogin?resort=" + resort);
        return null;
      }

      // 5. Read roster and check director status
      RosterData rosterData = readRoster(patrollerId, sessionData, patrolData);

      // 6. Prepare model for template
      ResortData resortData = patrolData.getResortInfo();
      model.addAttribute("resort", resort);
      model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
      model.addAttribute("patrollerId", patrollerId);
      model.addAttribute("isDirector", rosterData.isDirector);
      model.addAttribute("myName", rosterData.myName);
      model.addAttribute("sortedRoster", rosterData.sortedRoster);

      // Header attributes
      model.addAttribute("resortUrl", resortData.getResortURL());
      model.addAttribute("resortImage", resortData.getResortImage());
      model.addAttribute("resortImgHeight", resortData.getImageHeight());
      model.addAttribute("logoutLink", "<a href='/calendar-1/MemberLogout?resort=" + resort + "' target='_self'>Logout</a>");

      return "directors-menu";

    }
    catch (Exception e) {
      LOG.logException("Error displaying directors menu: ", e);
      response.sendError(500, "Error displaying directors menu");
      return null;

    }
    finally {
      patrolData.close();
    }
  }

  /**
   * Read roster data and determine director status
   */
  private RosterData readRoster(String readID, SessionData sessionData, PatrolData patrolData) {
    RosterData data = new RosterData();
    ResultSet rosterResults = patrolData.resetRoster();

    List<String> roster = new ArrayList<>();
    Roster member;
    while ((member = patrolData.nextMember("", rosterResults)) != null) {
      roster.add(member.getLast() + ", " + member.getFirst());
    }
    data.sortedRoster = roster;

    if (readID.equalsIgnoreCase(sessionData.getBackDoorUser())) {
      data.isDirector = true;
    }
    else {
      member = patrolData.getMemberByID(readID);
      if (member != null) {
        data.myName = member.getLast() + ", " + member.getFirst();
        data.isDirector = member.isDirector();
      }
    }

    return data;
  }

  /**
   * Data structure for roster information
   */
  private static class RosterData {
    boolean isDirector = false;
    String myName = null;
    List<String> sortedRoster = new ArrayList<>();
  }
}
