package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nsponline.calendar.store.Assignments;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot controller for listing patrol assignments.
 * Migrated from ListAssignments servlet.
 *
 * Handles GET /ListAssignments - displays patroller's shift assignments
 */
@Controller
public class ListAssignmentsController {

    public static final String DISPLAY_ALL_ASSIGNMENTS = "DisplayAllAssignments";

    /**
     * Model class for assignment information
     */
    public static class AssignmentInfo {
        private String dateTimeRange;
        private String shiftType;
        private boolean missedShift;
        private String bgColor;

        public AssignmentInfo(String dateTimeRange, String shiftType,
                             boolean missedShift, String bgColor) {
            this.dateTimeRange = dateTimeRange;
            this.shiftType = shiftType;
            this.missedShift = missedShift;
            this.bgColor = bgColor;
        }

        // Getters
        public String getDateTimeRange() { return dateTimeRange; }
        public String getShiftType() { return shiftType; }
        public boolean isMissedShift() { return missedShift; }
        public String getBgColor() { return bgColor; }
    }

    @GetMapping("/ListAssignments")
    public String showAssignments(HttpServletRequest request, HttpServletResponse response,
                                  Model model) throws IOException {
        // 1. Read resort parameter
        String resort = request.getParameter("resort");

        // 2. Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(ListAssignmentsController.class, javaxRequest, "GET", resort, Logger.INFO);

        // 3. Validate resort
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        // 4. Initialize session and patrol data
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        try {
            // 5. Validate credentials
            ValidateCredentialsRedirectIfNeeded credentials =
                new ValidateCredentialsRedirectIfNeeded(
                    sessionData, javaxRequest, javaxResponse,
                    "ListAssignments", patrolData, LOG);

            if (credentials.hasInvalidCredentials()) {
                return null; // Redirect already sent
            }

            // 6. Get logged-in user info
            String patrollerId = sessionData.getLoggedInUserId();

            // TODO: Load data and populate model (next task)

            return "list-assignments";

        } finally {
            patrolData.close();
        }
    }
}
