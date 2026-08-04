package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.CalendarMonth;
import com.gledhills.calendar.service.MonthCalendarService;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.ResortData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.StaticUtils;
import com.gledhills.calendar.utils.ValidateCredentialsRedirectIfNeeded;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.util.Date;

/**
 * Monthly shift calendar.
 *
 * The calendar used to be drawn by the legacy MonthCalendar servlet, whose PrintWriter output this
 * controller captured and injected into the page as raw HTML. It now builds a CalendarMonth view
 * model through MonthCalendarService and lets month-calendar.html do the drawing.
 */
@Controller
public class MonthCalendarController {

  private final MonthCalendarService monthCalendarService;

  public MonthCalendarController(MonthCalendarService monthCalendarService) {
    this.monthCalendarService = monthCalendarService;
  }

  @GetMapping("/MonthCalendar")
  public String showMonthCalendar(HttpServletRequest request, HttpServletResponse response,
                                  Model model) throws IOException {

    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

    String resort = request.getParameter("resort");
    Logger LOG = new Logger(MonthCalendarController.class, javaxRequest, "GET", resort, Logger.INFO);

    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Resort not found: " + resort);
      return null;
    }

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    try {
      ValidateCredentialsRedirectIfNeeded credentials =
        new ValidateCredentialsRedirectIfNeeded(
          sessionData, javaxRequest, javaxResponse, "MonthCalendar", patrolData, LOG);

      if (credentials.hasInvalidCredentials()) {
        return null; //redirect to MemberLogin already sent
      }

      String patrollerId = sessionData.getLoggedInUserId();
      CalendarMonth calendar = monthCalendarService.build(
        resort,
        request.getParameter("month"),
        request.getParameter("year"),
        parseTextLen(request.getParameter("textLen")),
        patrollerId,
        sessionData.getBackDoorUser(),
        patrolData,
        LOG);

      model.addAttribute("resort", resort);
      model.addAttribute("resortFullName", calendar.resortFullName());
      model.addAttribute("patrollerId", patrollerId);
      model.addAttribute("calendar", calendar);
      model.addAttribute("asOf", new Date());

      ResortData resortData = PatrolData.getResortInfo(resort);
      if (resortData != null) {
        model.addAttribute("resortUrl", resortData.getResortURL());
        model.addAttribute("resortImage", resortData.getResortImage());
        model.addAttribute("resortImgHeight", resortData.getImageHeight());
      }

      return "month-calendar";
    }
    catch (Exception e) {
      LOG.logException("Error displaying month calendar: ", e);
      response.sendError(500, "Error displaying calendar");
      return null;
    }
    finally {
      patrolData.close();
    }
  }

  /** Row-length override from the print controls; anything above 0 forces the dense layout. */
  private int parseTextLen(String textLen) {
    if (StaticUtils.isEmpty(textLen)) {
      return 0;
    }
    try {
      return Integer.parseInt(textLen);
    }
    catch (NumberFormatException e) {
      return 0;
    }
  }
}
