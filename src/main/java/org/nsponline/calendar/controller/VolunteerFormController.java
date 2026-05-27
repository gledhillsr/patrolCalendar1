package org.nsponline.calendar.controller;

import org.nsponline.calendar.controller.ServletAdapter;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Spring Boot controller for volunteer application form submission.
 * Migrated from OuterVolunteerForm servlet.
 *
 * Handles POST /VolunteerForm - processes volunteer application and sends email notifications
 */
@Controller
public class VolunteerFormController {

  @PostMapping("/VolunteerForm")
  public String processVolunteerForm(HttpServletRequest request, HttpServletResponse response,
                                     Model model) throws IOException {
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String id = request.getParameter("ID");
    String firstName = request.getParameter("firstName");
    String lastName = request.getParameter("lastName");
    String city = request.getParameter("city");
    String state = request.getParameter("state");
    String zip = request.getParameter("zip");
    String cellNumber = request.getParameter("cellNumber");
    String email = request.getParameter("email");
    String yearsSkied = request.getParameter("yearsSkied");
    String skiExperience = request.getParameter("skiExperience");

    // 2. Validate resort and ID (Brighton-specific check from legacy code)
    if (!"Brighton".equals(resort) || !"192443".equals(id)) {
      response.sendError(400, "Invalid resort or ID");
      return null;
    }

    // 3. Use ServletAdapter for legacy code compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(VolunteerFormController.class, javaxRequest, "POST", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    try {
      // 4. Build email message
      String subject = "Brighton volunteer application";
      String message = buildApplicationMessage(firstName, lastName, city, state, zip,
                                               cellNumber, email, yearsSkied, skiExperience);

      // 5. Send email notifications
      Roster member = patrolData.getMemberByID(id);
      MailMan mail = new MailMan(member.getEmailAddress(), sessionData, LOG);

      mail.sendMessage(subject, message, "brightonnsp@gmail.com");
      mail.sendMessage(subject, message, "steve@gledhills.com");

      LOG.info("Volunteer application submitted for: " + firstName + " " + lastName);

      // 6. Prepare model for template
      model.addAttribute("resort", resort);
      model.addAttribute("redirectUrl", PatrolData.SERVLET_URL + "MonthCalendar?resort=" + resort);

      return "volunteer-form-success";

    }
    catch (Exception e) {
      LOG.logException("Error processing volunteer application: ", e);
      response.sendError(500, "Error processing application");
      return null;

    }
    finally {
      patrolData.close();
    }
  }

  /**
   * Build the volunteer application email message
   */
  private String buildApplicationMessage(String firstName, String lastName, String city,
                                         String state, String zip, String cellNumber,
                                         String email, String yearsSkied, String skiExperience) {
    return "new Ski Patrol applicant: " + firstName + " " + lastName +
      "\n address: " + city + ", " + state + " " + zip +
      "\n cellNumber: " + cellNumber +
      "\n email: " + email +
      "\n yearsSkied: " + yearsSkied +
      "\n skiExperience: \n" + skiExperience;
  }
}
