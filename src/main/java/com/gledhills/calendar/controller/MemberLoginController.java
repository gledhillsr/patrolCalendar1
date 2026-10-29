package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.*;
import com.gledhills.calendar.store.*;
import com.gledhills.calendar.utils.*;
import com.gledhills.calendar.store.PasswordResetToken;
import com.gledhills.calendar.store.Roster;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Controller
public class MemberLoginController {

  /**
   * Shared across requests on purpose - the counters only mean anything if every attempt lands on the
   * same instance.  The controller is a singleton, so static fields are enough.
   * <p>
   * Login and reset get separate limiters so a member locked out of one is not locked out of the
   * other - somebody who mistyped their password five times still needs the reset link to work.
   */
  private static final AttemptRateLimiter RATE_LIMITER = AttemptRateLimiter.forFailedLogins();
  private static final AttemptRateLimiter RESET_RATE_LIMITER = AttemptRateLimiter.forPasswordResets();

  /**
   * Populate the model the login page needs and return its view name.  The caller sets the
   * errorMessage itself, so the same page can be re-shown for different reasons.
   */
  private String renderLoginFormWithError(Model model, String resort, String gotoPage, Logger LOG) {
    model.addAttribute("resort", resort);
    model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
    model.addAttribute("gotoPage", gotoPage);
    model.addAttribute("showSampleCredentials", PatrolData.isDemoResort(resort));
    model.addAttribute("logoutLink", "");

    ResortData resortData = PatrolData.getResortInfo(resort);
    if (resortData != null) {
      model.addAttribute("resortUrl", resortData.getResortURL());
      model.addAttribute("resortImage", resortData.getResortImage());
      model.addAttribute("resortImgHeight", resortData.getImageHeight());
    }
    return "member-login";
  }

  /**
   * What to tell somebody whose sign-in just failed.
   * <p>
   * The generic wording is right when a member number or a recognised address was given with the
   * wrong password.  It is useless when the Member ID box holds an email address the site cannot
   * place - the member has no way to tell that the box wanted something else, which is exactly how
   * three of them ended up requesting reset after reset while every reset succeeded.
   * <p>
   * The unknown-address wording names the resort, and so admits that the address is not on this
   * roster.  That is the posture the password reset form on this same page already takes, with the
   * same rate limiter in front of it, so it discloses nothing that was not already obtainable -
   * and a login hint vaguer than the reset message six inches below it would help nobody.
   */
  private String loginFailureMessage(LoginIdentifier identifier, String resort, Logger LOG) {
    return switch (identifier.resolution()) {
      case NOT_AN_EMAIL, RESOLVED -> "Invalid member ID or password. Please try again.";

      case UNKNOWN_EMAIL -> "The email address \"" + identifier.typed() + "\" is not on the " +
        PatrolData.getResortFullName(resort, LOG) + " roster." +
        "\nYou can also sign in with your patrol member number - it is in your password reset email.";

      case AMBIGUOUS_EMAIL -> "That email address belongs to more than one member here, so it cannot " +
        "say which of you is signing in." +
        "\nPlease sign in with your patrol member number - it is in your password reset email.";
    };
  }

  @GetMapping("/MemberLogin")
  public String showLoginForm(HttpServletRequest request, HttpServletResponse response,
                              Model model) throws IOException {
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String gotoPage = request.getParameter("NSPgoto");

    // 2. Validate resort
    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Invalid resort: " + resort);
      return null;
    }

    // 3. Set defaults
    if (StaticUtils.isEmpty(gotoPage)) {
      gotoPage = "MonthCalendar";
    }

    // 4. Use ServletAdapter for legacy compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "GET", resort, Logger.INFO);

    // 5. Prepare model
    model.addAttribute("resort", resort);
    model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
    model.addAttribute("gotoPage", gotoPage);
    model.addAttribute("showSampleCredentials", PatrolData.isDemoResort(resort));

    // 5a. Add resort metadata for header
    ResortData resortData = PatrolData.getResortInfo(resort);
    if (resortData != null) {
      model.addAttribute("resortUrl", resortData.getResortURL());
      model.addAttribute("resortImage", resortData.getResortImage());
      model.addAttribute("resortImgHeight", resortData.getImageHeight());
    }
    model.addAttribute("logoutLink", "");

