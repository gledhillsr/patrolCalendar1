package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.nsponline.calendar.utils.ResortData;
import org.nsponline.calendar.dto.WebSitePreferencesForm;
import org.nsponline.calendar.store.DirectorSettings;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
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
}
