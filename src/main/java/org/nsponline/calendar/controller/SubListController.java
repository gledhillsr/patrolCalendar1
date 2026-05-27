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
        Logger LOG = new Logger(SubListController.class, javaxRequest, "GET", resort, Logger.INFO);
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        try {
            // 3. Get logged-in user info
            String patrollerId = sessionData.getLoggedInUserId();

            // 4. Check authentication - redirect if not logged in
            if (patrollerId == null || patrollerId.isEmpty()) {
                response.sendRedirect("MemberLogin?resort=" + resort);
                return null;
            }

            // TODO: Load data and populate model (next task)

            return "sub-list";

        } finally {
            patrolData.close();
        }
    }
}
