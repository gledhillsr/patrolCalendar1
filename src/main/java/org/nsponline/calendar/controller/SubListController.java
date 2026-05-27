package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot controller for substitute list.
 * Migrated from SubList servlet.
 *
 * Handles GET /SubList - displays patrollers who opted in as substitutes
 */
@Controller
public class SubListController {

    /**
     * Model class for substitute patroller information
     */
    public static class SubstituteInfo {
        private String name;
        private String homePhone;
        private String workPhone;
        private String cellPhone;
        private String email;

        public SubstituteInfo(String name, String homePhone, String workPhone,
                             String cellPhone, String email) {
            this.name = name;
            this.homePhone = homePhone;
            this.workPhone = workPhone;
            this.cellPhone = cellPhone;
            this.email = email;
        }

        // Getters
        public String getName() { return name; }
        public String getHomePhone() { return homePhone; }
        public String getWorkPhone() { return workPhone; }
        public String getCellPhone() { return cellPhone; }
        public String getEmail() { return email; }
    }

    @GetMapping("/SubList")
    public String showSubList(HttpServletRequest request, HttpServletResponse response,
                              Model model) throws IOException {
        // 1. Read resort parameter
        String resort = request.getParameter("resort");

        // 2. Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(SubListController.class, javaxRequest, "GET", resort, Logger.INFO);

        // 3. Validate resort
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        // 4. Initialize session and patrol data
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        // 5. Validate credentials
        ValidateCredentialsRedirectIfNeeded credentials =
            new ValidateCredentialsRedirectIfNeeded(
                sessionData, javaxRequest, javaxResponse,
                "SubList", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null; // Redirect already sent
        }

        try {
            // 6. Get logged-in user info
            String patrollerId = sessionData.getLoggedInUserId();

            // 7. Load substitute list
            List<SubstituteInfo> substitutes = new ArrayList<>();
            ResultSet rosterResults = patrolData.resetRoster();
            Roster member = patrolData.nextMember("&nbsp;", rosterResults);

            while (member != null) {
                // Check if member opted in as substitute
                String subField = member.getSub();
                if (subField != null && (subField.startsWith("y") || subField.startsWith("Y"))) {
                    // Create SubstituteInfo with contact details
                    String name = member.getFullName_lastNameFirst();
                    String homePhone = (member.getHomePhone() != null) ? member.getHomePhone().trim() : "";
                    String workPhone = (member.getWorkPhone() != null) ? member.getWorkPhone().trim() : "";
                    String cellPhone = (member.getCellPhone() != null) ? member.getCellPhone().trim() : "";
                    String email = (member.getEmailAddress() != null) ? member.getEmailAddress().trim() : "";

                    substitutes.add(new SubstituteInfo(name, homePhone, workPhone, cellPhone, email));
                }

                member = patrolData.nextMember("&nbsp;", rosterResults);
            }

            // 8. Add model attributes
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("substitutes", substitutes);

            // 9. Build comma-separated email list for EmailForm button
            StringBuilder emailList = new StringBuilder();
            rosterResults = patrolData.resetRoster();
            member = patrolData.nextMember("&nbsp;", rosterResults);

            while (member != null) {
                String subField = member.getSub();
                if (subField != null && (subField.startsWith("y") || subField.startsWith("Y"))) {
                    String email = member.getEmailAddress();
                    // Validate email address
                    if (StaticUtils.isValidEmailAddress(email)) {
                        if (emailList.length() > 0) {
                            emailList.append(",");
                        }
                        emailList.append(email);
                    }
                }
                member = patrolData.nextMember("&nbsp;", rosterResults);
            }

            // 10. Build EmailForm URL with SubList parameter
            String emailFormUrl = "EmailForm?resort=" + resort + "&ID=" + patrollerId + "&SubList=1";
            model.addAttribute("emailFormUrl", emailFormUrl);
            model.addAttribute("emailList", emailList.toString());

            return "sub-list";

        } finally {
            patrolData.close();
        }
    }
}
