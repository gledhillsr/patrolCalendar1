package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.nsponline.calendar.dto.*;
import org.nsponline.calendar.service.EmailService;
import org.nsponline.calendar.service.PatrolMemberFilterService;
import org.nsponline.calendar.service.ShiftAssignmentService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Spring Boot controller for email form.
 * Migrated from InnerEmailForm servlet.
 *
 * Handles GET /EmailForm - displays email composition form with filtered recipients
 * Handles POST /EmailForm - sends emails asynchronously to selected recipients
 */
@Controller
public class EmailFormController {

    private final PatrolMemberFilterService filterService;
    private final ShiftAssignmentService shiftAssignmentService;
    private final EmailService emailService;

    public EmailFormController(PatrolMemberFilterService filterService,
                              ShiftAssignmentService shiftAssignmentService,
                              EmailService emailService) {
        this.filterService = filterService;
        this.shiftAssignmentService = shiftAssignmentService;
        this.emailService = emailService;
    }

    @GetMapping("/EmailForm")
    public String showEmailForm(
            @RequestParam String resort,
            @RequestParam(name = "ID") String id,
            @RequestParam(required = false) Boolean EveryBody,
            @RequestParam(required = false) Boolean SubList,
            @RequestParam(required = false) Boolean BAS,
            @RequestParam(required = false) Boolean INA,
            @RequestParam(required = false) Boolean SR,
            @RequestParam(required = false) Boolean SRA,
            @RequestParam(required = false) Boolean ALM,
            @RequestParam(required = false) Boolean PRO,
            @RequestParam(required = false) Boolean AUX,
            @RequestParam(required = false) Boolean TRA,
            @RequestParam(required = false) Boolean CAN,
            @RequestParam(required = false) Boolean OTH,
            @RequestParam(required = false) Boolean FullTime,
            @RequestParam(required = false) Boolean PartTime,
            @RequestParam(required = false) Boolean Inactive,
            @RequestParam(required = false) Boolean ALL,
            @RequestParam(required = false) Boolean ListDirector,
            @RequestParam(required = false) Boolean OEC,
            @RequestParam(required = false) Boolean CPR,
            @RequestParam(required = false) Boolean Ski,
            @RequestParam(required = false) Boolean Toboggan,
            @RequestParam(required = false) Boolean DAY_CNT,
            @RequestParam(required = false) Boolean SWING_CNT,
            @RequestParam(required = false) Boolean NIGHT_CNT,
            @RequestParam(required = false) Boolean TRAINING_CNT,
            @RequestParam(required = false) Boolean OTHER_CNT,
            @RequestParam(required = false) Boolean HOLIDAY_CNT,
            @RequestParam(required = false) Integer StartDay,
            @RequestParam(required = false) Integer StartMonth,
            @RequestParam(required = false) Integer StartYear,
            @RequestParam(required = false) Integer EndDay,
            @RequestParam(required = false) Integer EndMonth,
            @RequestParam(required = false) Integer EndYear,
            @RequestParam(required = false) Integer MinDays,
            @RequestParam(required = false) Boolean MIN_DAYS,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        // Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(EmailFormController.class, javaxRequest, "GET", resort, Logger.INFO);

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
                "EmailForm", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null; // Redirect already sent
        }

