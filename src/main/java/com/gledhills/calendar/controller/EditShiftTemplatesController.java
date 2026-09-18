package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.DirectorSettings;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.service.ShiftTimeAudit;
import com.gledhills.calendar.store.ShiftTemplatePatrollers;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.*;

import java.util.stream.IntStream;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.gledhills.calendar.service.ShiftTemplateApplyService;

import java.io.IOException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring Boot controller for editing shift templates.
 * Migrated from InnerEditShiftTemplates servlet.
 *
 * Handles GET /EditShiftTemplates - displays shift template editor
 * Handles POST /EditShiftTemplates - saves changes, adds, or deletes shifts
 */
@Controller
public class EditShiftTemplatesController {

    private final ShiftTemplateApplyService shiftTemplateApplyService;

    public EditShiftTemplatesController(ShiftTemplateApplyService shiftTemplateApplyService) {
        this.shiftTemplateApplyService = shiftTemplateApplyService;
    }

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

            // The "Assign Patrollers to this template" grid posts back its own slots. Handle that
            // before the row processing below, which only knows about times/counts/types.
            if (request.getParameter("saveTemplatePatrollersBtn") != null) {
                saveTemplatePatrollers(request, patrolData, selectedShift, LOG);
            }

            boolean assignPatrollers = request.getParameter("assignPatrollers") != null;
            boolean applyToCalendar = request.getParameter("applyToCalendar") != null
                || request.getParameter("confirmApply") != null;

            // Process form submission FIRST - this is what persists row edits/adds/deletes.
            // (The legacy servlet also parsed and wrote changes before honoring SaveChangesBtn.)
            ProcessResult result = processFormSubmission(request, sessionData, patrolData, selectedShift, deleteTemplateBtn, LOG);

            // processFormSubmission is what resolves the template's name: typing into "Event
            // Name/Day of Week" renames an existing template or creates a new one, and everything
            // below - the dropdown, the rows, the patroller counts - has to work from that rather
            // than from whatever the dropdown happened to post. Without this, adding a row to a
            // brand new template wrote it to the database and then displayed neither the row nor
            // the name, having replaced the typed name with "--New Shift Style--".
            // On a rejected name it returns the original, so the director stays where they were.
            selectedShift = result.selectedShift;

            if (result.errorMessage != null) {
                model.addAttribute("errorMessage", result.errorMessage);
            }

            // Handle Save Changes button - changes are saved above, now return to Directors
            // (but stay put when the name was rejected, so the director sees why)
            if (saveChangesBtn != null && result.errorMessage == null) {
                response.sendRedirect("Directors?resort=" + resort + "&ID=" + loggedInUserId);
                return null;
            }

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
            Map<String, Integer> templatePatrollerCounts = ShiftTemplatePatrollers
                .countByTemplateName(LOG, patrolData.getConnection(), allShifts,
                                     buildIdToName(patrolData).keySet());
            model.addAttribute("templatePatrollerCounts", templatePatrollerCounts);
            model.addAttribute("selectedTemplatePatrollers",
                               templatePatrollerCounts.getOrDefault(selectedShift, 0));
            model.addAttribute("assignPatrollers", assignPatrollers);
            model.addAttribute("applyToCalendar", applyToCalendar);
            // Every template's unusable times, not just the one on screen: a director fixing these
            // wants to know what is left across the resort, and the panel names the template each
            // row belongs to so they can go to it.
            model.addAttribute("timeProblems", ShiftTimeAudit.problems(allShifts));
            // Only Sunday..Saturday can be applied - the weekday in the name is what picks the days
            // out of the range - so the button is dead for every other template.
            model.addAttribute("selectedShiftIsDayOfWeek",
                               shiftTemplateApplyService.isDayOfWeekTemplate(selectedShift));
            if (applyToCalendar) {
                addApplyToCalendarModel(request, patrolData, resort, selectedShift, model, LOG);
            }
            if (assignPatrollers) {
                model.addAttribute("templateSlots",
                                   buildTemplateSlots(patrolData, selectedShifts, LOG));
                model.addAttribute("sortedRoster", buildSortedRoster(patrolData));
            }

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

