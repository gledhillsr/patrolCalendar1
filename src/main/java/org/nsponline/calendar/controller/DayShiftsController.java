package org.nsponline.calendar.controller;

import org.nsponline.calendar.store.Assignments;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.store.ShiftDefinitions;
import org.nsponline.calendar.utils.*;
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
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        String patrollerId = ID != null ? ID : sessionData.getLoggedInUserId();
        if (patrollerId == null || patrollerId.trim().isEmpty()) {
            response.sendRedirect("/calendar-1/MemberLogin?resort=" + resort + "&NSPgoto=MonthCalendar");
            return null;
        }

        Roster editorsMemberData = patrolData.getMemberByID(patrollerId);
        boolean isDirector = patrollerId.equalsIgnoreCase(sessionData.getBackDoorUser()) ||
                (editorsMemberData != null && editorsMemberData.isDirector());

        if (!isDirector) {
            // Non-directors can only view shift details
            return showShiftDetailsView(resort, dayOfWeek, date, month, year, patrollerId,
                    patrolData, sessionData, model, LOG);
        }

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
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger LOG = new Logger(DayShiftsController.class, javaxRequest, "POST", resort, Logger.INFO);
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        String patrollerId = ID != null ? ID : sessionData.getLoggedInUserId();

        // Process the change request
        ArrayList<Assignments> assignmentsFromDisk = new ArrayList<>();
        ArrayList<ShiftDefinitions> parameterShifts = new ArrayList<>();

        readAssignmentsAndShifts(resort, dayOfWeek, date, month, year,
                patrolData, sessionData, assignmentsFromDisk, javaxRequest);

        readParameterShifts(javaxRequest, shiftCount != null ? shiftCount : 0,
                newName, parameterShifts, LOG);

        // Handle different button actions
        if (saveAssignmentBtn != null) {
            processSaveAssignments(javaxRequest, patrolData, assignmentsFromDisk, resort);
            return redirectToCalendar(response, resort, month, year, patrollerId);
        } else if (deleteAllShiftsBtn != null) {
            processDeleteAllShifts(patrolData, assignmentsFromDisk);
            return redirectToCalendar(response, resort, month, year, patrollerId);
        } else if (dropdownShift2 != null && !dropdownShift2.isEmpty() &&
                !dropdownShift2.equals("--New Shift Style--")) {
            assignmentsFromDisk.clear();
            selectedShift = dropdownShift2;
        } else if (newShift != null) {
            processAddNewShift(patrolData, assignmentsFromDisk, parameterShifts, newName,
                    date, month, year, LOG);
        } else if (saveShiftBtn != null) {
            processSaveShiftChanges(patrolData, assignmentsFromDisk, parameterShifts,
                    newName, date, month, year, LOG);
            return redirectToCalendar(response, resort, month, year, patrollerId);
        }

        // Check for delete action
        if (shiftCount != null) {
            for (int i = 0; i < shiftCount; i++) {
                if (javaxRequest.getParameter("delete_" + i) != null) {
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

        if (selectedShift == null || selectedShift.isEmpty()) {
            selectedShift = StaticUtils.szDays[dayOfWeek];
        }

        // If no assignments exist for this date, populate from shift template
        if (assignments.isEmpty() && dropdownShift == null) {
            populateAssignmentsFromTemplate(assignments, shiftTemplates, selectedShift,
                    date, month, year, LOG);
        }

        String originalName = assignments.isEmpty() ? "" : assignments.get(0).getEventName();

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
        model.addAttribute("selectedShift", selectedShift);
        model.addAttribute("originalName", originalName);
        model.addAttribute("numToName", numToName);
        model.addAttribute("sortedRoster", sortedRoster);
        model.addAttribute("isDirector", true);
        model.addAttribute("doAssignments", doAssignments);
        model.addAttribute("maxShiftsReached", assignments.size() >= ShiftDefinitions.MAX);

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

        if (matchCount == 0) {
            System.out.println("DayShifts DEBUG: No assignments found for " + year + "-" + (month+1) + "-" + date);
            System.out.println("DayShifts DEBUG: Total assignments in month: " + monthOfAssignments.size());
            for (Assignments data : monthOfAssignments) {
                System.out.println("DayShifts DEBUG: Assignment date: " + data.getYear() + "-" + data.getMonth() + "-" + data.getDay());
            }
        } else {
            System.out.println("DayShifts DEBUG: Found " + matchCount + " assignments for " + year + "-" + (month+1) + "-" + date);
        }
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
            ArrayList<Assignments> assignments, String resort) {

        for (int i = 0; i < assignments.size(); i++) {
            Assignments data = assignments.get(i);
            data.setExisted(true);

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

                if (member != null && member.getID() != null) {
                    data.insertAt(resort, j, member.getID());
                } else {
                    data.insertAt(resort, j, "0");
                }
            }

            patrolData.writeAssignment(data);
        }
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

        if (parameterShifts.size() > 0) {
            saveShiftInfo(patrolData, assignments, parameterShifts, newName, date, month, year, LOG);
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

    private void populateAssignmentsFromTemplate(ArrayList<Assignments> assignments,
            ArrayList<ShiftDefinitions> shiftTemplates, String selectedShift,
            int date, int month, int year, Logger LOG) {

        // Find all shift definitions that match the selected shift name
        int position = 1;
        for (ShiftDefinitions template : shiftTemplates) {
            String parsedName = template.parsedEventName();
            if (parsedName.equals(selectedShift)) {
                String szAssignmentDate = getTodaysAssignmentString(date, month, year, position);
                Assignments assignment = new Assignments(szAssignmentDate, template, LOG);
                assignment.setEventName(" ");
                assignments.add(assignment);
                position++;
            }
        }
    }
}