    // 6. Return template
    return "member-login";
  }

  @GetMapping("/LoginHelp")
  public String showLoginHelp(HttpServletRequest request, HttpServletResponse response,
                              Model model) throws IOException {
    // Same login page, but with the "If you forgot your password" section visible
    String view = showLoginForm(request, response, model);
    model.addAttribute("showPasswordReset", true);
    return view;
  }

  @PostMapping("/LoginHelp")
  public String processLogin(HttpServletRequest request, HttpServletResponse response,
                             Model model) throws IOException {
    // Check if this is a password reset request or login attempt
    String emailme = request.getParameter("emailme");

    if (emailme != null) {
      return handlePasswordResetRequest(request, response, model);
    }
    else {
      return handleLoginAttempt(request, response, model);
    }
  }

  @GetMapping("/ResetPassword")
  public String showResetForm(HttpServletRequest request, HttpServletResponse response,
                              Model model) throws IOException {
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String token = request.getParameter("token");

    // 2. Validate resort
    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Invalid resort");
      return null;
    }

    // 3. Validate token exists
    if (StaticUtils.isEmpty(token)) {
      model.addAttribute("errorMessage", "Invalid or missing reset token");
      return "redirect:/MemberLogin?resort=" + resort;
    }

    // 4. Use ServletAdapter for legacy code
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "GET", resort, Logger.INFO);

    // 4b. Throttle token guessing.  There is no member identity to key on until the token resolves,
    //     so this rides on the source address counter alone.
    String remoteAddress = request.getRemoteAddr();
    if (RESET_RATE_LIMITER.isBlocked(resort, null, remoteAddress)) {
      LOG.error("reset token lookup blocked by rate limit: resort=" + resort + ", from=" + remoteAddress);
      response.sendError(429, "Too many attempts, please try again later");
      return null;
    }

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    try {
      Connection conn = patrolData.getConnection();

      // 5. Validate token
      PasswordResetToken resetToken = PasswordResetToken.findByToken(LOG, conn, resort, token);

      if (resetToken == null || !resetToken.isValid()) {
        //only bad tokens are counted, so following a real reset link never eats into the budget
        RESET_RATE_LIMITER.recordAttempt(resort, null, remoteAddress);
        model.addAttribute("errorMessage",
                           "This reset link has expired or is invalid. Please request a new password reset.");
        return "redirect:/MemberLogin?resort=" + resort;
      }

      // 6. Prepare model
      model.addAttribute("resort", resort);
      model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
      model.addAttribute("token", token);

      return "reset-password";

    }
    catch (Exception e) {
      LOG.logException("Error validating reset token", e);
      model.addAttribute("errorMessage", "An error occurred. Please try again.");
      return "redirect:/MemberLogin?resort=" + resort;
    }
    finally {
      patrolData.close();
    }
  }

  @PostMapping("/ResetPassword")
  public String processPasswordReset(HttpServletRequest request, HttpServletResponse response,
                                     Model model) throws IOException {
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String token = request.getParameter("token");
    String newPassword = request.getParameter("newPassword");
    String confirmPassword = request.getParameter("confirmPassword");

    // 2. Validate passwords match
    if (newPassword == null || !newPassword.equals(confirmPassword)) {
      model.addAttribute("errorMessage", "Passwords do not match");
      model.addAttribute("resort", resort);
      model.addAttribute("token", token);
      return "reset-password";
    }

    // 2b. ...and that it will fit. The column was varchar(16) with nothing checking, so a
    //     password manager's generated password reached MySQL, came back as "Data too long for
    //     column 'Password'", and the member was told only "An error occurred. Please try again."
    //     - with the reset token already spent. Say what is wrong instead.
    if (newPassword.length() > Roster.MAX_PASSWORD_LENGTH) {
      model.addAttribute("errorMessage",
                         "That password is " + newPassword.length() + " characters. Please choose one of "
                           + Roster.MAX_PASSWORD_LENGTH + " characters or fewer.");
      model.addAttribute("resort", resort);
      model.addAttribute("token", token);
      return "reset-password";
    }

    // 3. Use ServletAdapter
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "POST", resort, Logger.INFO);

    // 3b. Throttle token guessing on the submit side too, otherwise the GET limit is trivially skipped
    String remoteAddress = request.getRemoteAddr();
    if (RESET_RATE_LIMITER.isBlocked(resort, null, remoteAddress)) {
      LOG.error("password reset submit blocked by rate limit: resort=" + resort + ", from=" + remoteAddress);
      response.sendError(429, "Too many attempts, please try again later");
      return null;
    }

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    try {
      Connection conn = patrolData.getConnection();

      // 4. Validate token
      PasswordResetToken resetToken = PasswordResetToken.findByToken(LOG, conn, resort, token);

      if (resetToken == null || !resetToken.isValid()) {
        RESET_RATE_LIMITER.recordAttempt(resort, null, remoteAddress);
        model.addAttribute("errorMessage", "Invalid or expired reset token");
        return PostRedirect.view(request, "redirect:/MemberLogin?resort=" + resort);
      }

      // 5. Load member and update password
      String memberId = resetToken.getMemberId();
      Roster member = patrolData.getMemberByID(memberId);

      if (member == null) {
        model.addAttribute("errorMessage", "Member not found");
        return PostRedirect.view(request, "redirect:/MemberLogin?resort=" + resort);
      }

      // 6. Update password in database
      member.updatePassword(conn, resort, newPassword);

      // 7. Mark token as used
      resetToken.markAsUsed(LOG, conn, resort);

      LOG.info("Password reset successful for member " + memberId);

      // 8. Redirect to login with success message
      return PostRedirect.view(request, "redirect:/MemberLogin?resort=" + resort +
        "&successMessage=Password reset successful. Please login with your new password.");

    }
    catch (Exception e) {
      LOG.logException("Error processing password reset", e);
      model.addAttribute("errorMessage", "An error occurred. Please try again.");
      model.addAttribute("resort", resort);
      model.addAttribute("token", token);
      return "reset-password";
    }
    finally {
      patrolData.close();
    }
  }

  private String handleLoginAttempt(HttpServletRequest request, HttpServletResponse response,
                                    Model model) throws IOException {
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String id = request.getParameter("ID");
    String password = request.getParameter("Password");
    String gotoPage = request.getParameter("NSPgoto");

    if (StaticUtils.isEmpty(gotoPage)) {
      gotoPage = "MonthCalendar";
    }

    // 2. Use ServletAdapter for legacy code compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "POST", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    String remoteAddress = request.getRemoteAddr();

    try {
      // 3. Clear any existing session
      sessionData.clearLoggedInResort();
      sessionData.clearLoggedInUserId();

      // 3b. Refuse to even check the password once this member id or source address has failed
      //     too often, so the weak password hashing cannot be brute forced online
      if (RATE_LIMITER.isBlocked(resort, id, remoteAddress)) {
        long waitSeconds = RATE_LIMITER.secondsUntilUnblocked(resort, id, remoteAddress);
        LOG.error("login blocked by rate limit: resort=" + resort + ", ID=[" + id + "], from=" + remoteAddress +
                    ", retry in " + waitSeconds + "s");
        model.addAttribute("errorMessage",
                           "Too many failed sign-in attempts. Please try again in " +
                             ((waitSeconds + 59) / 60) + " minute(s).");
        return renderLoginFormWithError(model, resort, gotoPage, LOG);
      }

      // 3c. Accept an email address as well as a member number.  A fifth of every failed sign-in
      //     here is somebody typing their address into the Member ID box, and a password reset
      //     cannot fix an identifier that was never wrong - three members reset repeatedly,
      //     successfully every time, and still could not get in.
      LoginIdentifier identifier = patrolData.resolveLoginIdentifier(id, sessionData);
      String memberId = identifier.memberId();

      // 4. Validate credentials
      if (patrolData.isValidLogin(null, resort, memberId, password, sessionData)) {
        // Success - set session and redirect.  Note the *resolved* id throughout: the rest of the
        // site is keyed on the member number, so signing in by email must not put an address into
        // the session or onto the next page's query string.
        RATE_LIMITER.recordSuccess(resort, id);
        sessionData.setLoggedInUserId(memberId);
        sessionData.setLoggedInResort(resort);

        String redirectUrl = PatrolData.SERVLET_URL + gotoPage +
          "?resort=" + resort + "&ID=" + memberId;
        //303, not sendRedirect's 302: gotoPage only answers GET, and a client that repeats the
        //POST - which 302 permits - is shown a 405 just as it thinks it has signed in
        PostRedirect.sendTo(response, redirectUrl);
        return null; // redirect already sent

      }
      else {
        RATE_LIMITER.recordAttempt(resort, id, remoteAddress);
        // Failure - show error on login form, and offer the password reset section
        model.addAttribute("showPasswordReset", true);
        model.addAttribute("errorMessage",
                           loginFailureMessage(identifier, resort, LOG));
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName",
                           PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("gotoPage", gotoPage);
        model.addAttribute("showSampleCredentials", PatrolData.isDemoResort(resort));

        // Add resort metadata for header
        ResortData resortData = PatrolData.getResortInfo(resort);
        if (resortData != null) {
          model.addAttribute("resortUrl", resortData.getResortURL());
          model.addAttribute("resortImage", resortData.getResortImage());
          model.addAttribute("resortImgHeight", resortData.getImageHeight());
        }
        model.addAttribute("logoutLink", "");

        return "member-login";
      }

    }
    finally {
      patrolData.close();
    }
  }

  /**
   * The resort's own time zone, or null when it has not set one.
   *
   * <p>Deliberately <em>not</em> {@link ResortTimeZones#inForce} - that falls back to
   * {@code America/Denver} for a resort with no answer, which would put a confident Mountain
   * clock time in front of a member three zones away. For this one purpose a missing zone has to
   * produce no time at all: "expires in 15 minutes" is always true, and a wrong wall-clock time
   * is worse than none.
   */
  private static ZoneId resortZoneOrNull(DirectorSettings settings) {
    if (settings == null) {
      return null;
    }
    String chosen = settings.getTimeZone();
    if (chosen == null || chosen.isEmpty()) {
      return null;
    }
    try {
      return ZoneId.of(chosen);
    }
    catch (Exception e) {
      //a stored value that is not a zone must not cost the member their reset email
      return null;
    }
  }

  /**
   * "This link will expire in 15 minutes. (at 4:19pm MST)", or the sentence by itself when the
   * resort has no time zone set.
   *
   * <p>The clock time comes from the token's own {@code expiresAt}, never from
   * now + {@link PasswordResetToken#LIFETIME_MINUTES} - what the member is told is then the
   * moment the server will actually enforce, however long the mail sat in a queue.
   */
  static String expiryWording(PasswordResetToken token, ZoneId zone) {
    String sentence = "This link will expire in " + PasswordResetToken.LIFETIME_MINUTES + " minutes.";
    if (zone == null || token == null || token.getExpiresAt() == null) {
      return sentence;
    }
    ZonedDateTime at = token.getExpiresAt().toInstant().atZone(zone);
    //lower-case meridiem is not something a DateTimeFormatter pattern gives you
    String clock = at.format(DateTimeFormatter.ofPattern("h:mm", Locale.US))
      + (at.getHour() < 12 ? "am" : "pm")
      + " " + at.format(DateTimeFormatter.ofPattern("zzz", Locale.US));
    return sentence + " (at " + clock + ")";
  }

  private String buildPasswordResetEmail(String memberName, String resetUrl, String resortName,
                                         String expiryWording) {
    return "Hello " + memberName + ",\n\n" +
      "You requested a password reset for your " + resortName + " patrol account.\n\n" +
      "Click the link below to reset your password:\n" +
      resetUrl + "\n\n" +
      expiryWording + "\n\n" +
      "If you didn't request this reset, please ignore this email. " +
      "Your password will remain unchanged.\n\n" +
      "For assistance, contact your patrol director.\n\n" +
      "Thanks,\n" +
      "Patrol Calendar System";
  }

  private String handlePasswordResetRequest(HttpServletRequest request, HttpServletResponse response,
                                            Model model) throws IOException {
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String id = request.getParameter("ID2");
    String email = request.getParameter("email");
    String gotoPage = request.getParameter("NSPgoto");

    // 2. Use ServletAdapter
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "POST", resort, Logger.INFO);

    // Password changes are not allowed on a demo resort - its members are invented, and Sample
    // is restored from SampleBackup nightly, so a reset would be undone at 2am anyway
    if (PatrolData.isDemoResort(resort)) {
      String demoMessage = "Password changes are not allowed on the demo resort.";
      model.addAttribute("errorMessage", demoMessage);
      model.addAttribute("popupMessage", demoMessage);
      model.addAttribute("resort", resort);
      model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
      model.addAttribute("gotoPage", gotoPage);
      model.addAttribute("showSampleCredentials", true);

      // Add resort metadata for header
      ResortData demoResortData = PatrolData.getResortInfo(resort);
      if (demoResortData != null) {
        model.addAttribute("resortUrl", demoResortData.getResortURL());
        model.addAttribute("resortImage", demoResortData.getResortImage());
        model.addAttribute("resortImgHeight", demoResortData.getImageHeight());
      }
      model.addAttribute("logoutLink", "");

      return "member-login";
    }

    // Throttle before the lookup: an accepted request sends real email to the member, and the
    // not-found message differs from the sent message, so an unthrottled endpoint is both an inbox
    // flooder and a way to enumerate which ids and addresses belong to this resort.
    String remoteAddress = request.getRemoteAddr();
    String resetSubject = !StaticUtils.isEmpty(id) ? id : email;
    if (RESET_RATE_LIMITER.isBlocked(resort, resetSubject, remoteAddress)) {
      long waitSeconds = RESET_RATE_LIMITER.secondsUntilUnblocked(resort, resetSubject, remoteAddress);
      LOG.error("password reset blocked by rate limit: resort=" + resort + ", subject=[" + resetSubject +
                  "], from=" + remoteAddress + ", retry in " + waitSeconds + "s");
      String throttledMessage = "Too many password reset requests. Please try again in " +
        ((waitSeconds + 59) / 60) + " minute(s).";
      model.addAttribute("errorMessage", throttledMessage);
      model.addAttribute("popupMessage", throttledMessage);
      model.addAttribute("showPasswordReset", true);
      return renderLoginFormWithError(model, resort, gotoPage, LOG);
    }
    //counted whether or not the member turns out to exist, so a miss is as expensive as a hit
    RESET_RATE_LIMITER.recordAttempt(resort, resetSubject, remoteAddress);

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    // Keep the reset section visible on every outcome of a reset request
    model.addAttribute("showPasswordReset", true);

    try {
      Connection conn = patrolData.getConnection();
      Roster member = null;

      // 3. Find member by ID or email
      if (!StaticUtils.isEmpty(id) && id.length() > 4) {
        member = patrolData.getMemberByID(id);
      }
      else if (!StaticUtils.isEmpty(email) && email.length() > 4) {
        member = patrolData.getMemberByEmail(email.trim());
      }

      // 4. Handle member not found
      if (member == null) {
        String notFoundMessage = (!StaticUtils.isEmpty(email) && email.length() > 4)
          ? "The email address \"" + email.trim() + "\" was not found for " +
            PatrolData.getResortFullName(resort, LOG) +
            "\nPlease contact your patrol representative to resolve this."
          : "No member found with that ID or email address.";
        model.addAttribute("errorMessage", notFoundMessage);
        model.addAttribute("popupMessage", notFoundMessage);
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("gotoPage", gotoPage);
        model.addAttribute("showSampleCredentials", PatrolData.isDemoResort(resort));

        // Add resort metadata for header
        ResortData resortData = PatrolData.getResortInfo(resort);
        if (resortData != null) {
          model.addAttribute("resortUrl", resortData.getResortURL());
          model.addAttribute("resortImage", resortData.getResortImage());
          model.addAttribute("resortImgHeight", resortData.getImageHeight());
        }
        model.addAttribute("logoutLink", "");

        return "member-login";
      }

      // 5. Validate member has email
      String memberEmail = member.getEmailAddress();
      if (memberEmail == null || !memberEmail.contains("@")) {
        model.addAttribute("errorMessage",
                           "No valid email address found for this member. Please contact your patrol director.");
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("gotoPage", gotoPage);
        model.addAttribute("showSampleCredentials", PatrolData.isDemoResort(resort));

        // Add resort metadata for header
        ResortData resortData = PatrolData.getResortInfo(resort);
        if (resortData != null) {
          model.addAttribute("resortUrl", resortData.getResortURL());
          model.addAttribute("resortImage", resortData.getResortImage());
          model.addAttribute("resortImgHeight", resortData.getImageHeight());
        }
        model.addAttribute("logoutLink", "");

        return "member-login";
      }

      // 6. Generate reset token
      PasswordResetToken resetToken = PasswordResetToken.createToken(member.getID());
      resetToken.save(LOG, conn, resort);

      // 7. Build reset URL
      String baseUrl = request.getRequestURL().toString().replace("/LoginHelp", "");
      String resetUrl = baseUrl + "/ResetPassword?resort=" + resort +
        "&token=" + resetToken.getToken();

      // 8. Send email
      String subject = "Password Reset Request for " + PatrolData.getResortFullName(resort, LOG);
      //the resort's own zone, from directorsettings; no zone means no clock time in the email
      ZoneId resortZone = resortZoneOrNull(patrolData.readDirectorSettings());
      String message = buildPasswordResetEmail(member.getFullName(), resetUrl,
                                               PatrolData.getResortFullName(resort, LOG),
                                               expiryWording(resetToken, resortZone));

      MailMan mailMan = new MailMan(memberEmail, sessionData, LOG);
      boolean emailSent = mailMan.sendMessage(subject, message, memberEmail);

      if (!emailSent) {
        LOG.error("Password reset email could NOT be sent to " + member.getFullName() + " at " + memberEmail);
        String failMessage = "Server error: the password reset email could not be sent." +
          "\nPlease try again later, or contact your patrol representative.";
        model.addAttribute("errorMessage", failMessage);
        model.addAttribute("popupMessage", failMessage);
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("gotoPage", gotoPage);
        model.addAttribute("showSampleCredentials", PatrolData.isDemoResort(resort));

        // Add resort metadata for header
        ResortData failResortData = PatrolData.getResortInfo(resort);
        if (failResortData != null) {
          model.addAttribute("resortUrl", failResortData.getResortURL());
          model.addAttribute("resortImage", failResortData.getResortImage());
          model.addAttribute("resortImgHeight", failResortData.getImageHeight());
        }
        model.addAttribute("logoutLink", "");

        return "member-login";
      }

      LOG.info("Password reset email sent to " + member.getFullName() + " at " + memberEmail);

      // 9. Show success message
      model.addAttribute("successMessage",
                         "An email with password reset instructions has been sent to " + memberEmail);
      model.addAttribute("resort", resort);
      model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
      model.addAttribute("gotoPage", gotoPage);
      model.addAttribute("showSampleCredentials", PatrolData.isDemoResort(resort));

      // Add resort metadata for header
      ResortData resortData = PatrolData.getResortInfo(resort);
      if (resortData != null) {
        model.addAttribute("resortUrl", resortData.getResortURL());
        model.addAttribute("resortImage", resortData.getResortImage());
        model.addAttribute("resortImgHeight", resortData.getImageHeight());
      }
      model.addAttribute("logoutLink", "");

      return "member-login";

    }
    catch (Exception e) {
      LOG.logException("Error sending password reset email: ", e);
      model.addAttribute("errorMessage",
                         "An error occurred while processing your request. Please try again or contact your patrol director.");
      model.addAttribute("resort", resort);
      model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
      model.addAttribute("gotoPage", gotoPage);
      model.addAttribute("showSampleCredentials", PatrolData.isDemoResort(resort));

      // Add resort metadata for header
      ResortData resortData = PatrolData.getResortInfo(resort);
      if (resortData != null) {
        model.addAttribute("resortUrl", resortData.getResortURL());
        model.addAttribute("resortImage", resortData.getResortImage());
        model.addAttribute("resortImgHeight", resortData.getImageHeight());
      }
      model.addAttribute("logoutLink", "");

      return "member-login";

    }
    finally {
      patrolData.close();
    }
  }
}
