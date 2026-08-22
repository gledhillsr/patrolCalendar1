package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.gledhills.calendar.store.DirectorSettings;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot controller for member list.
 * Migrated from MemberList servlet.
 *
 * Handles GET /MemberList - displays all active patrol members
 */
@Controller
public class MemberListController {

    /**
     * Classifications this tab lists. Other (OTH) and the legacy Inactive (INA) are left out
     * entirely. Pro (PRO) and Alumni (ALM) have a checkbox each: Pro starts ticked, so the default
     * view is unchanged from before the boxes existed, and Alumni starts unticked.
     *
     * The listing loop and the "email THESE patrollers" URL are both built from this one list, so
     * they cannot drift apart: the page and the email it offers have to hold the same members.
     */
    private static final List<String> LISTED_CLASSIFICATIONS =
        List.of("BAS", "SR", "SRA", "AUX", "TRA", "CAN");

    /** Opt-in classifications, each behind its own checkbox on the page. */
    private static final String PRO = "PRO";
    private static final String ALUMNI = "ALM";

    /**
     * Everything the roster recognises, for the "Show FULL roster" toggle - the trimmed list plus
     * Alumni, "Other" and the legacy Inactive code. Matches Roster.okToDisplay's vocabulary, so the
     * email URL built from it asks EmailForm for exactly what the page drew.
     *
     * Note a member whose classification or commitment holds something outside these values is
     * shown by neither list's email: okToDisplay rejects them whatever the URL asks for. That is
     * pre-existing behaviour of okToDisplay, not something this toggle introduces.
     */
    private static final List<String> ALL_CLASSIFICATIONS =
        List.of("BAS", "INA", "SR", "SRA", "ALM", "PRO", "AUX", "TRA", "CAN", "OTH");

    /** Query parameter that flips the tab between the trimmed list and everybody. */
    public static final String FULL_ROSTER = "fullRoster";

    /**
     * Model class for member information
     */
    public static class MemberInfo {
        private String name;
        private String homePhone;
        private String workPhone;
        private String cellPhone;
        private String email;
        private String classification;
        private String commitment;

        public MemberInfo(String name, String homePhone, String workPhone,
                         String cellPhone, String email,
                         String classification, String commitment) {
            this.name = name;
            this.homePhone = homePhone;
            this.workPhone = workPhone;
            this.cellPhone = cellPhone;
            this.email = email;
            this.classification = classification;
            this.commitment = commitment;
        }

        // Getters
        public String getName() { return name; }
        public String getHomePhone() { return homePhone; }
        public String getWorkPhone() { return workPhone; }
        public String getCellPhone() { return cellPhone; }
        public String getEmail() { return email; }
        public String getClassification() { return classification; }
        public String getCommitment() { return commitment; }
    }

    /**
     * Clean field value by removing null, empty, or &nbsp; values
     */
    private String cleanField(String value) {
        if (value == null || value.trim().isEmpty() || "&nbsp;".equals(value.trim())) {
            return "";
        }
        return value.trim();
    }

