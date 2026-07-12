package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.utils.*;

import java.util.stream.IntStream;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot controller for editing shift templates.
 * Migrated from InnerEditShiftTemplates servlet.
 *
 * Handles GET /EditShiftTemplates - displays shift template editor
 * Handles POST /EditShiftTemplates - saves changes, adds, or deletes shifts
 */
@Controller
public class EditShiftTemplatesController {

    @GetMapping("/EditShiftTemplates")
    public String showEditShiftTemplates(
            @RequestParam String resort,
            @RequestParam(name = "ID") String id,
            @RequestParam(required = false) String selectedShift,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        return handleRequest(resort, id, selectedShift, null, null, request, response, model);
    }

    @PostMapping("/EditShiftTemplates")
    public String saveShiftTemplates(
            @RequestParam String resort,
            @RequestParam(name = "ID") String id,
            @RequestParam(required = false) String selectedShift,
            @RequestParam(required = false) String SaveChangesBtn,
            @RequestParam(required = false) String DeleteTemplateBtn,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        return handleRequest(resort, id, selectedShift, SaveChangesBtn, DeleteTemplateBtn, request, response, model);
    }

    private String handleRequest(
            String resort,
            String id,
            String selectedShift,
            String saveChangesBtn,
            String deleteTemplateBtn,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        // Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(EditShiftTemplatesController.class, javaxRequest, "POST", resort, Logger.INFO);

        // Validate resort
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        // Initialize session and patrol data
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        // Validate credentials
        ValidateCredentialsRedirectIfNeeded credentials =
            new ValidateCredentialsRedirectIfNeeded(
                sessionData, javaxRequest, javaxResponse,
                "EditShiftTemplates", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null; // Redirect already sent
        }

        try {
            String loggedInUserId = sessionData.getLoggedInUserId();

            // Handle Save Changes button - redirect back to Directors
            if (saveChangesBtn != null) {
                response.sendRedirect("Directors?resort=" + resort + "&ID=" + loggedInUserId);
                return null;
            }

            // Process form submission
            ProcessResult result = processFormSubmission(request, sessionData, patrolData, selectedShift, deleteTemplateBtn, LOG);

            if (deleteTemplateBtn != null) {
                model.addAttribute("deleteSuccess", true);
                selectedShift = ""; // Template no longer exists
            }

            // Get all shifts and filter for selected
            List<ShiftDefinitions> allShifts = patrolData.readShiftDefinitions();
            List<ShiftWithIndex> selectedShifts = new ArrayList<>();
            Map<String, String> shiftOptions = buildShiftOptions(allShifts);

            // If no shift selected and we have shifts, select the first one
            if ((selectedShift == null || selectedShift.isEmpty()) && !shiftOptions.isEmpty()) {
                selectedShift = shiftOptions.keySet().iterator().next();
            }

            // Build selected shifts list with indices
            int validShifts = 0;
            for (ShiftDefinitions shift : allShifts) {
                String parsedName = shift.parsedEventName();
                if (parsedName.equals(selectedShift)) {
                    selectedShifts.add(new ShiftWithIndex(shift, validShifts));
                    validShifts++;
                }
            }

            // Add model attributes
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", loggedInUserId);
            model.addAttribute("selectedShift", selectedShift != null ? selectedShift : "");
            model.addAttribute("shiftOptions", shiftOptions);
            model.addAttribute("selectedShifts", selectedShifts);
            model.addAttribute("newShiftStyle", PatrolData.NEW_SHIFT_STYLE);
            model.addAttribute("shiftTypes", getAllShiftTypes());
            model.addAttribute("maxAssignmentSize", Assignments.MAX_ASSIGNMENT_SIZE);
            model.addAttribute("countOptions", buildCountOptions());

            // Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            return "edit-shift-templates";

        } finally {
            patrolData.close();
        }
    }

