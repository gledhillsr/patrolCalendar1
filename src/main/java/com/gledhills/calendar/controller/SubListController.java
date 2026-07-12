package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.*;
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

    /**
     * Clean field value by removing null, empty, or &nbsp; values
     */
    private String cleanField(String value) {
        if (value == null || value.trim().isEmpty() || "&nbsp;".equals(value.trim())) {
            return "";
        }
        return value.trim();
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

            // 7. Load substitute list and build email list in single pass
            List<SubstituteInfo> substitutes = new ArrayList<>();
            StringBuilder emailList = new StringBuilder();
            ResultSet rosterResults = patrolData.resetRoster();
            Roster member = patrolData.nextMember("&nbsp;", rosterResults);

            while (member != null) {
                // Check if member opted in as substitute
                String subField = member.getSub();
                if (subField != null && (subField.startsWith("y") || subField.startsWith("Y"))) {
                    // Create SubstituteInfo with contact details
                    String name = member.getFullName_lastNameFirst();
                    String homePhone = cleanField(member.getHomePhone());
                    String workPhone = cleanField(member.getWorkPhone());
                    String cellPhone = cleanField(member.getCellPhone());
                    String email = cleanField(member.getEmailAddress());

                    substitutes.add(new SubstituteInfo(name, homePhone, workPhone, cellPhone, email));

                    // Build email list for EmailForm button
                    if (StaticUtils.isValidEmailAddress(email)) {
                        if (emailList.length() > 0) {
                            emailList.append(",");
                        }
                        emailList.append(email);
                    }
                }

                member = patrolData.nextMember("&nbsp;", rosterResults);
            }

            // 8. Add model attributes
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("substitutes", substitutes);

            // 8a. Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }
            model.addAttribute("logoutLink", ""); // TODO: Add logout functionality

            // 9. Build EmailForm URL with SubList parameter
            String emailFormUrl = "EmailForm?resort=" + resort + "&ID=" + patrollerId + "&SubList=1";
            model.addAttribute("emailFormUrl", emailFormUrl);
            model.addAttribute("emailList", emailList.toString());

            return "sub-list";

        } finally {
            patrolData.close();
        }
    }
}
