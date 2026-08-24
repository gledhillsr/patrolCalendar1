package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.store.ShiftTemplatePatrollers;
import com.gledhills.calendar.utils.*;
import com.gledhills.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Controller for editing single day shift assignments.
 * Allows directors to override the day shift template, add/change/delete shifts,
 * and make mass changes to day shift assignments.
 */
@Controller
@RequestMapping("/DayShifts")
public class DayShiftsController {
    private static final boolean DEBUG = false;

    @GetMapping
    public String handleGet(
            @RequestParam String resort,
            @RequestParam int dayOfWeek,
            @RequestParam int date,
            @RequestParam int month,
            @RequestParam int year,
            @RequestParam(required = false) String ID,
            @RequestParam(required = false) String dropdownShift,
            @RequestParam(required = false) String selectedShift,
            @RequestParam(required = false) String doAssignments,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger LOG = new Logger(DayShiftsController.class, javaxRequest, "GET", resort, Logger.INFO);
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);

        String patrollerId = ID != null ? ID : sessionData.getLoggedInUserId();
        // the Logger only picks up an "ID" request parameter, which is optional here
        LOG.setId(patrollerId);

        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

        // Every other page in this app checks credentials; this one never did. It took its identity
        // from the "ID" *query parameter*, looked that member up, and handed the director view to
        // whoever asked - so anyone who knew a director's member number could read and, through the
        // POST, rewrite a day's shifts without logging in at all.
        //
        // Authority now comes from the session (or the remember-me cookie ValidateCredentials
        // honours), never from a parameter. The month calendar only ever links directors here.
        ValidateCredentialsRedirectIfNeeded credentials =
                new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                                        "DayShifts", patrolData, LOG);
        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null; //redirect already sent
        }

        String loggedInUserId = sessionData.getLoggedInUserId();
        Roster loggedInMember = patrolData.getMemberByID(loggedInUserId);
        boolean isDirector = loggedInMember != null && loggedInMember.isDirector();
        if (!isDirector) {
            LOG.error("DENIED DayShifts: member " + loggedInUserId + " is not a director");
            patrolData.close();
            response.sendError(403, "Directors only");
            return null;
        }
        patrollerId = loggedInUserId;   //the parameter is for links and logging, not for authority

        // Director view - can edit shifts
        return showEditShiftView(resort, dayOfWeek, date, month, year, patrollerId,
                dropdownShift, selectedShift, doAssignments != null,
                patrolData, sessionData, model, LOG);
    }

    @PostMapping
    public String handlePost(
            @RequestParam String resort,
            @RequestParam int dayOfWeek,
            @RequestParam int date,
            @RequestParam int month,
            @RequestParam int year,
            @RequestParam(required = false) String ID,
            @RequestParam(required = false) String newName,
            @RequestParam(required = false) String newShift,
            @RequestParam(required = false) String saveShiftBtn,
            @RequestParam(required = false) String saveAssignmentBtn,
            @RequestParam(required = false) String deleteAllShiftsBtn,
            @RequestParam(required = false) String dropdownShift,
            @RequestParam(required = false) String dropdownShift2,
            @RequestParam(required = false) String selectedShift,
            @RequestParam(required = false) Integer shiftCount,
            @RequestParam(required = false) String doAssignments,
            @RequestParam(required = false) String replaceShiftFormat,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger LOG = new Logger(DayShiftsController.class, javaxRequest, "POST", resort, Logger.INFO);
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);

        String patrollerId = ID != null ? ID : sessionData.getLoggedInUserId();
        // the Logger only picks up an "ID" request parameter, which is optional here
        LOG.setId(patrollerId);

        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

        // Every other page in this app checks credentials; this one never did. It took its identity
        // from the "ID" *query parameter*, looked that member up, and handed the director view to
        // whoever asked - so anyone who knew a director's member number could read and, through the
        // POST, rewrite a day's shifts without logging in at all.
        //
        // Authority now comes from the session (or the remember-me cookie ValidateCredentials
        // honours), never from a parameter. The month calendar only ever links directors here.
        ValidateCredentialsRedirectIfNeeded credentials =
                new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                                        "DayShifts", patrolData, LOG);
        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null; //redirect already sent
        }

        String loggedInUserId = sessionData.getLoggedInUserId();
        Roster loggedInMember = patrolData.getMemberByID(loggedInUserId);
        boolean isDirector = loggedInMember != null && loggedInMember.isDirector();
        if (!isDirector) {
            LOG.error("DENIED DayShifts: member " + loggedInUserId + " is not a director");
            patrolData.close();
            response.sendError(403, "Directors only");
            return null;
        }
        patrollerId = loggedInUserId;   //the parameter is for links and logging, not for authority

        // Replacing the day's shift format is destructive - it removes the day's shifts and
        // everybody on them - so it arrives as a confirmed POST rather than a URL that a refresh
        // or a stale link could fire again.
        if (replaceShiftFormat != null && !replaceShiftFormat.isEmpty()) {
            processReplaceShiftFormat(resort, dayOfWeek, date, month, year, patrolData,
                    sessionData, javaxRequest, replaceShiftFormat, LOG);
            patrolData.close();
            return "redirect:/DayShifts?resort=" + resort + "&dayOfWeek=" + dayOfWeek +
                    "&date=" + date + "&month=" + month + "&year=" + year + "&ID=" + patrollerId;
        }

        if (doAssignments != null) {
            // "Assign Patrollers to Today's Shifts" button. The button submits the shift edit
            // form, so save any pending row edits first - otherwise the grid would be built from
            // the saved shifts and the director's unsaved times/counts would be silently lost.
            // shiftCount is only absent if the row fields were not submitted at all (a stale
            // cached page); saving then would risk writing a comment-only row over real data.
            if (shiftCount != null) {
                ArrayList<Assignments> pendingFromDisk = new ArrayList<>();
                ArrayList<ShiftDefinitions> pendingShifts = new ArrayList<>();

                readAssignmentsAndShifts(resort, dayOfWeek, date, month, year,
                        patrolData, sessionData, pendingFromDisk, javaxRequest);
                readParameterShifts(javaxRequest, shiftCount, newName, pendingShifts, LOG);
                processSaveShiftChanges(patrolData, pendingFromDisk, pendingShifts,
                        newName, date, month, year, resort,
                        templatePatrollersForFreshDay(patrolData, pendingFromDisk, selectedShift,
                                dayOfWeek, LOG), LOG);
            }

            return showEditShiftView(resort, dayOfWeek, date, month, year, patrollerId,
                    null, null, true, patrolData, sessionData, model, LOG);
        }

        // Process the change request
        ArrayList<Assignments> assignmentsFromDisk = new ArrayList<>();
        ArrayList<ShiftDefinitions> parameterShifts = new ArrayList<>();

        readAssignmentsAndShifts(resort, dayOfWeek, date, month, year,
                patrolData, sessionData, assignmentsFromDisk, javaxRequest);

        readParameterShifts(javaxRequest, shiftCount != null ? shiftCount : 0,
                newName, parameterShifts, LOG);

        // Handle different button actions
        if (saveAssignmentBtn != null) {
            processSaveAssignments(javaxRequest, patrolData, assignmentsFromDisk, resort, LOG);
            return redirectToCalendar(response, resort, month, year, patrollerId);
        } else if (deleteAllShiftsBtn != null) {
            processDeleteAllShifts(patrolData, assignmentsFromDisk);
            return redirectToCalendar(response, resort, month, year, patrollerId);
        } else if (dropdownShift2 != null && !dropdownShift2.isEmpty() &&
                !dropdownShift2.equals("--New Shift Style--")) {
            assignmentsFromDisk.clear();
            selectedShift = dropdownShift2;
        } else if (newShift != null) {
            ensureDisplayedRowsAreSaved(resort, dayOfWeek, date, month, year, patrolData,
                    sessionData, javaxRequest, assignmentsFromDisk, parameterShifts, newName,
                    selectedShift, LOG);
            processAddNewShift(patrolData, assignmentsFromDisk, parameterShifts, newName,
                    date, month, year, LOG);
        } else if (saveShiftBtn != null) {
            processSaveShiftChanges(patrolData, assignmentsFromDisk, parameterShifts,
                    newName, date, month, year, resort,
                    templatePatrollersForFreshDay(patrolData, assignmentsFromDisk, selectedShift,
                            dayOfWeek, LOG), LOG);
            return redirectToCalendar(response, resort, month, year, patrollerId);
        }

        // Check for a row reorder (the up/down arrows on the far right of each row)
        int rowToMove = findRequestedMove(javaxRequest, shiftCount);
        if (rowToMove >= 0) {
            int moveTo = javaxRequest.getParameter("moveUp_" + rowToMove) != null
                    ? rowToMove - 1 : rowToMove + 1;
            processMoveShift(resort, dayOfWeek, date, month, year, patrolData, sessionData,
                    javaxRequest, assignmentsFromDisk, parameterShifts, newName, selectedShift,
                    rowToMove, moveTo, LOG);
        }

        // Check for delete action
        if (shiftCount != null) {
            for (int i = 0; i < shiftCount; i++) {
                if (javaxRequest.getParameter("delete_" + i) != null) {
                    ensureDisplayedRowsAreSaved(resort, dayOfWeek, date, month, year, patrolData,
                            sessionData, javaxRequest, assignmentsFromDisk, parameterShifts,
                            newName, selectedShift, LOG);
                    processDeleteShift(patrolData, assignmentsFromDisk, i, newName,
                            date, month, year, LOG);
                    break;
                }
            }
        }

        patrolData.close();

        // Redirect back to GET to show updated state
        return "redirect:/DayShifts?resort=" + resort + "&dayOfWeek=" + dayOfWeek +
                "&date=" + date + "&month=" + month + "&year=" + year + "&ID=" + patrollerId;
    }

    private String showShiftDetailsView(String resort, int dayOfWeek, int date, int month,
            int year, String patrollerId, PatrolData patrolData, SessionData sessionData,
            Model model, Logger LOG) {

        ArrayList<Assignments> assignments = new ArrayList<>();
        readAssignmentsAndShifts(resort, dayOfWeek, date, month, year,
                patrolData, sessionData, assignments, null);

        Hashtable<String, String> numToName = buildRosterLookup(patrolData);

        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("patrollerId", patrollerId);
        model.addAttribute("dayOfWeek", dayOfWeek);
        model.addAttribute("dayName", StaticUtils.szDays[dayOfWeek]);
        model.addAttribute("date", date);
        model.addAttribute("month", month);
        model.addAttribute("monthName", StaticUtils.szMonthsFull[month]);
        model.addAttribute("year", year);
        model.addAttribute("assignments", assignments);
        model.addAttribute("numToName", numToName);
        model.addAttribute("isDirector", false);

        addResortMetadata(resort, model);
        patrolData.close();

        return "day-shifts";
    }

    private String showEditShiftView(String resort, int dayOfWeek, int date, int month,
            int year, String patrollerId, String dropdownShift, String selectedShift,
            boolean doAssignments, PatrolData patrolData, SessionData sessionData, Model model, Logger LOG) {

        ArrayList<Assignments> assignments = new ArrayList<>();
        readAssignmentsAndShifts(resort, dayOfWeek, date, month, year,
                patrolData, sessionData, assignments, null);

        ArrayList<ShiftDefinitions> shiftTemplates = new ArrayList<>(patrolData.readShiftDefinitions());
        Hashtable<String, String> numToName = buildRosterLookup(patrolData);
        String[] sortedRoster = buildSortedRoster(patrolData);

        // Total patrollers actually assigned today (empty slots don't count) - gates the
        // pattern-change block below and the "Delete All (restore template)" confirm dialog
        int patrollersOnSchedule = 0;
        for (Assignments assignment : assignments) {
            patrollersOnSchedule += assignment.getUseCount(resort);
        }

        // A "Use Predefined Shift format" selection reloads the page with dropdownShift set;
        // it overrides which pattern is displayed (matches the legacy servlet behavior).
        // Selecting "--New Shift Style--" matches no template, which yields a blank slate.
        // Only block the change when patrollers are actually assigned - a day whose saved
        // rows are all empty (e.g. everyone was removed) may still switch patterns.
        if (dropdownShift != null && dropdownShift.length() > 1) {
            if (patrollersOnSchedule > 0) {
                //reached only if the POST path was bypassed; the dropdown confirms and posts
                model.addAttribute("popupMessage",
                        "This day has patrollers on it. Use the shift format dropdown, which will "
                          + "ask before removing them.");
            } else {
                selectedShift = dropdownShift;
                assignments.clear(); //forget the saved rows; display the chosen pattern instead
            }
        }
        if (selectedShift == null || selectedShift.isEmpty()) {
            selectedShift = StaticUtils.szDays[dayOfWeek];
        }

        // If no assignments exist for this date, populate the rows from the selected template
        if (assignments.isEmpty()) {
            populateAssignmentsFromTemplate(assignments, shiftTemplates, selectedShift,
                    date, month, year, resort, patrolData, LOG);
        }

        String originalName = assignments.isEmpty() ? "" : assignments.get(0).getEventName();

        // Unique shift names for the "Use Predefined Shift format" dropdown:
        // everything before the first "_" of the template name, de-duplicated
        LinkedHashMap<String, String> shiftOptions = new LinkedHashMap<>();
        for (ShiftDefinitions shift : shiftTemplates) {
            String name = shiftDropdownName(shift.getEventName());
            if (!name.trim().isEmpty()) {
                shiftOptions.put(name, name);
            }
        }

        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("patrollerId", patrollerId);
        model.addAttribute("dayOfWeek", dayOfWeek);
        model.addAttribute("dayName", StaticUtils.szDays[dayOfWeek]);
        model.addAttribute("date", date);
        model.addAttribute("month", month);
        model.addAttribute("monthName", StaticUtils.szMonthsFull[month]);
        model.addAttribute("year", year);
        model.addAttribute("assignments", assignments);
        model.addAttribute("shiftTemplates", shiftTemplates);
        model.addAttribute("shiftOptions", shiftOptions);
        model.addAttribute("patrollersOnSchedule", patrollersOnSchedule);
        model.addAttribute("selectedShift", selectedShift);
        model.addAttribute("originalName", originalName);
        model.addAttribute("numToName", numToName);
        model.addAttribute("sortedRoster", sortedRoster);
        model.addAttribute("isDirector", true);
        model.addAttribute("doAssignments", doAssignments);
        model.addAttribute("maxShiftsReached", assignments.size() >= ShiftDefinitions.MAX);
        model.addAttribute("countOptions", buildCountOptions());
        model.addAttribute("maxAssignmentSize", Assignments.MAX_ASSIGNMENT_SIZE);
        model.addAttribute("shiftTypes", buildShiftTypes());
        Map<String, Integer> templatePatrollerCounts = ShiftTemplatePatrollers
                .countByTemplateName(LOG, patrolData.getConnection(), shiftTemplates,
                                     numToName.keySet());
        model.addAttribute("templatePatrollerCounts", templatePatrollerCounts);
        model.addAttribute("selectedTemplatePatrollers",
                           templatePatrollerCounts.getOrDefault(selectedShift, 0));

        addResortMetadata(resort, model);
        patrolData.close();

        return "day-shifts";
    }

    private void readAssignmentsAndShifts(String resort, int dayOfWeek, int date, int month,
            int year, PatrolData patrolData, SessionData sessionData,
            ArrayList<Assignments> assignmentsFromDisk, javax.servlet.http.HttpServletRequest request) {

        ArrayList<Assignments> monthOfAssignments = patrolData.readSortedAssignments(year, month + 1);

        int matchCount = 0;
        for (Assignments data : monthOfAssignments) {
            int y = data.getYear();
            int m = data.getMonth() - 1;
            int d = data.getDay();

            if (year == y && month == m && date == d) {
                assignmentsFromDisk.add(data);
                matchCount++;
            }
        }

//        if (matchCount == 0) {
//            System.out.println("DayShifts DEBUG: No assignments found for " + year + "-" + (month+1) + "-" + date);
//            System.out.println("DayShifts DEBUG: Total assignments in month: " + monthOfAssignments.size());
//            for (Assignments data : monthOfAssignments) {
//                System.out.println("DayShifts DEBUG: Assignment date: " + data.getYear() + "-" + data.getMonth() + "-" + data.getDay());
//            }
//        } else {
//            System.out.println("DayShifts DEBUG: Found " + matchCount + " assignments for " + year + "-" + (month+1) + "-" + date);
//        }
    }

    private void readParameterShifts(javax.servlet.http.HttpServletRequest request, int shiftCount,
            String newName, ArrayList<ShiftDefinitions> parameterShifts, Logger LOG) {

        for (int i = 0; i < shiftCount; i++) {
            String startTime = request.getParameter("startTime_" + i);
            String endTime = request.getParameter("endTime_" + i);
            String countStr = request.getParameter("count_" + i);
            String shiftType = request.getParameter("shift_" + i);

            if (countStr == null || shiftType == null) {
                break;
            }

            int count = Integer.parseInt(countStr);
            SessionData sessionData = new SessionData(request, null, LOG);
            int typeId = Assignments.getTypeID(sessionData, shiftType);

            parameterShifts.add(new ShiftDefinitions(newName, startTime, endTime, count, typeId, LOG));
        }
    }

    /**
     * Shift type names, in type-id order, so the option index is the stored type. Same list
     * EditShiftTemplates uses, which keeps the two shift editors' dropdowns identical.
     */
    private List<String> buildShiftTypes() {
        List<String> types = new ArrayList<>(Assignments.MAX_SHIFT_TYPES);
        for (int type = 0; type < Assignments.MAX_SHIFT_TYPES; type++) {
            types.add(Assignments.getShiftName(type));
        }
        return types;
    }

    /** Patroller-count choices for each shift row: 0 through the max slots a shift can hold. */
    private List<Integer> buildCountOptions() {
        List<Integer> options = new ArrayList<>(Assignments.MAX_ASSIGNMENT_SIZE + 1);
        for (int count = 0; count <= Assignments.MAX_ASSIGNMENT_SIZE; count++) {
            options.add(count);
        }
        return options;
    }

    private Hashtable<String, String> buildRosterLookup(PatrolData patrolData) {
        Hashtable<String, String> numToName = new Hashtable<>();
        ResultSet rosterResults = patrolData.resetRoster();
        Roster member;

        while ((member = patrolData.nextMember("", rosterResults)) != null) {
            numToName.put(member.getID(), member.getFullName_lastNameFirst());
        }

        return numToName;
    }

    private String[] buildSortedRoster(PatrolData patrolData) {
        String[] sortedRoster = new String[PatrolData.MAX_PATROLLERS];
        ResultSet rosterResults = patrolData.resetRoster();
        Roster member;
        int rosterSize = 0;

        while ((member = patrolData.nextMember("", rosterResults)) != null) {
            sortedRoster[rosterSize++] = member.getFullName_lastNameFirst();
        }

        return Arrays.copyOf(sortedRoster, rosterSize);
    }

    private void processSaveAssignments(javax.servlet.http.HttpServletRequest request, PatrolData patrolData,
            ArrayList<Assignments> assignments, String resort, Logger LOG) {

        // Nobody may hold two slots on the same shift. The grid stops it before the name goes in,
        // but that check is in the browser and can be bypassed, so the save has the last word.
        Map<String, Set<String>> alreadyOnShift = new HashMap<>();

        for (int i = 0; i < assignments.size(); i++) {
            Assignments data = assignments.get(i);
            data.setExisted(true);
            Set<String> onThisShift =
                alreadyOnShift.computeIfAbsent(shiftKey(data), key -> new HashSet<>());

            for (int j = 0; ; j++) {
                String param = "name" + PatrolData.IndexToString(i) + "_" + PatrolData.IndexToString(j);
                String name = request.getParameter(param);

                if (name == null) {
                    break;
                }

                Roster member = null;
                if (name.length() > 2) {
                    member = patrolData.getMemberByName2(name);
                }

                // add() is false when this shift already holds that id, which drops the duplicate
                // and keeps the first slot they were put in
                if (member != null && member.getID() != null) {
                    if (onThisShift.add(member.getID())) {
                        data.insertAt(resort, j, member.getID());
                    } else {
                        LOG.info("dropped a duplicate assignment of " + member.getID() + " to the "
                                   + shiftKey(data) + " shift on " + data.getDate());
                        data.insertAt(resort, j, "0");
                    }
                } else {
                    data.insertAt(resort, j, "0");
                }
            }

            patrolData.writeAssignment(data);
        }
    }

    /**
     * What counts as one shift for the "not twice on the same shift" rule: its times, not its row.
     * A shift needing more than 10 patrollers is entered as several rows sharing a start and end
     * time, and those are one shift as far as the person working it is concerned.
     */
    private String shiftKey(Assignments assignment) {
        return assignment.getStartingTimeString() + " - " + assignment.getEndingTimeString();
    }

    private void processDeleteAllShifts(PatrolData patrolData, ArrayList<Assignments> assignments) {
        for (int i = assignments.size() - 1; i >= 0; i--) {
            Assignments data = assignments.get(i);
            patrolData.deleteAssignment(data);
            assignments.remove(i);
        }
    }

    private void processAddNewShift(PatrolData patrolData, ArrayList<Assignments> assignments,
            ArrayList<ShiftDefinitions> parameterShifts, String newName,
            int date, int month, int year, Logger LOG) {

        saveShiftInfo(patrolData, assignments, parameterShifts, newName, date, month, year, LOG);

        int assignmentSize = assignments.size();
        ShiftDefinitions sData = new ShiftDefinitions(newName, "start time", "end time",
                1, Assignments.DAY_TYPE, LOG);

        String szAssignmentDate = getTodaysAssignmentString(date, month, year, assignmentSize + 1);
        Assignments assignment = new Assignments(szAssignmentDate, sData, LOG);

        String name = (newName != null && newName.length() > 1) ? newName : "";
        assignment.setEventName(name);
        assignments.add(assignment);
        patrolData.writeAssignment(assignment);
    }

    /**
     * Throw away everything on a day and rebuild it from a different shift template.
     *
     * The day's shifts and the patrollers on them are deleted outright - the patrollers live on the
     * shift rows, so removing the rows removes them. The chosen template is then applied, bringing
     * its own patrollers if it has any. Choosing a name that matches no template leaves the day
     * empty, which reverts it to the day-of-week template.
     */
    private void processReplaceShiftFormat(String resort, int dayOfWeek, int date, int month,
            int year, PatrolData patrolData, SessionData sessionData,
            javax.servlet.http.HttpServletRequest javaxRequest, String newFormat, Logger LOG) {

        ArrayList<Assignments> existing = new ArrayList<>();
        readAssignmentsAndShifts(resort, dayOfWeek, date, month, year,
                patrolData, sessionData, existing, javaxRequest);

        int patrollersRemoved = 0;
        for (Assignments assignment : existing) {
            patrollersRemoved += assignment.getUseCount(resort);
        }
        for (int i = existing.size() - 1; i >= 0; i--) {
            patrolData.deleteAssignment(existing.get(i));
        }

        ArrayList<Assignments> replacement = new ArrayList<>();
        ArrayList<ShiftDefinitions> templates = new ArrayList<>(patrolData.readShiftDefinitions());
        populateAssignmentsFromTemplate(replacement, templates, newFormat,
                date, month, year, resort, patrolData, LOG);

        for (Assignments assignment : replacement) {
            assignment.setExisted(false);
            patrolData.writeAssignment(assignment);
        }

        LOG.info("replaced the shift format on " + year + "-" + (month + 1) + "-" + date
                   + " with '" + newFormat + "', removing " + patrollersRemoved
                   + " patroller(s) and writing " + replacement.size() + " shift row(s)");
    }

    /**
     * A date nobody has saved yet still shows rows: the day-of-week template is rendered so the
     * director has something to edit. Those rows are not in the database, and New and Delete both
     * work on saved rows - Delete would find nothing to remove, and New would build its row at a
     * position the template rows are about to occupy. So persist what is on screen and re-read
     * before touching it.
     *
     * @param assignmentsFromDisk saved rows; replaced with the re-read rows when a save happens
     */
    private void ensureDisplayedRowsAreSaved(String resort, int dayOfWeek, int date, int month,
            int year, PatrolData patrolData, SessionData sessionData,
            javax.servlet.http.HttpServletRequest javaxRequest,
            ArrayList<Assignments> assignmentsFromDisk,
            ArrayList<ShiftDefinitions> parameterShifts, String newName, String selectedShift,
            Logger LOG) {

        if (!assignmentsFromDisk.isEmpty() || parameterShifts.isEmpty()) {
            return; //already saved, or there is nothing on screen to save
        }

        LOG.info("saving the " + parameterShifts.size() + " template row(s) shown for "
                   + year + "-" + (month + 1) + "-" + date + " before editing them");
        saveShiftInfo(patrolData, assignmentsFromDisk, parameterShifts, newName,
                date, month, year, resort,
                readTemplatePatrollersByRow(patrolData, templateNameOnScreen(selectedShift, dayOfWeek),
                        LOG), LOG);

        assignmentsFromDisk.clear();
        readAssignmentsAndShifts(resort, dayOfWeek, date, month, year,
                patrolData, sessionData, assignmentsFromDisk, javaxRequest);
    }

    /**
     * Which row's up/down arrow was clicked, or -1 when none was.
     * The arrows are named moveUp_&lt;row&gt; / moveDown_&lt;row&gt;.
     */
    private int findRequestedMove(javax.servlet.http.HttpServletRequest request, Integer shiftCount) {
        if (shiftCount == null) {
            return -1;
        }
        for (int i = 0; i < shiftCount; i++) {
            if (request.getParameter("moveUp_" + i) != null
                    || request.getParameter("moveDown_" + i) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Swap a shift row with its neighbour, carrying that shift's assigned patrollers along with
     * its times, type and count - the patrollers belong to the shift, not to the row position.
     *
     * The director's pending edits are saved first, so clicking an arrow does not quietly discard
     * whatever they had just typed (the same thing New, Delete and the assignment grid do).
     */
    private void processMoveShift(String resort, int dayOfWeek, int date, int month, int year,
            PatrolData patrolData, SessionData sessionData,
            javax.servlet.http.HttpServletRequest javaxRequest,
            ArrayList<Assignments> assignmentsFromDisk, ArrayList<ShiftDefinitions> parameterShifts,
            String newName, String selectedShift, int from, int to, Logger LOG) {

        processSaveShiftChanges(patrolData, assignmentsFromDisk, parameterShifts,
                newName, date, month, year, resort,
                templatePatrollersForFreshDay(patrolData, assignmentsFromDisk, selectedShift,
                        dayOfWeek, LOG), LOG);

        // saveShiftInfo rewrote every row, so re-read before touching positions
        ArrayList<Assignments> rows = new ArrayList<>();
        readAssignmentsAndShifts(resort, dayOfWeek, date, month, year,
                patrolData, sessionData, rows, javaxRequest);

        if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size()) {
            LOG.info("ignoring move of row " + from + " to " + to + ", only " + rows.size()
                       + " row(s) exist");
            return;
        }

        Assignments fromRow = rows.get(from);
        Assignments toRow = rows.get(to);
        String eventName = newName != null ? newName : "";

        // The date key carries the row position, so the rows keep their keys and exchange payloads
        Assignments movedDown = buildSwappedRow(fromRow.getDate(), toRow, eventName, LOG);
        Assignments movedUp = buildSwappedRow(toRow.getDate(), fromRow, eventName, LOG);

        patrolData.deleteAssignment(fromRow);
        patrolData.deleteAssignment(toRow);
        patrolData.writeAssignment(movedDown);
        patrolData.writeAssignment(movedUp);
    }

    /** A row at {@code date} holding {@code source}'s definition and assigned patrollers. */
    private Assignments buildSwappedRow(String date, Assignments source, String eventName, Logger LOG) {
        ShiftDefinitions definition = new ShiftDefinitions(eventName,
                source.getStartingTimeString(), source.getEndingTimeString(),
                source.getCount(), source.getType(), LOG);

        Assignments row = new Assignments(date, definition, LOG);
        row.setEventName(eventName);
        row.copyAssignedPatrollers(source);
        row.setExisted(false); //the old row was deleted, so this is an insert
        return row;
    }

    private void processDeleteShift(PatrolData patrolData, ArrayList<Assignments> assignments,
            int shiftToDelete, String newName, int date, int month, int year, Logger LOG) {

        if (shiftToDelete >= assignments.size()) {
            return;
        }

        Assignments data = assignments.get(shiftToDelete);
        patrolData.deleteAssignment(data);
        assignments.remove(shiftToDelete);

        int assignmentSize = assignments.size();
        for (int j = shiftToDelete; j < assignmentSize; j++) {
            data = assignments.get(j);
            patrolData.decrementAssignment(data);
        }

        if (assignmentSize == 0) {
            // Add placeholder
            ShiftDefinitions sData = new ShiftDefinitions(newName, "", "<empty>",
                    0, Assignments.DAY_TYPE, LOG);
            String szAssignmentDate = getTodaysAssignmentString(date, month, year, 1);
            Assignments assignment = new Assignments(szAssignmentDate, sData, LOG);
            assignment.setEventName(newName);
            assignments.add(assignment);
            patrolData.writeAssignment(assignment);
        }
    }

    private void processSaveShiftChanges(PatrolData patrolData, ArrayList<Assignments> assignments,
            ArrayList<ShiftDefinitions> parameterShifts, String newName,
            int date, int month, int year, Logger LOG) {
        processSaveShiftChanges(patrolData, assignments, parameterShifts, newName,
                date, month, year, null, null, LOG);
    }

    /**
     * @param templatePatrollersByRow template patrollers indexed by row, seeding rows that have
     *                                nothing saved yet; null to seed nothing
     */
    private void processSaveShiftChanges(PatrolData patrolData, ArrayList<Assignments> assignments,
            ArrayList<ShiftDefinitions> parameterShifts, String newName,
            int date, int month, int year, String resort,
            List<List<String>> templatePatrollersByRow, Logger LOG) {

        if (parameterShifts.size() > 0) {
            saveShiftInfo(patrolData, assignments, parameterShifts, newName, date, month, year,
                    resort, templatePatrollersByRow, LOG);
            // Delete leftover saved rows beyond the new row count
            // (e.g. the day was switched to a template with fewer shifts)
            for (int i = parameterShifts.size(); i < assignments.size(); i++) {
                patrolData.deleteAssignment(assignments.get(i));
            }
        } else if (newName != null && newName.trim().length() > 0) {
            // Add comment-only assignment
            ShiftDefinitions sData = new ShiftDefinitions(newName, " ", " ",
                    0, Assignments.DAY_TYPE, LOG);
            String szAssignmentDate = getTodaysAssignmentString(date, month, year,
                    assignments.size() + 1);
            Assignments assignment = new Assignments(szAssignmentDate, sData, LOG);
            assignment.setEventName(newName);
            assignments.add(assignment);
            patrolData.writeAssignment(assignment);
        }
    }

    private void saveShiftInfo(PatrolData patrolData, ArrayList<Assignments> assignmentsFromDisk,
            ArrayList<ShiftDefinitions> parameterShifts, String newName,
            int date, int month, int year, Logger LOG) {
        saveShiftInfo(patrolData, assignmentsFromDisk, parameterShifts, newName,
                date, month, year, null, null, LOG);
    }

    /**
     * Write the day's shift rows.
     *
     * The row form carries times, counts and types but no patrollers, so whoever is on a shift has
     * to be carried across the rewrite. An existing row keeps its own patrollers. A row with
     * nothing saved yet is seeded from the template it came from, which is what makes the
     * day-of-week auto-fill arrive with patrollers on it. Real assignments always win: template
     * patrollers only ever fill a position that had nothing in it.
     *
     * @param templatePatrollersByRow template patrollers indexed by row, or null for none
     */
    private void saveShiftInfo(PatrolData patrolData, ArrayList<Assignments> assignmentsFromDisk,
            ArrayList<ShiftDefinitions> parameterShifts, String newName,
            int date, int month, int year, String resort,
            List<List<String>> templatePatrollersByRow, Logger LOG) {

        Set<String> rosterIds = templatePatrollersByRow == null
                ? Set.of() : buildRosterLookup(patrolData).keySet();

        for (int shiftIndex = 0; shiftIndex < parameterShifts.size(); shiftIndex++) {
            ShiftDefinitions sh = parameterShifts.get(shiftIndex);
            String szAssignmentDate = getTodaysAssignmentString(date, month, year, shiftIndex + 1);

            Assignments assignment = new Assignments(szAssignmentDate, sh, LOG);
            assignment.setEventName(newName);

            Assignments prevAssignment = patrolData.readAssignment(assignment.getDate());
            patrolData.deleteAssignment(assignment);
            assignment.setExisted(false);

            if (prevAssignment != null) {
                assignment.copyAssignedPatrollers(prevAssignment);
            }
            else if (templatePatrollersByRow != null && shiftIndex < templatePatrollersByRow.size()) {
                applyTemplatePatrollers(assignment, resort, rosterIds,
                        templatePatrollersByRow.get(shiftIndex), LOG);
            }

            patrolData.writeAssignment(assignment);
        }
    }

    private String getTodaysAssignmentString(int date, int month, int year, int pos) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        Calendar cal = new GregorianCalendar(TimeZone.getDefault());
        cal.set(year, month, date);
        String szAssignmentDate = formatter.format(cal.getTime());
        return Assignments.createAssignmentName(szAssignmentDate, pos);
    }

    private String redirectToCalendar(HttpServletResponse response, String resort,
            int month, int year, String patrollerId) throws IOException {
        response.sendRedirect("/calendar-1/MonthCalendar?month=" + month + "&year=" + year +
                "&resort=" + resort + "&ID=" + patrollerId);
        return null;
    }

    private void addResortMetadata(String resort, Model model) {
        ResortData resortData = PatrolData.getResortInfo(resort);
        if (resortData != null) {
            model.addAttribute("resortUrl", resortData.getResortURL());
            model.addAttribute("resortImage", resortData.getResortImage());
            model.addAttribute("resortImgHeight", resortData.getImageHeight());
        }
    }

    /**
     * Dropdown display name for a shift template: the name with its "_&lt;position&gt;" suffix
     * removed.
     *
     * Splits on the LAST underscore, matching ShiftDefinitions.parsedEventName. This used to split
     * on the first, so a template whose name contained an underscore was grouped under the wrong
     * name here while the month calendar used the right one. New names can no longer contain an
     * underscore (EditShiftTemplates rejects them), but existing data still can.
     */
    private static String shiftDropdownName(String eventName) {
        int underscore = eventName.lastIndexOf('_');
        return underscore >= 0 ? eventName.substring(0, underscore) : eventName;
    }

    private void populateAssignmentsFromTemplate(ArrayList<Assignments> assignments,
            ArrayList<ShiftDefinitions> shiftTemplates, String selectedShift,
            int date, int month, int year, String resort, PatrolData patrolData, Logger LOG) {

        Map<String, List<String>> templatePatrollers =
                ShiftTemplatePatrollers.readAll(LOG, patrolData.getConnection());
        Set<String> rosterIds = buildRosterLookup(patrolData).keySet();

        // Find all shift definitions that match the selected shift name
        // (same first-"_" trimming used for the dropdown options)
        int position = 1;
        for (ShiftDefinitions template : shiftTemplates) {
            String parsedName = shiftDropdownName(template.getEventName());
            if (parsedName.equals(selectedShift)) {
                String szAssignmentDate = getTodaysAssignmentString(date, month, year, position);
                Assignments assignment = new Assignments(szAssignmentDate, template, LOG);
                assignment.setEventName(" ");
                //whoever the director pencilled into this template row shows up on the day
                applyTemplatePatrollers(assignment, resort, rosterIds,
                        templatePatrollers.get(template.getEventName()), LOG);
                assignments.add(assignment);
                position++;
            }
        }
    }

    /**
     * Seed a day's shift with the patrollers a director pencilled into the template row it came
     * from. Slots beyond the shift's count are skipped, as is anybody no longer on the roster -
     * a deleted member would otherwise surface on the calendar as "Err id (...)".
     */
    private void applyTemplatePatrollers(Assignments assignment, String resort,
            Set<String> rosterIds, List<String> templateSlots, Logger LOG) {
        if (templateSlots == null) {
            return;
        }
        for (int slot = 0; slot < templateSlots.size() && slot < assignment.getCount(); slot++) {
            String patrollerId = templateSlots.get(slot);
            if (ShiftTemplatePatrollers.isEmpty(patrollerId)) {
                continue;
            }
            if (!rosterIds.contains(patrollerId)) {
                LOG.info("template patroller " + patrollerId + " is no longer on the roster, skipped");
                continue;
            }
            assignment.insertAt(resort, slot, patrollerId);
        }
    }

    /**
     * Template patrollers to seed a day with, but only when the day has nothing saved - that is
     * the day-of-week auto-fill case. An existing day keeps its own patrollers, so this returns
     * null and nothing is seeded.
     */
    private List<List<String>> templatePatrollersForFreshDay(PatrolData patrolData,
            ArrayList<Assignments> assignmentsFromDisk, String selectedShift, int dayOfWeek,
            Logger LOG) {
        if (!assignmentsFromDisk.isEmpty()) {
            return null;
        }
        return readTemplatePatrollersByRow(patrolData,
                templateNameOnScreen(selectedShift, dayOfWeek), LOG);
    }

    /**
     * Which template the rows on screen came from. An empty selection means the director never
     * picked one, so the day-of-week template is what showEditShiftView displayed.
     */
    private String templateNameOnScreen(String selectedShift, int dayOfWeek) {
        return (selectedShift != null && !selectedShift.isEmpty())
                ? selectedShift : StaticUtils.szDays[dayOfWeek];
    }

    /** Each matching template row's patrollers, indexed by row, for the selected template. */
    private List<List<String>> readTemplatePatrollersByRow(PatrolData patrolData,
            String selectedShift, Logger LOG) {
        List<List<String>> byRow = new ArrayList<>();
        if (selectedShift == null || selectedShift.isEmpty()) {
            return byRow;
        }
        Map<String, List<String>> all =
                ShiftTemplatePatrollers.readAll(LOG, patrolData.getConnection());

        for (ShiftDefinitions template : patrolData.readShiftDefinitions()) {
            if (shiftDropdownName(template.getEventName()).equals(selectedShift)) {
                byRow.add(all.getOrDefault(template.getEventName(), new ArrayList<>()));
            }
        }
        return byRow;
    }
}