    /**
     * Process form submission for shift updates, additions, and deletions.
     */
    private ProcessResult processFormSubmission(
            HttpServletRequest request,
            SessionData sessionData,
            PatrolData patrolData,
            String selectedShift,
            String deleteTemplateBtn,
            Logger LOG) {

        String eventName = request.getParameter("eventName");
        String addShiftParam = request.getParameter("addShift");
        boolean addShift = (addShiftParam != null);

        String shiftCountParam = request.getParameter("shiftCount");
        int shiftCount = (shiftCountParam != null) ? Integer.parseInt(shiftCountParam) : 0;

        if (selectedShift == null) {
            selectedShift = "";
        }

        // Check if shift name changed (new shift)
        boolean newShift = (eventName != null && !eventName.isEmpty() && !eventName.equals(selectedShift));
        if (newShift) {
            selectedShift = eventName;
        }

        // Check for individual shift deletion
        boolean deleteShift = false;
        int shiftToDelete = -1;
        for (int i = 0; i < shiftCount; i++) {
            if (request.getParameter("delete_" + i) != null) {
                deleteShift = true;
                shiftToDelete = i;
                break;
            }
        }

        // Read existing shifts and process updates
        List<ShiftDefinitions> shifts = patrolData.readShiftDefinitions();
        ShiftDefinitions[] todaysData = new ShiftDefinitions[shiftCount];

        // Read shift data from form
        for (int i = 0; i < shiftCount; i++) {
            String tStart = request.getParameter("startTime_" + i);
            String tEnd = request.getParameter("endTime_" + i);
            String tCount = request.getParameter("count_" + i);
            String tType = request.getParameter("shift_" + i);

            if (tStart == null || tEnd == null || tCount == null) {
                continue;
            }

            int tCnt = Integer.parseInt(tCount);
            int tTyp = parseShiftType(tType);
            String tEvent = ShiftDefinitions.createShiftName(eventName, i);

            todaysData[i] = new ShiftDefinitions(tEvent, tStart, tEnd, tCnt, tTyp, LOG);

            if (newShift) {
                patrolData.writeShift(todaysData[i]);
            }
        }

        // Process deletions and updates
        int selectedSize = 0;
        boolean foundDeleteShift = false;

        for (ShiftDefinitions shiftDefinition : shifts) {
            String parsedName = shiftDefinition.parsedEventName();

            if (parsedName.equals(selectedShift)) {
                // Delete entire template
                if (deleteTemplateBtn != null) {
                    patrolData.deleteShift(shiftDefinition);
                    continue;
                }

                // Delete individual shift
                if (deleteShift && shiftToDelete == selectedSize) {
                    deleteShift = false;
                    patrolData.deleteShift(shiftDefinition);
                    foundDeleteShift = true;
                    continue;
                }

                // Renumber shifts after deletion
                if (foundDeleteShift) {
                    patrolData.decrementShift(shiftDefinition);
                }
                // Update existing shift
                else if (!newShift && shiftCount > 0 && selectedSize < todaysData.length &&
                         todaysData[selectedSize] != null && !shiftDefinition.equals(todaysData[selectedSize])) {
                    patrolData.writeShift(todaysData[selectedSize]);
                }

                selectedSize++;
            }
        }

        // Add new shift
        if (addShift) {
            String start = "8:30";
            String end = "14:00";
            int cnt = 1;
            ShiftDefinitions shiftDefinition =
                new ShiftDefinitions(ShiftDefinitions.createShiftName(selectedShift, selectedSize),
                                   start, end, cnt, Assignments.DAY_TYPE, LOG);
            patrolData.writeShift(shiftDefinition);
        }

        return new ProcessResult(selectedShift, newShift, addShift);
    }

    /**
     * Build shift options for dropdown (unique shift names).
     */
    private Map<String, String> buildShiftOptions(List<ShiftDefinitions> shifts) {
        Map<String, String> options = new LinkedHashMap<>();
        String lastName = "";

        for (ShiftDefinitions shift : shifts) {
            String parsedName = shift.parsedEventName();
            if (!parsedName.equals(lastName)) {
                options.put(parsedName, parsedName);
                lastName = parsedName;
            }
        }

        return options;
    }

    /**
     * Helper class for shift with index (for form field naming).
     */
    public static class ShiftWithIndex {
        private final ShiftDefinitions shift;
        private final int index;

        public ShiftWithIndex(ShiftDefinitions shift, int index) {
            this.shift = shift;
            this.index = index;
        }

        public ShiftDefinitions getShift() {
            return shift;
        }

        public int getIndex() {
            return index;
        }
    }

    /**
     * Result of form processing.
     */
    private static class ProcessResult {
        final String selectedShift;
        final boolean newShift;
        final boolean addShift;

        ProcessResult(String selectedShift, boolean newShift, boolean addShift) {
            this.selectedShift = selectedShift;
            this.newShift = newShift;
            this.addShift = addShift;
        }
    }

    /**
     * Get all shift type names.
     */
    private List<String> getAllShiftTypes() {
        return IntStream.range(0, Assignments.MAX_SHIFT_TYPES)
            .mapToObj(Assignments::getShiftName)
            .collect(Collectors.toList());
    }

    /**
     * Parse shift type name to type ID.
     */
    private int parseShiftType(String typeName) {
        for (int i = 0; i < Assignments.MAX_SHIFT_TYPES; i++) {
            if (Assignments.getShiftName(i).equals(typeName)) {
                return i;
            }
        }
        return Assignments.DAY_TYPE; // Default
    }

    /**
     * Build count options (1 to MAX_ASSIGNMENT_SIZE).
     */
    private List<Integer> buildCountOptions() {
        return IntStream.rangeClosed(1, Assignments.MAX_ASSIGNMENT_SIZE)
            .boxed()
            .collect(Collectors.toList());
    }
}
