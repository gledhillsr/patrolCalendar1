package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.EmailFormRequest;
import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.dto.TextCompositionForm;
import com.gledhills.calendar.service.PatrolMemberFilterService;
import com.gledhills.calendar.service.ShiftAssignmentService;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.ResortData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.ValidateCredentialsRedirectIfNeeded;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
 * "Text Message THESE patrollers", reached from the same button row as the e-mail one on
 * ListPatrollers.
 *
 * <h2>Nothing is sent yet</h2>
 * <p>There is no SMS provider wired to this application: sending needs an account, a number, and
 * in the US an A2P 10DLC brand and campaign registration before an application-sent message is
 * deliverable at all. This page composes, picks the audience and shows exactly what would go to
 * whom - and then says so instead of sending. {@link #wouldSend} is the one place that becomes a
 * real call to a TextService.
 *
 * <h2>Who can see it</h2>
 * <p>The site owner, on their own resort - Brighton and 192443, the same pair that gates Site Wide
 * Configuration, because this is being built rather than released. The gate is on the GET
 * <b>and</b> the POST, and reads the <b>session</b>, never the {@code ID} parameter: a page that
 * decides who you are from a query string decides nothing at all.
 *
 * <h2>Who it would go to</h2>
 * <p>The audience arrives as the same filter query string ListPatrollers builds for EmailForm -
 * one builder, so the two buttons cannot select different people. Recipients are then split by
 * whether they have a number that can be dialled, and the page <b>says how many were dropped</b>
 * rather than quietly showing a shorter list.
 *
 * <p>Like the site-wide director mailing, the POST <b>re-derives</b> the audience rather than
 * trusting what came back; what the browser sends may only narrow it. Numbers arriving in a
 * request and being messaged as-is is the definition of an open relay.
 */
@Controller
public class TextFormController {

    private static final String FORM_VIEW = "text-form";
    private static final Comparator<EmailRecipient> BY_LAST_NAME =
        Comparator.comparing(EmailRecipient::getLastName).thenComparing(EmailRecipient::getFirstName);

    private final PatrolMemberFilterService filterService;
    private final ShiftAssignmentService shiftAssignmentService;

    public TextFormController(PatrolMemberFilterService filterService,
                              ShiftAssignmentService shiftAssignmentService) {
        this.filterService = filterService;
        this.shiftAssignmentService = shiftAssignmentService;
    }

    @GetMapping("/TextForm")
    public String showTextForm(@RequestParam String resort,
                               @RequestParam(name = "ID") String id,
                               @ModelAttribute EmailFormRequest filter,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               Model model) throws IOException {

        Session session = openSession(resort, request, response, "TextForm");
        if (session == null) {
            return null;
        }
        try {
            if (refuseNonOwner(resort, session.sessionData(), response, session.log())) {
                return null;
            }
            populateForm(model, resort, id, filter, session.patrolData(), session.log());
            return FORM_VIEW;
        }
        finally {
            session.patrolData().close();
        }
    }

    @PostMapping("/TextForm")
    public String sendTexts(@Valid @ModelAttribute("compositionForm") TextCompositionForm form,
                            BindingResult binding,
                            @RequestParam String resort,
                            @RequestParam(name = "ID") String id,
                            HttpServletRequest request,
                            HttpServletResponse response,
                            Model model) throws IOException {

        Session session = openSession(resort, request, response, "TextForm");
        if (session == null) {
            return null;
        }
        try {
            if (refuseNonOwner(resort, session.sessionData(), response, session.log())) {
                return null;
            }

            //The filters behind the audience are not posted back, so a rejected form has to rebuild
            //it from "everybody" - the same reason the site-wide mailing carries its audience
            //through the error path. Without this the page would redisplay a different list than
            //the one the owner was looking at.
            if (binding.hasErrors()) {
                //name the characters rather than leaving a director to hunt for the one curly
                //quote their phone inserted in 160 characters of their own writing
                String offending = TextCompositionForm.disallowedCharactersIn(form.getMessage());
                if (!offending.isEmpty()) {
                    model.addAttribute("badCharacters", offending);
                }
                populateForm(model, resort, id, EmailFormRequest.everybody(),
                             session.patrolData(), session.log());
                return FORM_VIEW;
            }

            List<EmailRecipient> chosen =
                resolveChosen(form, EmailFormRequest.everybody(), session.patrolData());
            int queued = wouldSend(form, chosen, session.log());

            populateForm(model, resort, id, EmailFormRequest.everybody(),
                         session.patrolData(), session.log());
            model.addAttribute("notSentYet", true);
            model.addAttribute("wouldSendCount", queued);
            model.addAttribute("wouldSendTo", chosen);
            model.addAttribute("sentMessage", form.getMessage());
            return FORM_VIEW;
        }
        finally {
            session.patrolData().close();
        }
    }

    /**
     * Where the send will go when there is something to send with.
     *
     * <p>Today it writes the batch to the log and returns how many it would have been. Everything
     * a real send needs is already decided by this point: the recipients are resolved, every one of
     * them has a dialable number, and the message has been validated. Replacing this body with a
     * call to a TextService is the whole of turning the page on.
     */
    private int wouldSend(TextCompositionForm form, List<EmailRecipient> recipients, Logger LOG) {
        LOG.warn("TextForm: would send " + recipients.size() + " text(s), " +
                 form.getMessage().length() + " characters: " + form.getMessage());
        for (EmailRecipient recipient : recipients) {
            LOG.info("TextForm: would text " + recipient.getFullNameLastFirst()
                       + " at " + recipient.getMobileNumber());
        }
        return recipients.size();
    }

    /**
     * The audience, re-derived here and then narrowed to what the browser selected.
     *
     * <p>An id that is not in the derived audience matches nothing and is dropped, so nothing
     * posted can add a recipient - and somebody whose number went missing between the GET and the
     * POST falls out on their own.
     */
    private List<EmailRecipient> resolveChosen(TextCompositionForm form, EmailFormRequest filter,
                                               PatrolData patrolData) {
        List<String> selected = form.getPatrollerIds() == null ? List.of() : form.getPatrollerIds();
        List<EmailRecipient> chosen = new ArrayList<>();
        for (EmailRecipient recipient : filterService.filterMembers(filter, patrolData)) {
            if (recipient.hasValidMobile() && selected.contains(recipient.getSelectionKey())) {
                chosen.add(recipient);
            }
        }
        chosen.sort(BY_LAST_NAME);
        return chosen;
    }

    private void populateForm(Model model, String resort, String patrollerId,
                              EmailFormRequest filter, PatrolData patrolData, Logger LOG) {
        List<EmailRecipient> recipients = filterService.filterMembers(filter, patrolData);
        if (filter.hasDateRange()) {
            recipients = shiftAssignmentService.filterByShiftLimit(recipients, filter, patrolData);
        }

        List<EmailRecipient> textable = new ArrayList<>();
        List<EmailRecipient> noNumber = new ArrayList<>();
        for (EmailRecipient recipient : recipients) {
            (recipient.hasValidMobile() ? textable : noNumber).add(recipient);
        }
        textable.sort(BY_LAST_NAME);
        noNumber.sort(BY_LAST_NAME);

        Roster fromMember = patrolData.getMemberByID(patrollerId);

        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("patrollerId", patrollerId);
        ResortData resortData = PatrolData.getResortInfo(resort);
        if (resortData != null) {
            model.addAttribute("resortUrl", resortData.getResortURL());
            model.addAttribute("resortImage", resortData.getResortImage());
            model.addAttribute("resortImgHeight", resortData.getImageHeight());
        }

        model.addAttribute("fromName", fromMember != null ? fromMember.getFullName() : "Unknown");
        model.addAttribute("textable", textable);
        model.addAttribute("noNumber", noNumber);
        model.addAttribute("listedCount", recipients.size());
        model.addAttribute("maxCharacters", TextCompositionForm.MAX_CHARACTERS);
        model.addAttribute("segmentCharacters", TextCompositionForm.SEGMENT_CHARACTERS);

        if (!model.containsAttribute("compositionForm")) {
            TextCompositionForm compositionForm = new TextCompositionForm();
            compositionForm.setResort(resort);
            compositionForm.setFromPatrollerId(patrollerId);
            compositionForm.setPatrollerIds(
                textable.stream().map(EmailRecipient::getSelectionKey).toList());
            model.addAttribute("compositionForm", compositionForm);
        }
    }

    private boolean refuseNonOwner(String resort, SessionData sessionData,
                                   HttpServletResponse response, Logger LOG) throws IOException {
        String loggedInUserId = sessionData.getLoggedInUserId();
        if (SiteWideConfigurationController.isSiteOwner(resort, loggedInUserId)) {
            return false;
        }
        LOG.error("DENIED TextForm: " + loggedInUserId + " at " + resort);
        response.sendError(403, "Not authorized");
        return true;
    }

    /** Resort check, session and credentials, which both handlers need before anything else. */
    private Session openSession(String resort, HttpServletRequest request,
                                HttpServletResponse response, String page) throws IOException {
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger log = new Logger(TextFormController.class, javaxRequest, request.getMethod(),
                                resort, Logger.INFO);

        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        SessionData sessionData = new SessionData(javaxRequest, null, log);
        PatrolData patrolData = new PatrolData(resort, sessionData, log);

        ValidateCredentialsRedirectIfNeeded credentials =
            new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                                    page, patrolData, log);
        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null;
        }
        return new Session(sessionData, patrolData, log);
    }

    private record Session(SessionData sessionData, PatrolData patrolData, Logger log) {
    }
}
