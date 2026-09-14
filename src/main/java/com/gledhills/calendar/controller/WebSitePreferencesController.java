package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import com.gledhills.calendar.utils.*;
import com.gledhills.calendar.ShiftLabelMigration;
import com.gledhills.calendar.ShiftTimeCleanup;
import com.gledhills.calendar.dto.WebSitePreferencesForm;
import com.gledhills.calendar.service.ShiftTimeAudit;
import com.gledhills.calendar.store.DirectorSettings;
import com.gledhills.calendar.store.Roster;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

/**
 * Spring Boot controller for website preferences.
 * Migrated from InnerWebSitePreferences servlet.
 *
 * Handles GET /WebSitePreferences - displays preferences form for directors
 * Handles POST /WebSitePreferences - saves preferences changes
 */
@Controller
public class WebSitePreferencesController {

    @GetMapping("/WebSitePreferences")
    public String showPreferences(
            @RequestParam String resort,
            @RequestParam(name = "ID") String id,
            //what the tidy button did, handed back through the redirect so the page can say so
            @RequestParam(name = "tidied", required = false) String tidied,
            @RequestParam(name = "scheduledTidied", required = false) String scheduledTidied,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        // Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(WebSitePreferencesController.class, javaxRequest, "GET", resort, Logger.INFO);

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
                "WebSitePreferences", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null; // Redirect already sent
        }