        try {
            // Build request DTO
            EmailFormRequest emailRequest = new EmailFormRequest(
                resort, id, EveryBody, SubList,
                BAS, INA, SR, SRA, ALM, PRO, AUX, TRA, CAN, OTH,
                FullTime, PartTime, Inactive, ALL, ListDirector,
                OEC, CPR, Ski, Toboggan,
                DAY_CNT, SWING_CNT, NIGHT_CNT, TRAINING_CNT, OTHER_CNT, HOLIDAY_CNT,
                StartDay, StartMonth, StartYear, EndDay, EndMonth, EndYear,
                MinDays, MIN_DAYS
            );

            // Filter members
            List<EmailRecipient> recipients = filterService.filterMembers(emailRequest, patrolData);

            // Enrich with shift counts if date range specified
            if (emailRequest.getStartYear() != null) {
                recipients = shiftAssignmentService.enrichWithShiftCounts(recipients, emailRequest, patrolData);
            }

            // Split into valid and invalid email lists
            List<EmailRecipient> validRecipients = new ArrayList<>();
            List<EmailRecipient> invalidRecipients = new ArrayList<>();
            for (EmailRecipient recipient : recipients) {
                if (recipient.isHasValidEmail()) {
                    validRecipients.add(recipient);
                } else {
                    invalidRecipients.add(recipient);
                }
            }

            // Sort by last name
            Comparator<EmailRecipient> byLastName = Comparator.comparing(EmailRecipient::getLastName)
                .thenComparing(EmailRecipient::getFirstName);
            validRecipients.sort(byLastName);
            invalidRecipients.sort(byLastName);

            // Get from member info
            Roster fromMember = patrolData.getMemberByID(id);
            String fromName = (fromMember != null) ? fromMember.getFullName() : "Unknown";
            String fromEmail = (fromMember != null) ? fromMember.getEmailAddress() : "";

            // Add all to model
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", id);
            model.addAttribute("fromName", fromName);
            model.addAttribute("fromEmail", fromEmail);
            model.addAttribute("validRecipients", validRecipients);
            model.addAttribute("invalidRecipients", invalidRecipients);
            model.addAttribute("validCount", validRecipients.size());
            model.addAttribute("invalidCount", invalidRecipients.size());

            // Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            // Add filter parameters for hidden fields
            model.addAttribute("filterRequest", emailRequest);

            // Add empty form object for POST binding
            if (!model.containsAttribute("compositionForm")) {
                EmailCompositionForm form = new EmailCompositionForm();
                form.setResort(resort);
                form.setFromPatrollerId(id);
                form.setOriginalPatrollerCount(validRecipients.size());
                model.addAttribute("compositionForm", form);
            }

            return "email-form";

        } finally {
            patrolData.close();
        }
    }

    @PostMapping("/EmailForm")
    public String sendEmails(
            @Valid @ModelAttribute("compositionForm") EmailCompositionForm form,
            BindingResult bindingResult,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        // Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger LOG = new Logger(EmailFormController.class, javaxRequest, "POST", form.getResort(), Logger.INFO);

        // Validate resort
        if (!PatrolData.isValidResort(form.getResort())) {
            response.sendError(400, "Resort not found: " + form.getResort());
            return null;
        }

        // Check Sample resort (disabled)
        if ("Sample".equalsIgnoreCase(form.getResort())) {
            bindingResult.rejectValue("subject", "error.sample",
                "Email sending is disabled in the Sample resort");
        }

        // If validation errors, return to form
        if (bindingResult.hasErrors()) {
            return showEmailFormWithErrors(form, bindingResult, request, response, model, LOG);
        }

        // Initialize patrol data
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(form.getResort(), sessionData, LOG);

        try {
            // Send emails async
            EmailSendResult result = emailService.sendEmailsAsync(form, patrolData, LOG);

            // Add result to model
            model.addAttribute("trackingId", result.getTrackingId());
            model.addAttribute("totalRecipients", result.getTotalRecipients());
            model.addAttribute("patrollerId", form.getFromPatrollerId());
            model.addAttribute("resort", form.getResort());
            model.addAttribute("resortFullName", PatrolData.getResortFullName(form.getResort(), LOG));

            return "email-confirmation";

        } finally {
            patrolData.close();
        }
    }

    /**
     * Helper method to reconstruct GET request when validation errors occur.
     */
    private String showEmailFormWithErrors(EmailCompositionForm form, BindingResult bindingResult,
                                          HttpServletRequest request, HttpServletResponse response,
                                          Model model, Logger LOG) throws IOException {
        // Add validation errors to model
        model.addAttribute("org.springframework.validation.BindingResult.compositionForm", bindingResult);
        model.addAttribute("compositionForm", form);

        // Initialize patrol data to rebuild recipient lists
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(form.getResort(), sessionData, LOG);

        try {
            // Rebuild empty request to show all members (we don't preserve filter state on error)
            EmailFormRequest emailRequest = new EmailFormRequest(
                form.getResort(), form.getFromPatrollerId(),
                true, false, // EveryBody=true
                false, false, false, false, false, false, false, false, false, false, // classifications
                false, false, false, false, false, // commitment flags
                false, false, false, false, // instructor flags
                false, false, false, false, false, false, // shift type flags
                null, null, null, null, null, null, null, false // date range and MinDays
            );

            List<EmailRecipient> recipients = filterService.filterMembers(emailRequest, patrolData);

            // Split into valid and invalid
            List<EmailRecipient> validRecipients = new ArrayList<>();
            List<EmailRecipient> invalidRecipients = new ArrayList<>();
            for (EmailRecipient recipient : recipients) {
                if (recipient.isHasValidEmail()) {
                    validRecipients.add(recipient);
                } else {
                    invalidRecipients.add(recipient);
                }
            }

            // Sort by last name
            Comparator<EmailRecipient> byLastName = Comparator.comparing(EmailRecipient::getLastName)
                .thenComparing(EmailRecipient::getFirstName);
            validRecipients.sort(byLastName);
            invalidRecipients.sort(byLastName);

            // Get from member info
            Roster fromMember = patrolData.getMemberByID(form.getFromPatrollerId());
            String fromName = (fromMember != null) ? fromMember.getFullName() : "Unknown";
            String fromEmail = (fromMember != null) ? fromMember.getEmailAddress() : "";

            // Add to model
            model.addAttribute("resort", form.getResort());
            model.addAttribute("resortFullName", PatrolData.getResortFullName(form.getResort(), LOG));
            model.addAttribute("patrollerId", form.getFromPatrollerId());
            model.addAttribute("fromName", fromName);
            model.addAttribute("fromEmail", fromEmail);
            model.addAttribute("validRecipients", validRecipients);
            model.addAttribute("invalidRecipients", invalidRecipients);
            model.addAttribute("validCount", validRecipients.size());
            model.addAttribute("invalidCount", invalidRecipients.size());
            model.addAttribute("filterRequest", emailRequest);

            return "email-form";

        } finally {
            patrolData.close();
        }
    }
}
