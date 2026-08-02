package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.ValidateCredentialsRedirectIfNeeded;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;


/**
 * Spring Boot controller for member logout.
 * Migrated from MemberLogout servlet.
 *
 * Handles GET /MemberLogout - clears session and redirects to calendar
 */
@Controller
public class MemberLogoutController {

  @GetMapping("/MemberLogout")
  public String logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // 1. Read resort parameter
    String resort = request.getParameter("resort");

    // 2. Clear NSP_TOKEN_NAME cookie - must match the Path the signed cookie was issued on,
    //    otherwise the browser keeps the original and logout does not actually log out
    response.addHeader("Set-Cookie",
                       ValidateCredentialsRedirectIfNeeded.buildSetCookieHeader(request.isSecure(), "", 0));

    // 3. Use ServletAdapter for legacy code compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLogoutController.class, javaxRequest, "GET", resort, Logger.INFO);
    LOG.info("deleting cookie in member logout: resort=" + resort);

    // 4. Validate resort parameter
    if (resort == null || !PatrolData.isValidResort(resort)) {
      String errorMsg = "Invalid resort: " + resort;
      LOG.error(errorMsg);
      response.sendError(400, errorMsg);
      return null;
    }

    // 5. Clear session data
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    sessionData.clearLoggedInResort();
    sessionData.clearLoggedInUserId();

    // 6. Log and redirect
    String redirectUrl = "redirect:/MonthCalendar?resort=" + resort;
    LOG.info("Logout redirect to: " + redirectUrl);
    return redirectUrl;
  }
}
