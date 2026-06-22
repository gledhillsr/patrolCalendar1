package org.nsponline.calendar.controller;

import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Calendar;

@Controller
public class SetupListPatrollersController {

  @GetMapping("/SetupListPatrollers")
  public String showSetupForm(HttpServletRequest request, HttpServletResponse response,
                              Model model) throws IOException {

    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

    String resort = request.getParameter("resort");
    Logger LOG = new Logger(SetupListPatrollersController.class, javaxRequest, "GET", resort, Logger.INFO);

    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Resort not found: " + resort);
      return null;
    }

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    ValidateCredentialsRedirectIfNeeded credentials =
      new ValidateCredentialsRedirectIfNeeded(
        sessionData, javaxRequest, javaxResponse,
        "SetupListPatrollersController", patrolData, LOG);

    if (credentials.hasInvalidCredentials()) {
      patrolData.close();
      return null;
    }

    String userId = sessionData.getLoggedInUserId();
    boolean isDirector = false;
    if (userId != null) {
      Roster patroller = patrolData.getMemberByID(userId);
      if (patroller != null) {
        isDirector = patroller.isDirector();
      }
      if (userId.equals(sessionData.getBackDoorUser())) {
        isDirector = true;
      }
    }
    patrolData.close();

    int year = Calendar.getInstance().get(Calendar.YEAR);

    model.addAttribute("resort", resort);
    model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
    model.addAttribute("userId", userId);
    model.addAttribute("isDirector", isDirector);
    model.addAttribute("isBrighton", "Brighton".equalsIgnoreCase(resort));
    model.addAttribute("currentYear", year);
    model.addAttribute("startYear", year - 2);
    model.addAttribute("endYear", year + 2);

    return "setup-list-patrollers";
  }
}