        // A name may not START with an underscore: an unnamed row is stored as "_0", and
        // ShiftDefinitions.UNNAMED_TEMPLATE finds those with LIKE '\_%' so the Site Wide
        // Configuration job can delete them - a template called "_old" would be swept up with them.
        // An underscore *inside* the name is fine; everything splits on the last one.
        // The browser checks this too, but that can be bypassed.
        if (eventName != null && eventName.trim().startsWith("_")) {
            LOG.info("rejected shift template name starting with an underscore: (" + eventName + ")");
            return new ProcessResult(selectedShift, false, false,
                                     "Shift template names cannot start with an underscore ('_'). '"
                                       + eventName + "' was not saved.");
        }

        // Nothing is saved under a name that is not a name. Saving with the field blank used to
        // rename the template's rows to "_0", "_1" and so on - createShiftName just appends the
        // position - which is where the blank entry in the dropdown came from. The browser blocks
        // the submit, but that check can be bypassed.
        // Deleting is let through: a template already stuck with a bad name has to be removable.
        if (deleteTemplateBtn == null && eventName != null && !isUsableTemplateName(eventName)) {
            LOG.info("rejected unusable shift template name: (" + eventName + ")");
            return new ProcessResult(selectedShift, false, false,
                                     "The 'Event Name/Day of Week' field must have a valid value "
                                       + "to continue. Nothing was saved.");
        }
        String addShiftParam = request.getParameter("addShift");
        boolean addShift = (addShiftParam != null);

        String shiftCountParam = request.getParameter("shiftCount");
        int shiftCount = (shiftCountParam != null) ? Integer.parseInt(shiftCountParam) : 0;

        if (selectedShift == null) {
            selectedShift = "";
        }
        String originalSelectedShift = selectedShift;

        // Check if shift name changed (new shift)
        boolean newShift = (eventName != null && !eventName.isEmpty() && !eventName.equals(selectedShift));
        if (newShift) {
            selectedShift = eventName;
        }

