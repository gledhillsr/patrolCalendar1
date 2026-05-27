package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nsponline.calendar.store.DirectorSettings;
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
 * Spring Boot controller for member list.
 * Migrated from MemberList servlet.
 *
 * Handles GET /MemberList - displays all active patrol members
 */
@Controller
public class MemberListController {

    /**
     * Model class for member information
     */
    public static class MemberInfo {
        private String name;
        private String homePhone;
        private String workPhone;
        private String cellPhone;
        private String email;

        public MemberInfo(String name, String homePhone, String workPhone,
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

    @GetMapping("/MemberList")
    public String showMemberList(HttpServletRequest request, HttpServletResponse response,
                                 Model model) throws IOException {
        // 1. Read resort parameter
        String resort = request.getParameter("resort");

        // 2. Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(MemberListController.class, javaxRequest, "GET", resort, Logger.INFO);

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
                "MemberList", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            return null; // Redirect already sent
        }

        try {
            // 6. Get logged-in user info
            String patrollerId = sessionData.getLoggedInUserId();

            // 7. Check director status and emailAll permission
            DirectorSettings ds = patrolData.readDirectorSettings();
            Roster editor = patrolData.getMemberByID(patrollerId);
            boolean isDirector = editor != null && editor.isDirector();
            boolean showEmailButton = isDirector || (ds != null && ds.getEmailAll());

            // TODO: Load roster data (next task)

            return "member-list";

        } finally {
            patrolData.close();
        }
    }
}