    @GetMapping("/MemberList")
    public String showMemberList(HttpServletRequest request, HttpServletResponse response,
                                 Model model) throws IOException {
        // 1. Read resort parameter
        String resort = request.getParameter("resort");

        // 2. Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(MemberListController.class, javaxRequest, "GET", resort, Logger.INFO);

        // 3. Validate resort
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        // 4. Initialize session and patrol data
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        try {
            // 5. Validate credentials
            ValidateCredentialsRedirectIfNeeded credentials =
                new ValidateCredentialsRedirectIfNeeded(
                    sessionData, javaxRequest, javaxResponse,
                    "MemberList", patrolData, LOG);

            if (credentials.hasInvalidCredentials()) {
                return null; // Redirect already sent
            }

            // 6. Get logged-in user info
            String patrollerId = sessionData.getLoggedInUserId();

            // Anything but a literal "true" is the trimmed list, so a mangled parameter shows the
            // conservative view rather than everybody's details.
            boolean fullRoster = "true".equals(request.getParameter(FULL_ROSTER));
            // An unticked checkbox posts nothing, so "pro absent" is ambiguous: it means either
            // "unticked" or "this request never went through the form". The hidden "filters" field
            // tells them apart - present, the boxes are authoritative; absent, Pro starts ticked,
            // which is how this tab has always listed Pros.
            boolean cameFromTheFilters = request.getParameter("filters") != null;
            boolean includePro = cameFromTheFilters
                                   ? "true".equals(request.getParameter("pro")) : true;
            boolean includeAlumni = "true".equals(request.getParameter("alumni"));

            // One list drives both the rows below and the EmailForm URL, so the page and the
            // people its email button offers cannot disagree.
            List<String> listedClassifications = new ArrayList<>(LISTED_CLASSIFICATIONS);
            if (includePro) {
                listedClassifications.add(PRO);
            }
            if (includeAlumni) {
                listedClassifications.add(ALUMNI);
            }
            if (fullRoster) {
                listedClassifications = ALL_CLASSIFICATIONS;
            }

            // 7. Check director status and emailAll permission
            DirectorSettings ds = patrolData.readDirectorSettings();
            Roster editor = patrolData.getMemberByID(patrollerId);
            boolean isDirector = editor != null && editor.isDirector();
            boolean showEmailButton = isDirector || (ds != null && ds.getEmailAll());

            // 8. Load active members and build email list in single pass
            List<MemberInfo> members = new ArrayList<>();
            StringBuilder emailList = new StringBuilder();
            ResultSet rosterResults = patrolData.resetRoster();
            Roster member = patrolData.nextMember("&nbsp;", rosterResults);

            while (member != null) {
                // Trimmed: active members (commitment != "0") in a listed classification.
                // Full: everybody, which is what the toggle exists to show.
                if (fullRoster
                        || (!member.getCommitment().equals("0")
                              && listedClassifications.contains(member.getClassification()))) {
                    // Create MemberInfo with contact details
                    String name = member.getFullName_lastNameFirst();
                    String homePhone = cleanField(member.getHomePhone());
                    String workPhone = cleanField(member.getWorkPhone());
                    String cellPhone = cleanField(member.getCellPhone());
                    String email = cleanField(member.getEmailAddress());

                    members.add(new MemberInfo(name, homePhone, workPhone, cellPhone, email,
                                               cleanField(member.getClassification()),
                                               member.getCommitmentLabel()));

                    // Build email list for EmailForm button
                    if (StaticUtils.isValidEmailAddress(email)) {
                        if (emailList.length() > 0) {
                            emailList.append(",");
                        }
                        emailList.append(email);
                    }
                }

                member = patrolData.nextMember("&nbsp;", rosterResults);
            }

            // 9. Add model attributes
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("members", members);
            model.addAttribute("memberCount", members.size());
            model.addAttribute("showEmailButton", showEmailButton);

            // 10. Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }
            model.addAttribute("logoutLink", "");

            // 11. Build the EmailForm URL. It has to ask EmailForm for exactly the members listed
            // above, or "email THESE patrollers" mails people the page never showed. Both halves
            // come from the same list the loop used, so the two cannot drift:
            //   - trimmed: LISTED_CLASSIFICATIONS, and no Inactive=1 since commitment "0" is skipped
            //   - full:    ALL_CLASSIFICATIONS plus Inactive=1, matching "everybody"
            //   - ALL=1 either way, because the loop applies no director/instructor filter
            StringBuilder emailFormUrl =
                new StringBuilder("EmailForm?resort=" + resort + "&ID=" + patrollerId);
            for (String classification : listedClassifications) {
                emailFormUrl.append("&").append(classification).append("=1");
            }
            emailFormUrl.append("&FullTime=1&PartTime=1");
            if (fullRoster) {
                emailFormUrl.append("&Inactive=1");
            }
            emailFormUrl.append("&ALL=1");
            model.addAttribute("emailFormUrl", emailFormUrl.toString());

            // The toggle: its label describes where it goes, not where you are.
            //carry "filters" too, or the toggle would reset Pro back to ticked
            String optIns = "&filters=1" + (includePro ? "&pro=true" : "")
                              + (includeAlumni ? "&alumni=true" : "");
            model.addAttribute("fullRoster", fullRoster);
            model.addAttribute("includePro", includePro);
            model.addAttribute("includeAlumni", includeAlumni);
            model.addAttribute("toggleRosterUrl", "MemberList?resort=" + resort + "&ID=" + patrollerId
                                 + (fullRoster ? "" : "&" + FULL_ROSTER + "=true") + optIns);

            return "member-list";

        } finally {
            patrolData.close();
        }
    }
}