        // "New" writes a row named after this template, so refuse to write one under a name that
        // is not a name. The browser disables the button until the field is filled in, but that
        // check can be bypassed - and when it was, pressing New created a template called ""
        // whose row came out as "_0", which then sat in the dropdown as a blank entry.
        if (addShift && !isUsableTemplateName(selectedShift)) {
            LOG.info("refusing to add a shift to an unnamed template: (" + selectedShift + ")");
            return new ProcessResult(originalSelectedShift, false, false,
                                     "Enter a name in 'Event Name/Day of Week' before adding a "
                                       + "shift to it.");
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

        // Both row rules, checked before the loop below, because that loop writes as it goes - a
        // row rejected halfway through would leave the earlier ones saved. Delete is exempt from
        // both: it is how a director gets rid of the offending row.
        if (!deleteShift) {
            for (int i = 0; i < shiftCount; i++) {
                if (request.getParameter("startTime_" + i) == null
                        || request.getParameter("count_" + i) == null) {
                    continue;
                }
                StartAndLabel row = StartAndLabel.of(request.getParameter("startTime_" + i),
                                                     request.getParameter("label_" + i), LOG);
                if (row.hasNeitherLabelNorStart()) {
                    LOG.info("refusing to save template '" + selectedShift + "': shift " + (i + 1)
                             + " has neither a Label nor a Start Time");
                    return new ProcessResult(originalSelectedShift, false, false,
                                             StartAndLabel.rowNeedsLabelOrStartMessage(i + 1));
                }

                //an End Time left alone keeps whatever history put there; an edited one is a time
                String typedEnd = request.getParameter("endTime_" + i);
                EndTimeEdit end = EndTimeEdit.of(typedEnd,
                        storedEndTime(shifts, ShiftDefinitions.createShiftName(eventName, i)));
                if (end.rejected()) {
                    LOG.info("refusing to save template '" + selectedShift + "': shift " + (i + 1)
                             + " end time was changed to something that is not a time");
                    return new ProcessResult(originalSelectedShift, false, false,
                                             EndTimeEdit.rowNeedsATimeMessage(i + 1, typedEnd));
                }
            }
        }
        ShiftDefinitions[] todaysData = new ShiftDefinitions[shiftCount];

        // Read shift data from form
        for (int i = 0; i < shiftCount; i++) {
            String tStart = request.getParameter("startTime_" + i);
            String tEnd = request.getParameter("endTime_" + i);
            String tLabel = request.getParameter("label_" + i);
            String tCount = request.getParameter("count_" + i);
            String tType = request.getParameter("shift_" + i);

            if (tStart == null || tEnd == null || tCount == null) {
                continue;
            }

            int tCnt = Integer.parseInt(tCount);
            int tTyp = parseShiftType(tType);
            String tEvent = ShiftDefinitions.createShiftName(eventName, i);

            StartAndLabel split = StartAndLabel.of(tStart, tLabel, LOG);
            String endValue = EndTimeEdit.of(tEnd, storedEndTime(shifts, tEvent)).value();
            todaysData[i] = new ShiftDefinitions(tEvent, split.start(), endValue, tCnt, tTyp,
                                                 split.label(), LOG);

            if (newShift) {
                patrolData.writeShift(todaysData[i]);
            }
            // Rows for an existing template are already in the DB - mark them so
            // writeShift() generates an UPDATE instead of a failing duplicate INSERT
            todaysData[i].setExists(true);
        }

        // A row delete renumbers every row after it (decrementShift renames _2 to _1, and so on),
        // so the patrollers keyed by those names have to move with them. Snapshot by row index
        // now, and rewrite once the renumbering is done.
        List<List<String>> patrollersByRow = snapshotTemplatePatrollers(patrolData, selectedShift, LOG);

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

        if (deleteTemplateBtn != null) {
            //the whole template is gone; forget every row's patrollers
            for (int row = 0; row < patrollersByRow.size(); row++) {
                ShiftTemplatePatrollers.deleteShift(LOG, patrolData.getConnection(),
                                                   ShiftDefinitions.createShiftName(selectedShift, row));
            }
        }
        else if (foundDeleteShift && shiftToDelete >= 0 && shiftToDelete < patrollersByRow.size()) {
            patrollersByRow.remove(shiftToDelete);
            rewriteTemplatePatrollers(patrolData, selectedShift, patrollersByRow,
                                      selectedSize, LOG);
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

        // Move a row up or down. This runs after the updates above, so the edits the director had
        // pending are saved first - the same thing New and Delete do.
        int moveFrom = findRequestedMove(request, shiftCount);
        if (moveFrom >= 0) {
            int moveTo = request.getParameter("moveUp_" + moveFrom) != null
                ? moveFrom - 1 : moveFrom + 1;
            swapTemplateRows(patrolData, selectedShift, moveFrom, moveTo, LOG);
        }

        return new ProcessResult(selectedShift, newShift, addShift);
    }

    /**
     * Which row's up/down arrow was clicked, or -1 when none was.
     * The arrows are named moveUp_&lt;row&gt; / moveDown_&lt;row&gt;.
     */
    private int findRequestedMove(HttpServletRequest request, int shiftCount) {
        for (int i = 0; i < shiftCount; i++) {
            if (request.getParameter("moveUp_" + i) != null
                    || request.getParameter("moveDown_" + i) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Swap two rows of a template. A row's position lives in the suffix of its shift name
     * ("Sunday_a", "Sunday_b", ...), so the two names stay put and exchange their definitions.
     * Unlike a day's shifts, a template carries no patrollers, so there is nothing else to move.
     */
    private void swapTemplateRows(PatrolData patrolData, String templateName, int from, int to,
                                  Logger LOG) {
        List<ShiftDefinitions> rows = new ArrayList<>();
        for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
            if (shift.parsedEventName().equals(templateName)) {
                rows.add(shift);
            }
        }

        if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size()) {
            LOG.info("ignoring move of row " + from + " to " + to + ", template '" + templateName
                       + "' has " + rows.size() + " row(s)");
            return;
        }

        ShiftDefinitions fromRow = rows.get(from);
        ShiftDefinitions toRow = rows.get(to);

        ShiftDefinitions movedToFrom = new ShiftDefinitions(fromRow.getEventName(),
            toRow.getStartString(), toRow.getEndString(), toRow.getCount(), toRow.getType(), LOG);
        ShiftDefinitions movedToTo = new ShiftDefinitions(toRow.getEventName(),
            fromRow.getStartString(), fromRow.getEndString(), fromRow.getCount(),
            fromRow.getType(), LOG);

        patrolData.deleteShift(fromRow);
        patrolData.deleteShift(toRow);
        patrolData.writeShift(movedToFrom);
        patrolData.writeShift(movedToTo);

        //the patrollers belong to the shift, so they travel with it
        ShiftTemplatePatrollers.swap(LOG, patrolData.getConnection(),
                                     fromRow.getEventName(), toRow.getEventName());
    }

    /** Each row's patrollers for a template, indexed by row position. */
    private List<List<String>> snapshotTemplatePatrollers(PatrolData patrolData, String templateName,
                                                          Logger LOG) {
        List<List<String>> byRow = new ArrayList<>();
        if (templateName == null || templateName.isEmpty()) {
            return byRow;
        }
        Map<String, List<String>> all =
            ShiftTemplatePatrollers.readAll(LOG, patrolData.getConnection());

        for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
            if (shift.parsedEventName().equals(templateName)) {
                byRow.add(new ArrayList<>(
                    all.getOrDefault(shift.getEventName(), new ArrayList<>())));
            }
        }
        return byRow;
    }

    /**
     * Re-key the patrollers onto row names 0..n-1 after a delete shifted everything up, and clear
     * any name beyond the new end so the removed row leaves nothing behind.
     */
    private void rewriteTemplatePatrollers(PatrolData patrolData, String templateName,
                                           List<List<String>> patrollersByRow, int previousRowCount,
                                           Logger LOG) {
        for (int row = 0; row < patrollersByRow.size(); row++) {
            ShiftTemplatePatrollers.write(LOG, patrolData.getConnection(),
                                          ShiftDefinitions.createShiftName(templateName, row),
                                          patrollersByRow.get(row));
        }
        for (int row = patrollersByRow.size(); row <= previousRowCount; row++) {
            ShiftTemplatePatrollers.deleteShift(LOG, patrolData.getConnection(),
                                                ShiftDefinitions.createShiftName(templateName, row));
        }
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
    /**
     * The resort's season as two {@code yyyy-MM-dd} dates, for the Apply panel to start from.
     *
     * <p>The season is stored as month and day with no year (Directors &rarr; Web Site
     * Preferences), and a ski season crosses New Year, so the years have to be worked out:
     *
     * <ul>
     *   <li><b>A season that wraps</b> - starts November, ends April - runs from this year into
     *       next if we are already past its start, and from last year into this if we are not. In
     *       February you are inside the season that began last November, and that is the range you
     *       want offered.</li>
     *   <li><b>A season inside one year</b> is simply this year.</li>
     * </ul>
     *
     * <p>Returns two empty strings rather than throwing if the settings are missing or hold a date
     * that does not exist: an unhelpful default is better than a panel that will not open.
     *
     * <p><b>The stored month is 1-based</b> - January is 1. {@code DirectorSettings.getMonth}
     * parses it straight out of a {@code dd-mm} string, {@code isInSeason} is handed
     * {@code Calendar.MONTH + 1} to compare against it, and the blackout check subtracts one
     * before building a {@code Date}. Adding one here put every prefilled range a month late.
     */
    private String[] seasonRange(PatrolData patrolData, Logger LOG) {
        try {
            DirectorSettings settings = patrolData.readDirectorSettings();
            if (settings == null) {
                return new String[] {"", ""};
            }
            MonthDay start = MonthDay.of(settings.getStartMonth(), settings.getStartDay());
            MonthDay end = MonthDay.of(settings.getEndMonth(), settings.getEndDay());

            LocalDate today = LocalDate.now();
            int startYear = today.getYear();
            int endYear = today.getYear();

            if (end.isBefore(start)) {                       //a season that crosses New Year
                if (!MonthDay.from(today).isAfter(end)) {
                    startYear--;                             //January..April: the tail of the
                                                             //season that began last winter
                }
                else {
                    endYear++;                               //winter, or the summer before it:
                                                             //the season now or about to start
                }
            }
            return new String[] {start.atYear(startYear).toString(), end.atYear(endYear).toString()};
        }
        catch (Exception e) {
            LOG.info("could not work out the season range for the apply panel: " + e.getMessage());
            return new String[] {"", ""};
        }
    }

    /**
     * Drive the "Apply to calendar" panel. Apply writes straight away unless days in the range are
     * already scheduled, in which case it stops and asks; the answer comes back on the next Apply,
     * marked by the question's own hidden field, and only then is anything written.
     */
    private void addApplyToCalendarModel(HttpServletRequest request, PatrolData patrolData,
                                         String resort, String selectedShift, Model model,
                                         Logger LOG) {
        LocalDate from = parseDate(request.getParameter("applyFrom"));
        LocalDate to = parseDate(request.getParameter("applyTo"));

        //Opening the panel offers the season as the range, since that is what a director applying
        //a weekday template almost always means. Only when nothing was posted: once they have
        //typed dates, those are theirs and must survive the conflict question's round trip.
        String[] season = seasonRange(patrolData, LOG);
        model.addAttribute("applyFrom",
                           from == null ? season[0] : request.getParameter("applyFrom"));
        model.addAttribute("applyTo",
                           to == null ? season[1] : request.getParameter("applyTo"));

        if (request.getParameter("confirmApply") == null) {
            return; // the panel just opened; there is nothing to work out yet
        }
        if (from == null || to == null) {
            model.addAttribute("applyNeedsDates", true);
            return;
        }
        if (to.isBefore(from)) {
            model.addAttribute("applyError", "The 'to' date is before the 'from' date.");
            return;
        }

        // The skip/overwrite radio answers the conflict question. Anything but a literal
        // "overwrite" means skip, so neither an unanswered question nor a mangled parameter can be
        // the thing that deletes a shift.
        ShiftTemplateApplyService.ConflictMode mode =
            ShiftTemplateApplyService.ConflictMode.of(request.getParameter("conflictMode"));
        ShiftTemplateApplyService.Preview preview = shiftTemplateApplyService
            .preview(resort, patrolData, selectedShift, from, to, mode, LOG);

        // Stop and ask, unless this Apply is carrying the answer back
        if (!preview.isApplicable()
                || (preview.isAskingConflictQuestion()
                        && request.getParameter("conflictAnswered") == null)) {
            model.addAttribute("applyPreview", preview);
            return;
        }
        if (preview.getDaysAffectedCount() == 0 && !preview.hasConflicts()) {
            model.addAttribute("applyError",
                               "There are no " + selectedShift + "s between " + from + " and " + to
                                 + ".");
            return;
        }

        int written = shiftTemplateApplyService.apply(resort, patrolData, selectedShift,
                                                      from, to, mode, LOG);
        String result = "Put " + selectedShift + " shifts on " + written + " day(s).";

        //preview was read before apply() ran, so it still describes what was there
        if (preview.isOverwriting()) {
            result += " " + preview.getDaysInConflictCount()
                + " of those already had shifts and were overwritten, removing "
                + preview.patrollersInConflict() + " scheduled patroller(s).";
        }
        else if (preview.hasConflicts()) {
            result += " " + preview.getDaysInConflictCount()
                + " day(s) already had shifts and were left alone.";
        }
        model.addAttribute("applyResult", result);
        model.addAttribute("applyDone", true);
    }

    /** An HTML date input gives yyyy-MM-dd; anything else is treated as not supplied. */
    private LocalDate parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        }
        catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The slots of every row of the selected template, with whoever is pencilled into each.
     * One flat list, because the grid draws one line per slot.
     */
    private List<TemplateSlot> buildTemplateSlots(PatrolData patrolData,
                                                  List<ShiftWithIndex> selectedShifts, Logger LOG) {
        Map<String, List<String>> patrollersByShift =
            ShiftTemplatePatrollers.readAll(LOG, patrolData.getConnection());
        Map<String, String> idToName = buildIdToName(patrolData);

        List<TemplateSlot> slots = new ArrayList<>();
        for (ShiftWithIndex row : selectedShifts) {
            ShiftDefinitions shift = row.getShift();
            List<String> assigned =
                patrollersByShift.getOrDefault(shift.getEventName(), new ArrayList<>());
            //two values, deliberately: the key groups the rows the browser checks for duplicates
            //and must stay keyed on the times alone, while the label is only ever shown
            String time = shiftKey(shift);
            String displayTime = shift.describeLabelAndTimes();

            for (int position = 0; position < shift.getCount(); position++) {
                String patrollerId = position < assigned.size() ? assigned.get(position) : null;
                String memberName = ShiftTemplatePatrollers.isEmpty(patrollerId)
                    ? "" : idToName.getOrDefault(patrollerId, "");

                slots.add(new TemplateSlot(slotFieldName(row.getIndex(), position), time,
                                           displayTime, memberName));
            }
        }
        return slots;
    }

    /**
     * Whether a template name is one we can store. Mirrors isUsableTemplateName in
     * edit-shift-templates.html, which greys out the New button on the same rule - keep the two
     * in step. Blank is out, the dropdown's "--New Shift Style--" label is a menu entry rather
     * than a name, and a name may not start with an underscore - see the check in
     * processFormSubmission for why that one is not merely cosmetic.
     *
     * <p>An underscore <em>inside</em> the name is allowed. The row position is a "_&lt;n&gt;"
     * suffix and every reader splits on the LAST underscore, so "Saturday_old_3" is row 3 of
     * "Saturday_old" - which is a real template on Brighton's site and has always worked.
     */
    private static boolean isUsableTemplateName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        return !trimmed.isEmpty() && !trimmed.equals(PatrolData.NEW_SHIFT_STYLE)
                 && !trimmed.startsWith("_");
    }

