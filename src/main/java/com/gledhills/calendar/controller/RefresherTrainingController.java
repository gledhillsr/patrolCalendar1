package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

/**
 * Spring Boot controller for the Refresher and Training information page.
 * Migrated from the RefresherTraining servlet.
 *
 * Handles GET /RefresherTraining - shows the member's personal online-refresher
 * link plus ongoing training resources.
 */
@Controller
public class RefresherTrainingController {

    @GetMapping("/RefresherTraining")
    public String showRefresherTraining(HttpServletRequest request, HttpServletResponse response,
                                        Model model) throws IOException {
        // 1. Read resort parameter
        String resort = request.getParameter("resort");

        // 2. Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(RefresherTrainingController.class, javaxRequest, "GET", resort, Logger.INFO);

        // 3. Validate resort
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        // 4. Initialize session and patrol data
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        // 5. Validate credentials
        ValidateCredentialsRedirectIfNeeded credentials =
            new ValidateCredentialsRedirectIfNeeded(
                sessionData, javaxRequest, javaxResponse,
                "RefresherTraining", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null; // Redirect already sent
        }

        try {
            // 6. Get logged-in user info (backdoor user has no roster record)
            String patrollerId = sessionData.getLoggedInUserId();
            Roster member = patrolData.getMemberByID(patrollerId);
            String memberName = member != null ? member.getFullName() : patrollerId;

            // 7. Add model attributes
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("memberName", memberName);
            model.addAttribute("refresherUrl",
                               SiteIdentity.HOSTED_CONTENT_BASE
                                 + "/Brighton/training/index.php?id=" + patrollerId);

            // 7a. Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }
            model.addAttribute("logoutLink", "");

            return "refresher-training";

        } finally {
            patrolData.close();
        }
    }
}
