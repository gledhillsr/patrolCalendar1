package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.EmailCompositionForm;
import com.gledhills.calendar.dto.EmailFormRequest;
import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.service.EmailService;
import com.gledhills.calendar.service.PatrolMemberFilterService;
import com.gledhills.calendar.service.ShiftAssignmentService;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

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

    private static final String FORM_VIEW = "email-form";
    private static final Comparator<EmailRecipient> BY_LAST_NAME =
        Comparator.comparing(EmailRecipient::getLastName).thenComparing(EmailRecipient::getFirstName);

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

    /**
     * @param filter the classification/commitment/shift-count filters chosen on the calling
     *               page; every field is optional and binds straight from the query string
     */
    @GetMapping("/EmailForm")
    public String showEmailForm(
            @RequestParam String resort,
            @RequestParam(name = "ID") String id,
            @RequestParam(defaultValue = "false") boolean attachmentsTooLarge,
            @ModelAttribute EmailFormRequest filter,
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
            if (attachmentsTooLarge) {
                // bounced back here by handleUploadTooLarge - the POST never got parsed
                model.addAttribute("attachmentError",
                    "Those attachments were too large to upload. The limit is " +
                    asMegabytes(EmailCompositionForm.MAX_ATTACHMENTS_TOTAL_BYTES) + " in total.");
            }
            populateForm(model, resort, id, filter, patrolData, LOG);
            return FORM_VIEW;
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

        String resort = form.getResort();

        // Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(EmailFormController.class, javaxRequest, "POST", resort, Logger.INFO);
        // this form posts the sender as fromPatrollerId, not the "ID" the Logger looks for
        LOG.setId(form.getFromPatrollerId());

        // Validate resort
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

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
            // Attachments are capped as a total, not per file
            if (form.getAttachmentsTotalBytes() > EmailCompositionForm.MAX_ATTACHMENTS_TOTAL_BYTES) {
                bindingResult.reject("error.attachmentsTooLarge", describeAttachmentOverage(form));
            }

            // Sending is disabled in the demo resort; log what would have gone out instead
            if ("Sample".equalsIgnoreCase(resort)) {
                if (!bindingResult.hasErrors()) {
                    LOG.info("Sample resort - email NOT sent. Would have sent:\n" +
                             emailService.describeUnsentBatch(form, patrolData, LOG));
                }
                bindingResult.rejectValue("subject", "error.sample",
                    "Email sending is disabled in the Sample resort");
            }

            if (bindingResult.hasErrors()) {
                // The submitted form and its errors are already in the model; the filters the
                // recipient list was built from are not posted back, so redisplay everybody.
                populateForm(model, resort, form.getFromPatrollerId(),
                             EmailFormRequest.everybody(), patrolData, LOG);
                return FORM_VIEW;
            }

            int queuedCount = emailService.sendEmailsAsync(form, patrolData, LOG);

            addPageChrome(model, resort, form.getFromPatrollerId(), LOG);
            model.addAttribute("totalRecipients", queuedCount);

            return "email-confirmation";

        } finally {
            patrolData.close();
        }
    }

    /**
     * Build the recipient lists and page chrome shared by the initial GET and the
     * redisplay after a validation error.
     */
    private void populateForm(Model model, String resort, String patrollerId,
                              EmailFormRequest filter, PatrolData patrolData, Logger LOG) {
        List<EmailRecipient> recipients = filterService.filterMembers(filter, patrolData);
        if (filter.hasDateRange()) {
            recipients = shiftAssignmentService.filterByMinimumShifts(recipients, filter, patrolData);
        }

        List<EmailRecipient> validRecipients = new ArrayList<>();
        List<EmailRecipient> invalidRecipients = new ArrayList<>();
        for (EmailRecipient recipient : recipients) {
            (recipient.hasValidEmail() ? validRecipients : invalidRecipients).add(recipient);
        }
        validRecipients.sort(BY_LAST_NAME);
        invalidRecipients.sort(BY_LAST_NAME);

        Roster fromMember = patrolData.getMemberByID(patrollerId);

        addPageChrome(model, resort, patrollerId, LOG);
        model.addAttribute("fromName", (fromMember != null) ? fromMember.getFullName() : "Unknown");
        model.addAttribute("fromEmail", (fromMember != null) ? fromMember.getEmailAddress() : "");
        model.addAttribute("validRecipients", validRecipients);
        model.addAttribute("invalidRecipients", invalidRecipients);
        model.addAttribute("attachmentLimit", asMegabytes(EmailCompositionForm.MAX_ATTACHMENTS_TOTAL_BYTES));

        // On the initial GET, start with every valid recipient selected
        if (!model.containsAttribute("compositionForm")) {
            EmailCompositionForm compositionForm = new EmailCompositionForm();
            compositionForm.setResort(resort);
            compositionForm.setFromPatrollerId(patrollerId);
            compositionForm.setPatrollerIds(validRecipients.stream().map(EmailRecipient::getId).toList());
            model.addAttribute("compositionForm", compositionForm);
        }
    }

    /** "Attachments total 3.4 MB - the limit is 2 MB. Attached: notes.pdf (3.4 MB)" */
    private String describeAttachmentOverage(EmailCompositionForm form) {
        StringBuilder message = new StringBuilder();
        message.append("Attachments total ").append(asMegabytes(form.getAttachmentsTotalBytes()))
               .append(" - the limit is ")
               .append(asMegabytes(EmailCompositionForm.MAX_ATTACHMENTS_TOTAL_BYTES))
               .append(". Remove or shrink a file and attach them again. Attached: ");

        List<String> described = new ArrayList<>();
        for (MultipartFile file : form.getAttachments()) {
            described.add(StringUtils.getFilename(file.getOriginalFilename())
                          + " (" + asMegabytes(file.getSize()) + ")");
        }
        return message.append(String.join(", ", described)).toString();
    }

    private String asMegabytes(long bytes) {
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Backstop for uploads too large to even buffer (see spring.servlet.multipart.* limits).
     * The multipart body is unreadable at this point, so send the user back to a clean form
     * rather than trying to redisplay what they typed.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleUploadTooLarge(HttpServletRequest request) {
        // resort/fromPatrollerId ride in the form action's query string, so they survive
        // even though the body was rejected
        return "redirect:/EmailForm?resort=" + nullSafe(request.getParameter("resort"))
             + "&ID=" + nullSafe(request.getParameter("fromPatrollerId"))
             + "&attachmentsTooLarge=true";
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    /** Everything the shared header fragment needs. */
    private void addPageChrome(Model model, String resort, String patrollerId, Logger LOG) {
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("patrollerId", patrollerId);

        ResortData resortData = PatrolData.getResortInfo(resort);
        if (resortData != null) {
            model.addAttribute("resortUrl", resortData.getResortURL());
            model.addAttribute("resortImage", resortData.getResortImage());
            model.addAttribute("resortImgHeight", resortData.getImageHeight());
        }
    }
}
