package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;
import java.sql.Connection;

@Controller
public class UpdateInfoController {

    /**
     * Model class for member update form binding.
     * Contains 15 editable fields from the roster.
     */
    public static class MemberUpdateForm {
        @NotBlank(message = "First name is required")
        private String firstName;

        @NotBlank(message = "Last name is required")
        private String lastName;

        private String spouse;
        private String address;
        private String city;
        private String state;
        private String zipCode;
        private String homePhone;
        private String workPhone;
        private String cellPhone;
        private String pager;

        @Email(message = "Invalid email format")
        private String email;

        private String emergencyCallUp;
        private String substituteList;
        private String password;

        // No-arg constructor
        public MemberUpdateForm() {
        }

        // All-args constructor
        public MemberUpdateForm(String firstName, String lastName, String spouse, String address,
                                String city, String state, String zipCode, String homePhone,
                                String workPhone, String cellPhone, String pager, String email,
                                String emergencyCallUp, String substituteList) {
            this.firstName = firstName;
            this.lastName = lastName;
            this.spouse = spouse;
            this.address = address;
            this.city = city;
            this.state = state;
            this.zipCode = zipCode;
            this.homePhone = homePhone;
            this.workPhone = workPhone;
            this.cellPhone = cellPhone;
            this.pager = pager;
            this.email = email;
            this.emergencyCallUp = emergencyCallUp;
            this.substituteList = substituteList;
            this.password = "";
        }

        // Getters and setters
        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        public String getSpouse() {
            return spouse;
        }

