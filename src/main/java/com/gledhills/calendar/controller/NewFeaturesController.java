package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.ResortData;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.ValidateCredentialsRedirectIfNeeded;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

/**
 * What has been added to the calendar lately, written for directors rather than for developers.
 *
 * <p>Director-only, because everything it describes is a director's job. The page itself is static
 * prose - {@code new-features.html} - so adding a feature is an edit to that template and nothing
 * else. Keep the newest at the top; the contents list at the head of the page is hand-written for
 * the same reason, so the order and the wording stay under an author's control.
 */
@Controller
public class NewFeaturesController {

    @GetMapping("/NewFeatures")
    public String show(@RequestParam String resort,
                       @RequestParam(required = false) String ID,
                       HttpServletRequest request, HttpServletResponse response, Model model)
        throws IOException {

        //an unknown resort has no database, and building PatrolData for it dumps a stack trace
        //into the log for what is only a bad URL parameter
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse =
            ServletAdapter.toJavaxResponse(response);

        Logger LOG = new Logger(NewFeaturesController.class, javaxRequest, "GET", resort,
                                Logger.INFO);
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        try {
            ValidateCredentialsRedirectIfNeeded credentials =
                new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                                        "NewFeatures", patrolData, LOG);
            if (credentials.hasInvalidCredentials()) {
                return null;   //redirect already sent
            }

            //authority is the session, never the ID parameter - that is only for links and logging
            String loggedInUserId = sessionData.getLoggedInUserId();
            Roster member = patrolData.getMemberByID(loggedInUserId);
            if (member == null || !member.isDirector()) {
                LOG.error("DENIED NewFeatures: member " + loggedInUserId + " is not a director");
                response.sendError(403, "Directors only");
                return null;
            }

            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", loggedInUserId);

            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }
            return "new-features";
        }
        finally {
            patrolData.close();
        }
    }
}
