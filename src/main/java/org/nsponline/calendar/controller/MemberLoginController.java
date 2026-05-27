package org.nsponline.calendar.controller;

import org.nsponline.calendar.store.*;
import org.nsponline.calendar.utils.*;
import org.nsponline.calendar.utils.ResortData;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;

@Controller
public class MemberLoginController {

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
    model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));

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
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    try {
      Connection conn = patrolData.getConnection();

      // 5. Validate token
      PasswordResetToken resetToken = PasswordResetToken.findByToken(conn, resort, token);

      if (resetToken == null || !resetToken.isValid()) {
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

    // 3. Use ServletAdapter
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "POST", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    try {
      Connection conn = patrolData.getConnection();

      // 4. Validate token
      PasswordResetToken resetToken = PasswordResetToken.findByToken(conn, resort, token);

      if (resetToken == null || !resetToken.isValid()) {
        model.addAttribute("errorMessage", "Invalid or expired reset token");
        return "redirect:/MemberLogin?resort=" + resort;
      }

      // 5. Load member and update password
      String memberId = resetToken.getMemberId();
      Roster member = patrolData.getMemberByID(memberId);

      if (member == null) {
        model.addAttribute("errorMessage", "Member not found");
        return "redirect:/MemberLogin?resort=" + resort;
      }

      // 6. Update password in database
      member.updatePassword(conn, resort, newPassword);

      // 7. Mark token as used
      resetToken.markAsUsed(conn, resort);

      LOG.info("Password reset successful for member " + memberId);

      // 8. Redirect to login with success message
      return "redirect:/MemberLogin?resort=" + resort +
        "&successMessage=Password reset successful. Please login with your new password.";

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

    try {
      // 3. Clear any existing session
      sessionData.clearLoggedInResort();
      sessionData.clearLoggedInUserId();

      // 4. Validate credentials
      if (patrolData.isValidLogin(null, resort, id, password, sessionData)) {
        // Success - set session and redirect
        sessionData.setLoggedInUserId(id);
        sessionData.setLoggedInResort(resort);

        String redirectUrl = PatrolData.SERVLET_URL + gotoPage +
          "?resort=" + resort + "&ID=" + id;
        response.sendRedirect(redirectUrl);
        return null; // redirect already sent

      }
      else {
        // Failure - show error on login form
        model.addAttribute("errorMessage",
                           "Invalid member ID or password. Please try again.");
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName",
                           PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("gotoPage", gotoPage);
        model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));

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

  private String buildPasswordResetEmail(String memberName, String resetUrl, String resortName) {
    return "Hello " + memberName + ",\n\n" +
      "You requested a password reset for your " + resortName + " patrol account.\n\n" +
      "Click the link below to reset your password:\n" +
      resetUrl + "\n\n" +
      "This link will expire in 1 hour.\n\n" +
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
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

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
        model.addAttribute("errorMessage",
                           "No member found with that ID or email address.");
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("gotoPage", gotoPage);
        model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));

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
        model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));

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
      resetToken.save(conn, resort);

      // 7. Build reset URL
      String baseUrl = request.getRequestURL().toString().replace("/LoginHelp", "");
      String resetUrl = baseUrl + "/ResetPassword?resort=" + resort +
        "&token=" + resetToken.getToken();

      // 8. Send email
      String subject = "Password Reset Request for " + PatrolData.getResortFullName(resort, LOG);
      String message = buildPasswordResetEmail(member.getFullName(), resetUrl,
                                               PatrolData.getResortFullName(resort, LOG));

      MailMan mailMan = new MailMan(memberEmail, sessionData, LOG);
      mailMan.sendMessage(subject, message, memberEmail);

      LOG.info("Password reset email sent to " + member.getFullName() + " at " + memberEmail);

      // 9. Show success message
      model.addAttribute("successMessage",
                         "An email with password reset instructions has been sent to " + memberEmail);
      model.addAttribute("resort", resort);
      model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
      model.addAttribute("gotoPage", gotoPage);
      model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));

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
      model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));

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