        public void setSpouse(String spouse) {
            this.spouse = spouse;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getZipCode() {
            return zipCode;
        }

        public void setZipCode(String zipCode) {
            this.zipCode = zipCode;
        }

        public String getHomePhone() {
            return homePhone;
        }

        public void setHomePhone(String homePhone) {
            this.homePhone = homePhone;
        }

        public String getWorkPhone() {
            return workPhone;
        }

        public void setWorkPhone(String workPhone) {
            this.workPhone = workPhone;
        }

        public String getCellPhone() {
            return cellPhone;
        }

        public void setCellPhone(String cellPhone) {
            this.cellPhone = cellPhone;
        }

        public String getPager() {
            return pager;
        }

        public void setPager(String pager) {
            this.pager = pager;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getEmergencyCallUp() {
            return emergencyCallUp;
        }

        public void setEmergencyCallUp(String emergencyCallUp) {
            this.emergencyCallUp = emergencyCallUp;
        }

        public String getSubstituteList() {
            return substituteList;
        }

        public void setSubstituteList(String substituteList) {
            this.substituteList = substituteList;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        /**
         * Create MemberUpdateForm populated from Roster object.
         * Uses Roster field constants to extract data from memberData array.
         */
        public static MemberUpdateForm populateFromRoster(Roster roster) {
            if (roster == null || roster.memberData == null) {
                return new MemberUpdateForm();
            }

            MemberUpdateForm form = new MemberUpdateForm();
            form.setFirstName(roster.memberData[Roster.FIRST]);
            form.setLastName(roster.memberData[Roster.LAST]);
            form.setSpouse(roster.memberData[Roster.SPOUSE]);
            form.setAddress(roster.memberData[Roster.ADDRESS]);
            form.setCity(roster.memberData[Roster.CITY]);
            form.setState(roster.memberData[Roster.STATE]);
            form.setZipCode(roster.memberData[Roster.ZIPCODE]);
            form.setHomePhone(roster.memberData[Roster.HOMEPHONE]);
            form.setWorkPhone(roster.memberData[Roster.WORKPHONE]);
            form.setCellPhone(roster.memberData[Roster.CELLPHONE]);
            form.setPager(roster.memberData[Roster.PAGER]);
            form.setEmail(roster.memberData[Roster.EMAIL]);
            form.setEmergencyCallUp(roster.memberData[Roster.EMERGENCY]);
            form.setSubstituteList(roster.memberData[Roster.SUB]);
            form.setPassword(""); // Never populate password from database

            return form;
        }
    }

    /**
     * Convert commitment code to display label.
     * @param value "2"=Full Time, "1"=Part Time, else=Inactive
     */
    private String getCommitmentLabel(String value) {
        if (value == null) {
            return "Inactive";
        }
        return Roster.getCommitmentString(value);
    }

    /**
     * Convert instructor bit flags to certification list.
     * @param value Bit flags: 1=OEC, 2=CPR, 4=Ski, 8=Toboggan
     */
    private String getInstructorLabel(String value) {
        if (value == null) {
            return "";
        }
        return Roster.getInstructorString(value);
    }

    /**
     * Convert classification code to full name.
     * Uses Roster's getFullClassification method.
     * @param member Roster object with classification data
     */
    private String getClassificationLabel(Roster member) {
        if (member == null) {
            return "";
        }
        String fullClassification = member.getFullClassification();
        return fullClassification != null ? fullClassification : "";
    }

    @GetMapping("/UpdateInfo")
    public String showUpdateForm(HttpServletRequest request, HttpServletResponse response,
                                 Model model) throws IOException {
        // 1. Read resort and ID parameters
        String resort = request.getParameter("resort");
        String patrollerId = request.getParameter("ID");

        // 2. Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(UpdateInfoController.class, javaxRequest, "GET", resort, Logger.INFO);

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
                    "UpdateInfo", patrolData, LOG);

            if (credentials.hasInvalidCredentials()) {
                return null; // Redirect already sent
            }

            // 6. Get logged-in user info
            patrollerId = sessionData.getLoggedInUserId();

            // 7. Check for backdoor user
            boolean isBackdoorUser = patrollerId.equalsIgnoreCase(sessionData.getBackDoorUser());

            if (isBackdoorUser) {
                // Backdoor user - show friendly message
                model.addAttribute("resort", resort);
                model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
                model.addAttribute("patrollerId", patrollerId);
                model.addAttribute("isBackdoorUser", true);

                // Add resort metadata for header
                ResortData resortData = PatrolData.getResortInfo(resort);
                if (resortData != null) {
                    model.addAttribute("resortUrl", resortData.getResortURL());
                    model.addAttribute("resortImage", resortData.getResortImage());
                    model.addAttribute("resortImgHeight", resortData.getImageHeight());
                }

                return "update-info";
            }

            // 8. Load member data
            Roster member = patrolData.getMemberByID(patrollerId);
            if (member == null) {
                model.addAttribute("resort", resort);
                model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
                model.addAttribute("patrollerId", patrollerId);
                model.addAttribute("errorMessage", "Member not found: " + patrollerId);
                model.addAttribute("isBackdoorUser", false);

                // Add resort metadata for header
                ResortData resortData = PatrolData.getResortInfo(resort);
                if (resortData != null) {
                    model.addAttribute("resortUrl", resortData.getResortURL());
                    model.addAttribute("resortImage", resortData.getResortImage());
                    model.addAttribute("resortImgHeight", resortData.getImageHeight());
                }

                return "update-info";
            }

            // 9. Populate form from member data
            MemberUpdateForm memberForm = MemberUpdateForm.populateFromRoster(member);

            // 10. Add model attributes for template
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("memberName", member.getFullName());
            model.addAttribute("isBackdoorUser", false);
            model.addAttribute("memberForm", memberForm);

            // 11. Add read-only display attributes (converted to labels)
            model.addAttribute("idNumber", member.memberData[Roster.ID_NUM]);
            model.addAttribute("classification", getClassificationLabel(member));
            model.addAttribute("commitment", getCommitmentLabel(member.memberData[Roster.COMMITMENT]));
            model.addAttribute("instructor", getInstructorLabel(member.memberData[Roster.INSTRUCTOR]));
            model.addAttribute("lastUpdated", member.memberData[Roster.LAST_UPDATED]);

            // 12. Add hidden field values to preserve read-only data
            model.addAttribute("hiddenClassification", member.memberData[Roster.CLASSIFICATION]);
            model.addAttribute("hiddenCommitment", member.memberData[Roster.COMMITMENT]);
            model.addAttribute("hiddenInstructor", member.memberData[Roster.INSTRUCTOR]);
            model.addAttribute("hiddenDirector", member.memberData[Roster.DIRECTOR]);
            model.addAttribute("hiddenTeamLead", member.memberData[Roster.TEAM_LEAD]);
            model.addAttribute("hiddenMentoring", member.memberData[Roster.MENTORING]);
            model.addAttribute("hiddenComments", member.memberData[Roster.COMMENTS]);

            // 13. Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            return "update-info";

        } finally {
            patrolData.close();
        }
    }

    @PostMapping("/UpdateInfo")
    public String handleUpdate(@ModelAttribute("memberForm") @Valid MemberUpdateForm memberForm,
                               BindingResult bindingResult,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               Model model) {
        // TODO: Implement POST handler
        // - Validate form submission
        // - Check for validation errors
        // - Update roster in database
        // - Handle password change if provided
        // - Return success/error response
        return "update-info";
    }
}
