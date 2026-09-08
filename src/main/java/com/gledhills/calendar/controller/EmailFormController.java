package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.EmailCompositionForm;
import com.gledhills.calendar.dto.EmailFormRequest;
import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.service.EmailService;
import com.gledhills.calendar.service.PatrolMemberFilterService;
import com.gledhills.calendar.service.ShiftAssignmentService;
import com.gledhills.calendar.service.SiteDirectorsService;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * The one audience that is not this resort's own roster: every director at every resort,
     * for the site owner to announce a new feature or a problem. Reached only from
     * SiteWideConfiguration, and gated the same way that page is.
     */
    public static final String SITE_DIRECTORS = "siteDirectors";

    private final PatrolMemberFilterService filterService;
    private final ShiftAssignmentService shiftAssignmentService;
    private final EmailService emailService;
    private final SiteDirectorsService siteDirectorsService;

    public EmailFormController(PatrolMemberFilterService filterService,
                              ShiftAssignmentService shiftAssignmentService,
                              EmailService emailService,
                              SiteDirectorsService siteDirectorsService) {
        this.filterService = filterService;
        this.shiftAssignmentService = shiftAssignmentService;
        this.emailService = emailService;
        this.siteDirectorsService = siteDirectorsService;
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
            @RequestParam(defaultValue = "") String audience,
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
            if (rejectUnknownAudience(audience, response, LOG)) {
                return null;
            }
            if (isSiteDirectors(audience) && refuseNonOwner(resort, sessionData, response, LOG)) {
                return null;
            }
            if (attachmentsTooLarge) {
                // bounced back here by UploadTooLargeAdvice - the POST never got parsed
                model.addAttribute("attachmentError",
                    "Those attachments were too large to upload. The limit is " +
                    asMegabytes(EmailCompositionForm.MAX_ATTACHMENTS_TOTAL_BYTES) + " in total.");
            }
            populateForm(model, resort, id, filter, audience, sessionData, patrolData, LOG);
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
            if (rejectUnknownAudience(form.getAudience(), response, LOG)) {
                return null;
            }
            if (isSiteDirectors(form.getAudience())
                    && refuseNonOwner(resort, sessionData, response, LOG)) {
                return null;
            }

            // Attachments are capped as a total, not per file
            if (form.getAttachmentsTotalBytes() > EmailCompositionForm.MAX_ATTACHMENTS_TOTAL_BYTES) {
                bindingResult.reject("error.attachmentsTooLarge", describeAttachmentOverage(form));
            }

            // Sending is disabled in the demo resort; log what would have gone out instead
            if (PatrolData.isDemoResort(resort)) {
                if (!bindingResult.hasErrors()) {
                    LOG.info("demo resort " + resort + " - email NOT sent. Would have sent:\n" +
                             emailService.describeUnsentBatch(form, patrolData, LOG));
                }
                bindingResult.rejectValue("subject", "error.sample",
                    "Email sending is disabled in the demo resort");
            }

            if (bindingResult.hasErrors()) {
                // The submitted form and its errors are already in the model; the filters the
                // recipient list was built from are not posted back, so redisplay everybody.
                // The audience is posted back, and has to be honoured: rebuilding a site-wide
                // director mailing as "everybody" would quietly turn it into Brighton's roster.
                populateForm(model, resort, form.getFromPatrollerId(),
                             EmailFormRequest.everybody(), form.getAudience(), sessionData,
                             patrolData, LOG);
                return FORM_VIEW;
            }

            int queuedCount = isSiteDirectors(form.getAudience())
                ? emailService.sendEmailsAsync(form, patrolData, LOG,
                                               selectedDirectors(form, sessionData, LOG))
                : emailService.sendEmailsAsync(form, patrolData, LOG);

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
                              EmailFormRequest filter, String audience, SessionData sessionData,
                              PatrolData patrolData, Logger LOG) {
        List<EmailRecipient> recipients;
        if (isSiteDirectors(audience)) {
            recipients = siteDirectorsService.allDirectors(sessionData, LOG);
        }
        else {
            recipients = filterService.filterMembers(filter, patrolData);
            if (filter.hasDateRange()) {
                recipients = shiftAssignmentService.filterByShiftLimit(recipients, filter, patrolData);
            }
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
        model.addAttribute("audience", isSiteDirectors(audience) ? SITE_DIRECTORS : "");
        model.addAttribute("siteDirectors", isSiteDirectors(audience));

        // On the initial GET, start with every valid recipient selected
        if (!model.containsAttribute("compositionForm")) {
            EmailCompositionForm compositionForm = new EmailCompositionForm();
            compositionForm.setResort(resort);
            compositionForm.setFromPatrollerId(patrollerId);
            // the selection key, not the bare id: it is "Resort:id" once a list spans resorts
            compositionForm.setPatrollerIds(
                validRecipients.stream().map(EmailRecipient::getSelectionKey).toList());
            model.addAttribute("compositionForm", compositionForm);
        }
    }

    private boolean isSiteDirectors(String audience) {
        return SITE_DIRECTORS.equals(audience);
    }

    /**
     * Refuse an audience this controller does not recognise, instead of quietly treating it as
     * the ordinary one.
     *
     * A blank audience is the normal case - every other page that reaches EmailForm sends none.
     * But a value that is present and <em>not</em> understood means the request is not what the
     * page thought it was, and the fallback would be to build the send from this resort's whole
     * roster. That is how one duplicated parameter turned a mailing to 232 directors into a
     * mailing to Brighton: the audience arrived as "siteDirectors,siteDirectors", missed the
     * equality check, and the wrong list was assembled in silence. A 400 is the honest answer,
     * and it is visible the first time it happens rather than the first time somebody notices
     * the wrong people got an email.
     *
     * @return true when the response has been sent and the caller should stop
     */
    private boolean rejectUnknownAudience(String audience, HttpServletResponse response, Logger LOG)
            throws IOException {
        if (audience == null || audience.isEmpty() || isSiteDirectors(audience)) {
            return false;
        }
        LOG.error("EmailForm: unrecognised audience [" + audience + "] - refusing rather than "
                    + "falling back to this resort's roster");
        response.sendError(400, "Unrecognised recipient list");
        return true;
    }

    /**
     * Send a 403 unless this is the site owner. Same gate as SiteWideConfiguration, and for the
     * same reason: the director list of all 47 other resorts is nobody else's to see or mail.
     *
     * The check is against the <em>session</em>, never the ID parameter - that one is typed into
     * the URL by whoever made the request.
     *
     * @return true when the response has been sent and the caller should stop
     */
    private boolean refuseNonOwner(String resort, SessionData sessionData,
                                   HttpServletResponse response, Logger LOG) throws IOException {
        String loggedInUserId = sessionData.getLoggedInUserId();
        if (SiteWideConfigurationController.isSiteOwner(resort, loggedInUserId)) {
            return false;
        }
        LOG.error("DENIED site wide director email: " + loggedInUserId + " at " + resort);
        response.sendError(403, "Not authorized");
        return true;
    }

    /**
     * The directors the owner actually ticked, resolved against a freshly read list.
     *
     * The list is rebuilt here rather than trusted from the request. What comes back from the
     * browser is only allowed to <em>narrow</em> it: an id that is not a director's, or belongs to
     * a resort that no longer has one, matches nothing and is dropped. Nothing typed into the form
     * can add an address to the send.
     */
    private List<EmailRecipient> selectedDirectors(EmailCompositionForm form,
                                                   SessionData sessionData, Logger LOG) {
        Map<String, EmailRecipient> byKey = new LinkedHashMap<>();
        for (EmailRecipient director : siteDirectorsService.allDirectors(sessionData, LOG)) {
            if (director.hasValidEmail()) {
                byKey.put(director.getSelectionKey(), director);
            }
        }

        List<EmailRecipient> chosen = new ArrayList<>();
        List<String> selected = form.getPatrollerIds();
        if (selected != null) {
            for (String key : selected) {
                EmailRecipient director = byKey.get(key);
                if (director != null) {
                    chosen.add(director);
                }
            }
        }
        LOG.info("site wide director email: " + chosen.size() + " of "
                   + (selected == null ? 0 : selected.size()) + " selected resolved");
        return chosen;
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