    /** Form field carrying the name pencilled into one slot. */
    private static String slotFieldName(int row, int position) {
        return "slot_" + row + "_" + position;
    }

    /**
     * What counts as one shift for the "not twice on the same shift" rule: its times, not its row.
     * A shift needing more than 10 patrollers is entered as several rows sharing a start and end
     * time, and those are one shift as far as the person working it is concerned. This has to match
     * the time the grid shows, since that is what the browser check groups by.
     */
    /** The End Time currently stored for this template row, or "" when the row is new. */
    private static String storedEndTime(List<ShiftDefinitions> shifts, String eventName) {
        for (ShiftDefinitions shift : shifts) {
            if (eventName.equals(shift.getEventName())) {
                return shift.getEndString();
            }
        }
        return "";
    }

    private static String shiftKey(ShiftDefinitions shift) {
        return shift.getShiftKey();
    }

    /**
     * Store what the grid posted back. Names come from the roster list, so they are turned back
     * into ids; anything unrecognised is treated as an empty slot rather than guessed at.
     *
     * Nobody may hold two slots on the same shift. The grid stops it before the name goes in, but
     * that check is in the browser and can be bypassed, so this has the last word.
     */
    private void saveTemplatePatrollers(HttpServletRequest request, PatrolData patrolData,
                                        String selectedShift, Logger LOG) {
        if (selectedShift == null || selectedShift.isEmpty()) {
            return;
        }
        Map<String, Set<String>> alreadyOnShift = new LinkedHashMap<>();

        for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
            if (!shift.parsedEventName().equals(selectedShift)) {
                continue;
            }
            int row = shift.getEventIndex();
            List<String> patrollerIds = new ArrayList<>();
            boolean sawAnyField = false;
            Set<String> onThisShift =
                alreadyOnShift.computeIfAbsent(shiftKey(shift), key -> new HashSet<>());

            for (int position = 0; position < shift.getCount(); position++) {
                String name = request.getParameter(slotFieldName(row, position));
                if (name == null) {
                    patrollerIds.add(ShiftTemplatePatrollers.EMPTY_SLOT);
                    continue;
                }
                sawAnyField = true;
                Roster member = name.trim().length() > 2 ? patrolData.getMemberByName2(name) : null;

                if (member == null || member.getID() == null) {
                    patrollerIds.add(ShiftTemplatePatrollers.EMPTY_SLOT);
                }
                // add() is false when this shift already holds that id, which drops the duplicate
                // and keeps the first slot they were put in
                else if (onThisShift.add(member.getID())) {
                    patrollerIds.add(member.getID());
                }
                else {
                    LOG.info("dropped a duplicate assignment of " + member.getID() + " to the "
                               + shiftKey(shift) + " shift of template '" + selectedShift + "'");
                    patrollerIds.add(ShiftTemplatePatrollers.EMPTY_SLOT);
                }
            }

            //a row whose fields were not submitted at all is left alone, rather than cleared
            if (sawAnyField) {
                ShiftTemplatePatrollers.write(LOG, patrolData.getConnection(),
                                              shift.getEventName(), patrollerIds);
            }
        }
    }

    /** Roster names for the grid's pick list, in the order the roster returns them. */
    private String[] buildSortedRoster(PatrolData patrolData) {
        List<String> names = new ArrayList<>();
        java.sql.ResultSet rosterResults = patrolData.resetRoster();
        Roster member;

        while ((member = patrolData.nextMember("", rosterResults)) != null) {
            names.add(member.getFullName_lastNameFirst());
        }
        return names.toArray(new String[0]);
    }

    private Map<String, String> buildIdToName(PatrolData patrolData) {
        Map<String, String> idToName = new LinkedHashMap<>();
        java.sql.ResultSet rosterResults = patrolData.resetRoster();
        Roster member;

        while ((member = patrolData.nextMember("", rosterResults)) != null) {
            idToName.put(member.getID(), member.getFullName_lastNameFirst());
        }
        return idToName;
    }

    /**
     * One slot of one template row, for the "Assign Patrollers to this template" grid.
     *
     * <p><b>{@code time} and {@code displayTime} are not interchangeable.</b> {@code time} is the
     * shiftKey - the times alone - and the grid renders it into {@code data-shift}, which is what
     * the browser groups by when it checks that nobody is on the same shift twice. A shift needing
     * more than 10 patrollers is several rows sharing a time, and those rows need not share a
     * label, so putting the label in that key would split one real shift into two and let somebody
     * be booked on both. {@code displayTime} carries the label and is only ever shown.
     */
    public static class TemplateSlot {
        private final String fieldName;
        private final String time;
        private final String displayTime;
        private final String memberName;

        TemplateSlot(String fieldName, String time, String displayTime, String memberName) {
            this.fieldName = fieldName;
            this.time = time;
            this.displayTime = displayTime;
            this.memberName = memberName;
        }

        public String getFieldName() {
            return fieldName;
        }

        /** The grouping key: times only. Rendered into data-shift, never shown. */
        public String getTime() {
            return time;
        }

        /** What the row shows - "(dir) 8:00 - 16:30". Never used for grouping. */
        public String getDisplayTime() {
            return displayTime;
        }

        public String getMemberName() {
            return memberName;
        }
    }

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
        /** Why nothing was saved, or null when the submission was accepted. */
        final String errorMessage;

        ProcessResult(String selectedShift, boolean newShift, boolean addShift) {
            this(selectedShift, newShift, addShift, null);
        }

        ProcessResult(String selectedShift, boolean newShift, boolean addShift, String errorMessage) {
            this.selectedShift = selectedShift;
            this.newShift = newShift;
            this.addShift = addShift;
            this.errorMessage = errorMessage;
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
     * Patroller-count choices: 0 through the most slots a shift can hold. 0 is offered so a
     * template row can be kept but staffed by nobody; a shift added with "New" stores 1.
     */
    private List<Integer> buildCountOptions() {
        return IntStream.rangeClosed(0, Assignments.MAX_ASSIGNMENT_SIZE)
            .boxed()
            .collect(Collectors.toList());
    }
}
