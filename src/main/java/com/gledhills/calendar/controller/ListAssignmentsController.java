package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
     * Roster name a director picked on the Directors page, to read that patroller's history
     * instead of their own. Named for the Directors page's own select, so its "Show Patrollers
     * History ..." button can submit that form straight here.
     */
    public static final String SELECTED_PATROLLER = "NameToEdit";

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

            // 7. Check for backdoor user
            boolean isBackdoorUser = patrollerId.equalsIgnoreCase(sessionData.getBackDoorUser());

            if (isBackdoorUser) {
                // Backdoor user - skip assignment loading
                model.addAttribute("resort", resort);
                model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
                model.addAttribute("patrollerId", patrollerId);
                model.addAttribute("isBackdoorUser", true);
                model.addAttribute("assignments", new ArrayList<AssignmentInfo>());
                model.addAttribute("shiftCounts", new LinkedHashMap<String, Integer>());
                model.addAttribute("totalShifts", 0);

                // Add resort metadata for header
                ResortData resortData = PatrolData.getResortInfo(resort);
                if (resortData != null) {
                    model.addAttribute("resortUrl", resortData.getResortURL());
                    model.addAttribute("resortImage", resortData.getResortImage());
                    model.addAttribute("resortImgHeight", resortData.getImageHeight());
                }
                model.addAttribute("logoutLink", "");

                return "list-assignments";
            }

            // 7b. A director can arrive from the Directors page with a roster name selected, to
            // read somebody else's history. Only a director may: the parameter is ignored for
            // anyone else, so a hand-typed URL shows the asker their own assignments as usual.
            String selectedName = request.getParameter(SELECTED_PATROLLER);
            String subjectId = patrollerId;
            boolean cameFromDirectors = false;

            if (selectedName != null && !selectedName.trim().isEmpty()) {
                Roster loggedIn = patrolData.getMemberByID(patrollerId);

                if (loggedIn != null && loggedIn.isDirector()) {
                    Roster selected = patrolData.getMemberByName2(selectedName);
                    cameFromDirectors = true;

                    if (selected != null && selected.getID() != null) {
                        subjectId = selected.getID();
                    }
                    else {
                        LOG.info("no roster member named (" + selectedName + "), showing own history");
                    }
                }
                else {
                    LOG.info("ignoring " + SELECTED_PATROLLER + "=(" + selectedName
                               + "): " + patrollerId + " is not a director");
                }
            }

            // 8. Get DisplayAllAssignments parameter
            String displayAllParam = request.getParameter(DISPLAY_ALL_ASSIGNMENTS);
            boolean displayAllAssignments = "true".equals(displayAllParam);

            // 9. Calculate season start date
            Calendar calendarStart = new GregorianCalendar();
            int currentYear = calendarStart.get(Calendar.YEAR);
            int currentMonth = calendarStart.get(Calendar.MONTH);
            int startYear;
            int startMonth = Calendar.JULY;

            if (displayAllAssignments) {
                startYear = 2002;
            } else {
                startYear = (currentMonth < Calendar.JULY) ? currentYear - 1 : currentYear;
            }
            calendarStart.set(startYear, startMonth, 1);

            // 10. Get member info - the subject, who is the logged-in user unless a director
            // picked somebody off the roster
            Roster member = patrolData.getMemberByID(subjectId);
            String memberName = member != null ? member.getFullName() : "Unknown";
            int myID = Integer.parseInt(subjectId);

            // 11. Initialize collections for assignment data
            List<AssignmentInfo> assignments = new ArrayList<>();
            int[] shiftCounts = new int[Assignments.MAX_SHIFT_TYPES];
            int totalShifts = 0;

            // 12. Set up alternating row colors
            String WHITE_BG_COLOR = "#FFFFFF";
            String LT_GREEN_BG_COLOR = "#D7F5ED";
            String bgColor = WHITE_BG_COLOR;
            int lastMonth = 0;

            // 13. Load and process assignments
            for (Assignments ns : patrolData.readAllSortedAssignments(subjectId)) {
                // Check if assignment is within season range
                Calendar assignmentDate = new GregorianCalendar(ns.getYear(), ns.getMonth() - 1, ns.getDay());
                if (assignmentDate.before(calendarStart)) {
                    continue; // Skip assignments before season start
                }

                // Build date/time strings
                String szDate = ns.getExpandedDateAndTimeString();
                String szStartTime = ns.getStartingTimeString();
                String szEndTime = ns.getEndingTimeString();
                String dateTimeRange = szDate + " (" + szStartTime + " - " + szEndTime + ")";

                // Handle month change for alternating colors
                if (lastMonth != ns.getMonth()) {
                    lastMonth = ns.getMonth();
                    bgColor = bgColor.equals(WHITE_BG_COLOR) ? LT_GREEN_BG_COLOR : WHITE_BG_COLOR;
                }

                // Loop through all patrollers on this assignment
                for (int shiftType = 0; shiftType < Assignments.MAX_ASSIGNMENT_SIZE; shiftType++) {
                    String posID = ns.getPosID(shiftType);
                    if (posID == null || posID.equals("")) {
                        continue;
                    }

                    try {
                        int pat0 = Integer.parseInt(posID);
                        if (Math.abs(pat0) == myID) {
                            // Found matching patroller
                            boolean missedShift = (pat0 < 0);
                            String shiftTypeName = Assignments.getShiftName(ns.getType());

                            assignments.add(new AssignmentInfo(dateTimeRange, shiftTypeName, missedShift, bgColor));
                            shiftCounts[ns.getType()]++;
                            totalShifts++;
                        }
                    } catch (Exception e) {
                        LOG.error("error id (" + posID + ")");
                        continue;
                    }
                }
            }

            // 14. Convert shift counts array to map (only non-zero)
            Map<String, Integer> shiftCountsMap = new LinkedHashMap<>();
            for (int shiftType = 0; shiftType < Assignments.MAX_SHIFT_TYPES; shiftType++) {
                if (shiftCounts[shiftType] > 0) {
                    String shiftTypeName = Assignments.getShiftName(shiftType);
                    shiftCountsMap.put(shiftTypeName, shiftCounts[shiftType]);
                }
            }

            // 15. Build toggle URL and text. The selected patroller has to ride along, or toggling
            // the season would silently switch the page back to the director's own history.
            String toggleUrl = "ListAssignments?resort=" + resort + "&ID=" + patrollerId +
                "&" + DISPLAY_ALL_ASSIGNMENTS + "=" + !displayAllAssignments;
            if (cameFromDirectors) {
                toggleUrl += "&" + SELECTED_PATROLLER + "="
                    + URLEncoder.encode(selectedName, StandardCharsets.UTF_8);
            }
            String toggleText = displayAllAssignments
                ? "Change to current season only"
                : "Change to display all assignments";

            // 16. Add model attributes
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("memberName", memberName);
            model.addAttribute("displayAllAssignments", displayAllAssignments);
            model.addAttribute("toggleUrl", toggleUrl);
            model.addAttribute("toggleText", toggleText);
            model.addAttribute("assignments", assignments);
            model.addAttribute("shiftCounts", shiftCountsMap);
            model.addAttribute("totalShifts", totalShifts);
            model.addAttribute("isBackdoorUser", false);
            // Arrived from the Directors page, so the way back is a Done button rather than the
            // browser's Back button
            model.addAttribute("cameFromDirectors", cameFromDirectors);
            model.addAttribute("directorsUrl",
                               "Directors?resort=" + resort + "&ID=" + patrollerId);

            // 17. Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }
            model.addAttribute("logoutLink", "");

            return "list-assignments";

        } finally {
            patrolData.close();
        }
    }
}