        try {
            // Check if user is a director
            String loggedInUserId = sessionData.getLoggedInUserId();
            Roster member = patrolData.getMemberByID(loggedInUserId);
            boolean isDirector = (member != null && member.isDirector());

            if (!isDirector) {
                // Non-directors see "not authorized" message
                model.addAttribute("resort", resort);
                model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
                model.addAttribute("patrollerId", loggedInUserId);
                model.addAttribute("isDirector", false);

                // Add resort metadata for header
                ResortData resortData = PatrolData.getResortInfo(resort);
                if (resortData != null) {
                    model.addAttribute("resortUrl", resortData.getResortURL());
                    model.addAttribute("resortImage", resortData.getResortImage());
                    model.addAttribute("resortImgHeight", resortData.getImageHeight());
                }

                return "website-preferences";
            }

            // Load current settings from database
            DirectorSettings settings = patrolData.readDirectorSettings();

            // Populate form with current settings
            WebSitePreferencesForm form = new WebSitePreferencesForm();
            form.setResort(resort);
            form.setEmailPatrollers(settings.getNotifyChanges());
            form.setEmailReminder(settings.getSendReminder());
            form.setReminderDays(settings.getReminderDays());
            form.setEmailAll(settings.getEmailAll());
            //what is stored, or the built-in default when this resort has never answered - so the
            //dropdown opens on the zone actually in force rather than on nothing
            form.setTimeZone(settings.getTimeZone().isEmpty()
                                 ? ResortTimeZones.forResort(resort).getId()
                                 : settings.getTimeZone());
            form.setSyncEnabled(settings.getSyncEnabled());
            form.setOnlyDirectors(settings.getDirectorsOnlyChange());
            form.setRemoveAccess(settings.getRemoveAccess());
            form.setStartDay(settings.getStartDay());
            form.setStartMonth(settings.getStartMonth());
            form.setEndDay(settings.getEndDay());
            form.setEndMonth(settings.getEndMonth());
            form.setUseBlackOut(settings.getUseBlackOut());
            form.setBlackOutStartDay(settings.getBlackOutStartDay());
            form.setBlackOutStartMonth(settings.getBlackOutStartMonth());
            form.setBlackOutStartYear(settings.getBlackOutStartYear());
            form.setBlackOutEndDay(settings.getBlackOutEndDay());
            form.setBlackOutEndMonth(settings.getBlackOutEndMonth());
            form.setBlackOutEndYear(settings.getBlackOutEndYear());

            // Add to model
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", loggedInUserId);
            model.addAttribute("isDirector", true);
            model.addAttribute("preferencesForm", form);
            // How ready this resort's templates are to sync. Read here rather than linked to
            // blindly, because "you have 6 shifts to fix" is the thing that tells a director
            // whether the Edit Shift Templates panel is worth opening at all.
            model.addAttribute("syncReadiness",
                               ShiftTimeAudit.readiness(patrolData.readShiftDefinitions()));
            model.addAttribute("tidied", tidied);
            model.addAttribute("maySwitchOnSync", PatrolData.maySwitchOnCalendarSync(resort));
            model.addAttribute("scheduledTidied", scheduledTidied);

            // Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            return "website-preferences";

        } finally {
            patrolData.close();
        }
    }

    @PostMapping("/WebSitePreferences")
    public String savePreferences(
            @Valid @ModelAttribute("preferencesForm") WebSitePreferencesForm form,
            BindingResult bindingResult,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        // Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(WebSitePreferencesController.class, javaxRequest, "POST", form.getResort(), Logger.INFO);

        // Validate resort
        if (!PatrolData.isValidResort(form.getResort())) {
            response.sendError(400, "Resort not found: " + form.getResort());
            return null;
        }

        // If validation errors, return to form
        if (bindingResult.hasErrors()) {
            return showPreferencesWithErrors(form, bindingResult, request, response, model, LOG);
        }

        // Initialize session and patrol data
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        // the posted form carries no "ID" field for the Logger to pick up
        LOG.setId(sessionData.getLoggedInUserId());
        PatrolData patrolData = new PatrolData(form.getResort(), sessionData, LOG);

        try {
            // Check if user is a director
            String loggedInUserId = sessionData.getLoggedInUserId();
            Roster member = patrolData.getMemberByID(loggedInUserId);
            boolean isDirector = (member != null && member.isDirector());

            if (!isDirector) {
                model.addAttribute("errorMessage", "You must be a director to modify preferences");
                return showPreferencesWithErrors(form, bindingResult, request, response, model, LOG);
            }

            // Load current settings
            DirectorSettings settings = patrolData.readDirectorSettings();

            // Update settings from form
            settings.setNotifyChanges(form.getEmailPatrollers() != null && form.getEmailPatrollers());
            settings.setSendReminder(form.getEmailReminder() != null && form.getEmailReminder());
            settings.setReminderDays(form.getReminderDays());
            settings.setEmailAll(form.getEmailAll() != null && form.getEmailAll());
            settings.setTimeZone(form.getTimeZone());
            //Only a resort cleared for calendar sync may change the switch, and the stored value is
            //left exactly as it is for everybody else - not set to false, which would be a change
            //in its own right. A disabled checkbox is not posted at all, so an ordinary save from
            //one of those resorts would otherwise read as "the director turned it off".
            if (PatrolData.maySwitchOnCalendarSync(form.getResort())) {
                settings.setSyncEnabled(form.isSyncEnabled());
            }
            else if (form.isSyncEnabled()) {
                LOG.info("ignored a calendar sync switch posted for " + form.getResort()
                           + ", which is not cleared for it");
            }
            settings.setDirectorsOnlyChange(form.getOnlyDirectors() != null && form.getOnlyDirectors());
            settings.setRemoveAccess(form.getRemoveAccess());
            settings.setStartDay(form.getStartDay());
            settings.setStartMonth(form.getStartMonth());
            settings.setEndDay(form.getEndDay());
            settings.setEndMonth(form.getEndMonth());
            settings.setUseBlackOut(form.getUseBlackOut() != null && form.getUseBlackOut());
            settings.setBlackStartDay(form.getBlackOutStartDay());
            settings.setBlackStartMonth(form.getBlackOutStartMonth());
            settings.setBlackStartYear(form.getBlackOutStartYear());
            settings.setBlackEndDay(form.getBlackOutEndDay());
            settings.setBlackEndMonth(form.getBlackOutEndMonth());
            settings.setBlackEndYear(form.getBlackOutEndYear());

            // Save to database
            settings.write(patrolData.getConnection());

            // Redirect back to GET with success message
            response.sendRedirect("WebSitePreferences?resort=" + form.getResort() +
                                "&ID=" + loggedInUserId + "&success=true");
            return null;

        } finally {
            patrolData.close();
        }
    }

    /**
     * Helper method to reconstruct form when validation errors occur.
     */
    private String showPreferencesWithErrors(WebSitePreferencesForm form, BindingResult bindingResult,
                                            HttpServletRequest request, HttpServletResponse response,
                                            Model model, Logger LOG) throws IOException {
        // Add validation errors to model
        model.addAttribute("org.springframework.validation.BindingResult.preferencesForm", bindingResult);
        model.addAttribute("preferencesForm", form);

        // Initialize patrol data
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(form.getResort(), sessionData, LOG);

        try {
            String loggedInUserId = sessionData.getLoggedInUserId();
            Roster member = patrolData.getMemberByID(loggedInUserId);
            boolean isDirector = (member != null && member.isDirector());

            model.addAttribute("resort", form.getResort());
            model.addAttribute("resortFullName", PatrolData.getResortFullName(form.getResort(), LOG));
            model.addAttribute("patrollerId", loggedInUserId);
            model.addAttribute("isDirector", isDirector);

            // Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(form.getResort());
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            return "website-preferences";

        } finally {
            patrolData.close();
        }
    }

    /**
     * Tidy this one resort's shift template times, from the Personal Calendar Sync panel.
     *
     * <p>The same pass the Site Wide Configuration button runs over all 44 resorts
     * ({@link ShiftTimeCleanup}), scoped to the resort whose page this is - so a director can put
     * their own templates right without the site owner doing it for them, and without reaching into
     * anybody else's data.
     *
     * <p><b>Templates only, and nothing a person has to judge.</b> It rewrites times that are
     * already readable into one canonical form, and fills in a missing start time from a Label that
     * holds one - without touching the Label itself. A time nobody can read is left exactly as it is and listed on Edit Shift Templates,
     * because guessing at it is how a shift ends up on somebody's calendar at the wrong hour.
     *
     * <p>Director-gated like everything else on this page. The button sits inside the preferences
     * form and reaches here with {@code formaction}, so the form's own fields arrive too and are
     * deliberately ignored - this action saves no preferences, and binding them would let a tidy
     * press quietly write a half-filled form.
     */
    @PostMapping("/WebSitePreferences/tidyShiftTimes")
    public String tidyShiftTimes(
            @RequestParam String resort,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(WebSitePreferencesController.class, javaxRequest, "POST", resort,
                                Logger.INFO);

        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        ValidateCredentialsRedirectIfNeeded credentials =
            new ValidateCredentialsRedirectIfNeeded(
                sessionData, javaxRequest, javaxResponse,
                "WebSitePreferences", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null;
        }

        try {
            String loggedInUserId = sessionData.getLoggedInUserId();
            Roster member = patrolData.getMemberByID(loggedInUserId);
            if (member == null || !member.isDirector()) {
                LOG.info("non-director tried to tidy shift times: (" + loggedInUserId + ")");
                response.sendError(403, "You must be a director to do this");
                return null;
            }

            ShiftLabelMigration.ResortOutcome outcome =
                ShiftTimeCleanup.cleanResort(patrolData.getConnection());
            if (!outcome.ok()) {
                LOG.error("shift time cleanup failed for " + resort + ": " + outcome.detail());
            }

            sendBackToPreferences(response, resort, loggedInUserId, "tidied", outcome.detail());
            return null;
        }
        finally {
            patrolData.close();
        }
    }

    /**
     * The same tidy-up over the days already on the calendar, from today forward.
     *
     * <p>The sibling above fixes the templates, which is what stops the problem recurring as a
     * season is built. This one fixes the days that have <b>already</b> been scheduled - and those
     * are what a personal calendar would actually be reading, so a resort whose season is already
     * laid out needs this one as well.
     *
     * <p><b>Nothing before today is touched</b>, which is the whole reason this is safe to put in
     * front of a director. A shift somebody worked in 2019 is a record and stays exactly as it is.
     */
    @PostMapping("/WebSitePreferences/tidyScheduledShifts")
    public String tidyScheduledShifts(
            @RequestParam String resort,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(WebSitePreferencesController.class, javaxRequest, "POST", resort,
                                Logger.INFO);

        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        ValidateCredentialsRedirectIfNeeded credentials =
            new ValidateCredentialsRedirectIfNeeded(
                sessionData, javaxRequest, javaxResponse,
                "WebSitePreferences", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null;
        }

        try {
            String loggedInUserId = sessionData.getLoggedInUserId();
            Roster member = patrolData.getMemberByID(loggedInUserId);
            if (member == null || !member.isDirector()) {
                LOG.info("non-director tried to tidy scheduled shifts: (" + loggedInUserId + ")");
                response.sendError(403, "You must be a director to do this");
                return null;
            }

            //today in the resort's own zone, not the server's - a resort three hours behind must
            //not have tonight's shift treated as past because the box has already rolled over
            java.time.LocalDate today = java.time.LocalDate.now(
                ResortTimeZones.inForce(resort, patrolData.readDirectorSettings()));

            ShiftLabelMigration.ResortOutcome outcome =
                ShiftTimeCleanup.cleanScheduledDays(patrolData.getConnection(), today);
            if (!outcome.ok()) {
                LOG.error("scheduled shift cleanup failed for " + resort + ": " + outcome.detail());
            }

            sendBackToPreferences(response, resort, loggedInUserId, "scheduledTidied",
                                  outcome.detail());
            return null;
        }
        finally {
            patrolData.close();
        }
    }

    /** Back to the page that asked, carrying what happened so it can be shown. */
    private void sendBackToPreferences(HttpServletResponse response, String resort, String userId,
                                       String parameter, String detail) throws IOException {
        response.sendRedirect("../WebSitePreferences?resort=" + resort + "&ID=" + userId
                                + "&" + parameter + "=" + java.net.URLEncoder.encode(
                                    detail, java.nio.charset.StandardCharsets.UTF_8));
    }
}
