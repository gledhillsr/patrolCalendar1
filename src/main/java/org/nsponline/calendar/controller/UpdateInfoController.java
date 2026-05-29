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
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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
        private String idNumber; // For setNewID functionality

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

        public String getIdNumber() {
            return idNumber;
        }

        public void setIdNumber(String idNumber) {
            this.idNumber = idNumber;
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
            form.setIdNumber(roster.memberData[Roster.ID_NUM]); // For setNewID functionality

            return form;
        }

        /**
         * Apply form data to Roster object for database update.
         * Handles null values by converting to empty strings.
         * Password is only updated if non-blank (hashed with SaltUtils).
         *
         * @param roster Roster object to update
         * @param sessionData SessionData for password hashing
         * @param LOG Logger for error reporting
         */
        public void applyToRoster(Roster roster, SessionData sessionData, Logger LOG) {
            if (roster == null || roster.memberData == null) {
                return;
            }

            // Copy all form fields to roster.memberData array, handling nulls
            roster.memberData[Roster.FIRST] = (firstName != null) ? firstName : "";
            roster.memberData[Roster.LAST] = (lastName != null) ? lastName : "";
            roster.memberData[Roster.SPOUSE] = (spouse != null) ? spouse : "";
            roster.memberData[Roster.ADDRESS] = (address != null) ? address : "";
            roster.memberData[Roster.CITY] = (city != null) ? city : "";
            roster.memberData[Roster.STATE] = (state != null) ? state : "";
            roster.memberData[Roster.ZIPCODE] = (zipCode != null) ? zipCode : "";
            roster.memberData[Roster.HOMEPHONE] = (homePhone != null) ? homePhone : "";
            roster.memberData[Roster.WORKPHONE] = (workPhone != null) ? workPhone : "";
            roster.memberData[Roster.CELLPHONE] = (cellPhone != null) ? cellPhone : "";
            roster.memberData[Roster.PAGER] = (pager != null) ? pager : "";
            roster.memberData[Roster.EMAIL] = (email != null) ? email : "";
            roster.memberData[Roster.EMERGENCY] = (emergencyCallUp != null) ? emergencyCallUp : "";
            roster.memberData[Roster.SUB] = (substituteList != null) ? substituteList : "";

            // ID Number: update for setNewID functionality
            if (idNumber != null && !idNumber.trim().isEmpty()) {
                roster.memberData[Roster.ID_NUM] = idNumber;
            }

            // Password: only update if non-blank
            if (password != null && !password.trim().isEmpty()) {
                try {
                    String hashedPassword = new SaltUtils().hashPassword(sessionData, password);
                    roster.memberData[Roster.NEW_PASSWORD] = hashedPassword;
                } catch (java.security.NoSuchAlgorithmException e) {
                    LOG.logException("Failed to hash password", e);
                }
            }
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
            String loggedInUserId = sessionData.getLoggedInUserId();

            // Check if editing another patroller (director function)
            String editID = request.getParameter("EditID");
            String purpose = request.getParameter("Purpose");
            boolean isSetNewID = "setNewID".equals(purpose);
            String oldMemberID = null;

            if (editID != null && ("Edit_Patroller".equals(purpose) || isSetNewID)) {
                // Director is editing another patroller or changing ID
                patrollerId = editID;
                if (isSetNewID) {
                    oldMemberID = editID; // Store for later use
                }
            } else {
                // User is editing their own profile
                patrollerId = loggedInUserId;
            }

            // 7. Check for backdoor user
            boolean isBackdoorUser = loggedInUserId.equalsIgnoreCase(sessionData.getBackDoorUser());

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
            model.addAttribute("patrollerId", loggedInUserId); // For header navigation
            model.addAttribute("editingPatrollerId", patrollerId); // The ID being edited
            model.addAttribute("memberName", member.getFullName());
            model.addAttribute("isBackdoorUser", false);
            model.addAttribute("memberForm", memberForm);
            model.addAttribute("isSetNewID", isSetNewID);
            model.addAttribute("oldMemberID", oldMemberID);

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
    public String updateProfile(@ModelAttribute("memberForm") @Valid MemberUpdateForm memberForm,
                                BindingResult bindingResult,
                                HttpServletRequest request,
                                HttpServletResponse response,
                                Model model) throws IOException {
        // 1. Read resort and ID parameters
        String resort = request.getParameter("resort");
        String patrollerId = request.getParameter("ID");

        // 2. Use ServletAdapter for legacy code compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(UpdateInfoController.class, javaxRequest, "POST", resort, Logger.INFO);

        // 3. Check for FinalDelete purpose (from delete confirmation page)
        String purpose = request.getParameter("Purpose");
        if ("FinalDelete".equals(purpose)) {
            return handleFinalDelete(resort, patrollerId, request, response, model);
        }

        // 4. Check if this is a director action from Directors menu
        String editInfo = request.getParameter("EditInfo");
        String nameToEdit = request.getParameter("NameToEdit");
        String setNewID = request.getParameter("setNewID");
        String removeName = request.getParameter("removeName");

        if (editInfo != null && nameToEdit != null) {
            // Director clicked "Edit Patroller Info" - redirect to GET with selected patroller
            // nameToEdit is in format "Last, First" - need to find the patroller ID
            SessionData sessionData = new SessionData(javaxRequest, null, LOG);
            PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
            try {
                // Find the patroller by name
                String targetPatrollerId = findPatrollerIdByName(nameToEdit, patrolData);
                if (targetPatrollerId != null) {
                    response.sendRedirect("UpdateInfo?resort=" + resort + "&ID=" + patrollerId +
                                        "&Purpose=Edit_Patroller&EditID=" + targetPatrollerId);
                } else {
                    response.sendRedirect("Directors?resort=" + resort + "&ID=" + patrollerId);
                }
            } finally {
                patrolData.close();
            }
            return null;
        }

        if (removeName != null && nameToEdit != null) {
            // Director clicked "Delete Patroller" - show confirmation page
            return showDeleteConfirmation(resort, patrollerId, nameToEdit, request, response, model);
        }

        if (setNewID != null && nameToEdit != null) {
            // Director clicked "Change ID Number" - redirect to UpdateInfo with selected patroller
            SessionData sessionData = new SessionData(javaxRequest, null, LOG);
            PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
            try {
                // Find the patroller by name
                String targetPatrollerId = findPatrollerIdByName(nameToEdit, patrolData);
                if (targetPatrollerId != null) {
                    response.sendRedirect("UpdateInfo?resort=" + resort + "&ID=" + patrollerId +
                                        "&Purpose=setNewID&EditID=" + targetPatrollerId);
                } else {
                    response.sendRedirect("Directors?resort=" + resort + "&ID=" + patrollerId);
                }
            } finally {
                patrolData.close();
            }
            return null;
        }

        // 4. Validate resort
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        // 5. Initialize session and patrol data
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        try {
            // 6. Validate credentials
            ValidateCredentialsRedirectIfNeeded credentials =
                new ValidateCredentialsRedirectIfNeeded(
                    sessionData, javaxRequest, javaxResponse,
                    "UpdateInfo", patrolData, LOG);

            if (credentials.hasInvalidCredentials()) {
                return null; // Redirect already sent
            }

            // 7. Get logged-in user info
            String loggedInUserId = sessionData.getLoggedInUserId();

            // The ID parameter from the form indicates which member to update
            // This could be the logged-in user updating themselves, or a director editing another member
            // patrollerId already has the value from request.getParameter("ID") on line 433

            // 8. Check for backdoor user (cannot update)
            boolean isBackdoorUser = loggedInUserId.equalsIgnoreCase(sessionData.getBackDoorUser());

            if (isBackdoorUser) {
                // Redirect back to GET for backdoor user
                response.sendRedirect("UpdateInfo?resort=" + resort + "&ID=" + patrollerId);
                return null;
            }

            // 8. Check for validation errors
            if (bindingResult.hasErrors()) {
                // Load member data to re-populate page
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

                // Re-populate all model attributes for error display
                model.addAttribute("resort", resort);
                model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
                model.addAttribute("patrollerId", patrollerId);
                model.addAttribute("memberName", member.getFullName());
                model.addAttribute("isBackdoorUser", false);

                // Add read-only display attributes
                model.addAttribute("idNumber", member.memberData[Roster.ID_NUM]);
                model.addAttribute("classification", getClassificationLabel(member));
                model.addAttribute("commitment", getCommitmentLabel(member.memberData[Roster.COMMITMENT]));
                model.addAttribute("instructor", getInstructorLabel(member.memberData[Roster.INSTRUCTOR]));
                model.addAttribute("lastUpdated", member.memberData[Roster.LAST_UPDATED]);

                // Add hidden field values
                model.addAttribute("hiddenClassification", member.memberData[Roster.CLASSIFICATION]);
                model.addAttribute("hiddenCommitment", member.memberData[Roster.COMMITMENT]);
                model.addAttribute("hiddenInstructor", member.memberData[Roster.INSTRUCTOR]);
                model.addAttribute("hiddenDirector", member.memberData[Roster.DIRECTOR]);
                model.addAttribute("hiddenTeamLead", member.memberData[Roster.TEAM_LEAD]);
                model.addAttribute("hiddenMentoring", member.memberData[Roster.MENTORING]);
                model.addAttribute("hiddenComments", member.memberData[Roster.COMMENTS]);

                // Add resort metadata for header
                ResortData resortData = PatrolData.getResortInfo(resort);
                if (resortData != null) {
                    model.addAttribute("resortUrl", resortData.getResortURL());
                    model.addAttribute("resortImage", resortData.getResortImage());
                    model.addAttribute("resortImgHeight", resortData.getImageHeight());
                }

                return "update-info"; // Re-display form with validation errors
            }

            // 9. Check for setNewID purpose (Change ID Number)
            String postPurpose = request.getParameter("Purpose");
            String removeOldID = request.getParameter("removeOldID");
            if ("setNewID".equals(postPurpose) && removeOldID != null) {
                return handleSetNewID(resort, loggedInUserId, removeOldID, memberForm, request, response, model, sessionData, patrolData, LOG);
            }

            // 10. Load existing member record
            Roster member = patrolData.getMemberByID(patrollerId);
            if (member == null) {
                model.addAttribute("resort", resort);
                model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
                model.addAttribute("patrollerId", patrollerId);
                model.addAttribute("errorMessage", "Member not found: " + patrollerId);
                model.addAttribute("isBackdoorUser", false);
                model.addAttribute("memberForm", memberForm);

                // Add resort metadata for header
                ResortData resortData = PatrolData.getResortInfo(resort);
                if (resortData != null) {
                    model.addAttribute("resortUrl", resortData.getResortURL());
                    model.addAttribute("resortImage", resortData.getResortImage());
                    model.addAttribute("resortImgHeight", resortData.getImageHeight());
                }

                return "update-info";
            }

            // 10. Apply form values to roster
            memberForm.applyToRoster(member, sessionData, LOG);

            // 11. Update database
            try {
                Connection connection = patrolData.getConnection(resort, sessionData, LOG);
                String sql = member.getUpdateSQLString(resort, sessionData);
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.executeUpdate();
                connection.close();
                LOG.info("Successfully updated member: " + patrollerId);
            } catch (Exception e) {
                LOG.logException("Failed to update member: " + patrollerId, e);

                // Re-populate form on error
                model.addAttribute("resort", resort);
                model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
                model.addAttribute("patrollerId", patrollerId);
                model.addAttribute("memberName", member.getFullName());
                model.addAttribute("isBackdoorUser", false);
                model.addAttribute("memberForm", memberForm);
                model.addAttribute("errorMessage", "Database error: Failed to update profile. Please try again.");

                // Add read-only display attributes
                model.addAttribute("idNumber", member.memberData[Roster.ID_NUM]);
                model.addAttribute("classification", getClassificationLabel(member));
                model.addAttribute("commitment", getCommitmentLabel(member.memberData[Roster.COMMITMENT]));
                model.addAttribute("instructor", getInstructorLabel(member.memberData[Roster.INSTRUCTOR]));
                model.addAttribute("lastUpdated", member.memberData[Roster.LAST_UPDATED]);

                // Add hidden field values
                model.addAttribute("hiddenClassification", member.memberData[Roster.CLASSIFICATION]);
                model.addAttribute("hiddenCommitment", member.memberData[Roster.COMMITMENT]);
                model.addAttribute("hiddenInstructor", member.memberData[Roster.INSTRUCTOR]);
                model.addAttribute("hiddenDirector", member.memberData[Roster.DIRECTOR]);
                model.addAttribute("hiddenTeamLead", member.memberData[Roster.TEAM_LEAD]);
                model.addAttribute("hiddenMentoring", member.memberData[Roster.MENTORING]);
                model.addAttribute("hiddenComments", member.memberData[Roster.COMMENTS]);

                // Add resort metadata for header
                ResortData resortData = PatrolData.getResortInfo(resort);
                if (resortData != null) {
                    model.addAttribute("resortUrl", resortData.getResortURL());
                    model.addAttribute("resortImage", resortData.getResortImage());
                    model.addAttribute("resortImgHeight", resortData.getImageHeight());
                }

                return "update-info";
            }

            // 12. Reload member to get updated timestamp
            member = patrolData.getMemberByID(patrollerId);
            if (member == null) {
                // This shouldn't happen, but handle gracefully
                model.addAttribute("resort", resort);
                model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
                model.addAttribute("patrollerId", patrollerId);
                model.addAttribute("errorMessage", "Failed to reload updated member data");
                model.addAttribute("isBackdoorUser", false);
                model.addAttribute("memberForm", memberForm);

                // Add resort metadata for header
                ResortData resortData = PatrolData.getResortInfo(resort);
                if (resortData != null) {
                    model.addAttribute("resortUrl", resortData.getResortURL());
                    model.addAttribute("resortImage", resortData.getResortImage());
                    model.addAttribute("resortImgHeight", resortData.getImageHeight());
                }

                return "update-info";
            }

            // 13. Populate form from reloaded member
            MemberUpdateForm updatedForm = MemberUpdateForm.populateFromRoster(member);

            // 14. Add all model attributes for success display
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("memberName", member.getFullName());
            model.addAttribute("isBackdoorUser", false);
            model.addAttribute("memberForm", updatedForm);
            model.addAttribute("updateSuccess", true);

            // 15. Add read-only display attributes
            model.addAttribute("idNumber", member.memberData[Roster.ID_NUM]);
            model.addAttribute("classification", getClassificationLabel(member));
            model.addAttribute("commitment", getCommitmentLabel(member.memberData[Roster.COMMITMENT]));
            model.addAttribute("instructor", getInstructorLabel(member.memberData[Roster.INSTRUCTOR]));
            model.addAttribute("lastUpdated", member.memberData[Roster.LAST_UPDATED]);

            // 16. Add hidden field values
            model.addAttribute("hiddenClassification", member.memberData[Roster.CLASSIFICATION]);
            model.addAttribute("hiddenCommitment", member.memberData[Roster.COMMITMENT]);
            model.addAttribute("hiddenInstructor", member.memberData[Roster.INSTRUCTOR]);
            model.addAttribute("hiddenDirector", member.memberData[Roster.DIRECTOR]);
            model.addAttribute("hiddenTeamLead", member.memberData[Roster.TEAM_LEAD]);
            model.addAttribute("hiddenMentoring", member.memberData[Roster.MENTORING]);
            model.addAttribute("hiddenComments", member.memberData[Roster.COMMENTS]);

            // 17. Add resort metadata for header
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

    /**
     * Find patroller ID by their name in "Last, First" format.
     * Iterates through the roster to find a matching member.
     */
    private String findPatrollerIdByName(String nameToEdit, PatrolData patrolData) {
        if (nameToEdit == null || nameToEdit.trim().isEmpty()) {
            return null;
        }

        ResultSet rosterResults = patrolData.resetRoster();
        Roster member;
        while ((member = patrolData.nextMember("", rosterResults)) != null) {
            String memberName = member.getLast() + ", " + member.getFirst();
            if (memberName.equals(nameToEdit)) {
                return member.getID();
            }
        }
        return null;
    }

    /**
     * Show delete confirmation page.
     */
    private String showDeleteConfirmation(
            String resort,
            String patrollerId,
            String nameToEdit,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(UpdateInfoController.class, javaxRequest, "POST", resort, Logger.INFO);

        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        ValidateCredentialsRedirectIfNeeded credentials =
            new ValidateCredentialsRedirectIfNeeded(
                sessionData, javaxRequest, javaxResponse,
                "UpdateInfo", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null;
        }

        try {
            // Find the member to delete
            Roster member = patrolData.getMemberByLastNameFirstName(nameToEdit);
            if (member == null) {
                LOG.error("Member not found: " + nameToEdit);
                response.sendRedirect("Directors?resort=" + resort + "&ID=" + patrollerId);
                return null;
            }

            // Add model attributes for confirmation page
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("nameToEdit", nameToEdit);
            model.addAttribute("memberName", member.getFullName());
            model.addAttribute("memberData", member);

            // Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            return "delete-patroller-confirm";

        } finally {
            patrolData.close();
        }
    }

    /**
     * Handle final deletion of patroller record.
     */
    private String handleFinalDelete(
            String resort,
            String patrollerId,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(UpdateInfoController.class, javaxRequest, "POST", resort, Logger.INFO);

        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        ValidateCredentialsRedirectIfNeeded credentials =
            new ValidateCredentialsRedirectIfNeeded(
                sessionData, javaxRequest, javaxResponse,
                "UpdateInfo", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            patrolData.close();
            return null;
        }

        try {
            String nameToEdit = request.getParameter("NameToEdit");
            if (nameToEdit == null) {
                LOG.error("NameToEdit parameter missing for FinalDelete");
                response.sendRedirect("Directors?resort=" + resort + "&ID=" + patrollerId);
                return null;
            }

            // Find the member to delete
            Roster member = patrolData.getMemberByLastNameFirstName(nameToEdit);
            if (member == null) {
                LOG.error("Member not found for deletion: " + nameToEdit);
                response.sendRedirect("Directors?resort=" + resort + "&ID=" + patrollerId);
                return null;
            }

            String memberIdToDelete = member.getID();
            String memberName = member.getFullName();

            // Delete from database
            Connection connection = patrolData.getConnection(resort, sessionData, LOG);
            String sql = member.getDeleteSQLString(resort);
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.executeUpdate();

            // Replace member ID with "0" in assignments table
            updateAssignmentsAfterDelete(memberIdToDelete, connection);

            LOG.info("Successfully deleted member: " + memberName + " (ID: " + memberIdToDelete + ")");

            // Show success page
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("memberName", memberName);
            model.addAttribute("deleteSuccess", true);

            // Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            return "delete-patroller-success";

        } catch (Exception e) {
            LOG.logException("Failed to delete member", e);
            response.sendRedirect("Directors?resort=" + resort + "&ID=" + patrollerId + "&error=delete_failed");
            return null;
        } finally {
            patrolData.close();
        }
    }

    /**
     * Update assignments table after deleting a patroller.
     * Replace the deleted member's ID with "0" in all assignments.
     */
    private void updateAssignmentsAfterDelete(String deletedId, Connection connection) throws Exception {
        // Update all assignment patroller fields (P0 through P9)
        for (int i = 0; i < 10; i++) {
            String sql = "UPDATE assignments SET P" + i + " = '0' WHERE P" + i + " = ?";
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, deletedId);
            statement.executeUpdate();
        }
    }

    /**
     * Handle setting a new ID for a patroller.
     */
    private String handleSetNewID(
            String resort,
            String loggedInUserId,
            String oldMemberID,
            MemberUpdateForm memberForm,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            SessionData sessionData,
            PatrolData patrolData,
            Logger LOG) throws IOException {

        String newMemberID = memberForm.getIdNumber();

        // Check if new ID is already in use
        Roster existingMember = patrolData.getMemberByID(newMemberID);
        if (existingMember != null && !newMemberID.equals(oldMemberID)) {
            // ID already in use - show error
            Roster oldMember = patrolData.getMemberByID(oldMemberID);
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", loggedInUserId);
            model.addAttribute("editingPatrollerId", oldMemberID);
            model.addAttribute("memberName", oldMember != null ? oldMember.getFullName() : "Unknown");
            model.addAttribute("isBackdoorUser", false);
            model.addAttribute("memberForm", memberForm);
            model.addAttribute("isSetNewID", true);
            model.addAttribute("oldMemberID", oldMemberID);
            model.addAttribute("errorMessage", "ERROR: ID '" + newMemberID + "' is already owned by " + existingMember.getFullName());

            // Add read-only attributes
            if (oldMember != null) {
                model.addAttribute("idNumber", oldMember.memberData[Roster.ID_NUM]);
                model.addAttribute("classification", getClassificationLabel(oldMember));
                model.addAttribute("commitment", getCommitmentLabel(oldMember.memberData[Roster.COMMITMENT]));
                model.addAttribute("instructor", getInstructorLabel(oldMember.memberData[Roster.INSTRUCTOR]));
                model.addAttribute("lastUpdated", oldMember.memberData[Roster.LAST_UPDATED]);

                model.addAttribute("hiddenClassification", oldMember.memberData[Roster.CLASSIFICATION]);
                model.addAttribute("hiddenCommitment", oldMember.memberData[Roster.COMMITMENT]);
                model.addAttribute("hiddenInstructor", oldMember.memberData[Roster.INSTRUCTOR]);
                model.addAttribute("hiddenDirector", oldMember.memberData[Roster.DIRECTOR]);
                model.addAttribute("hiddenTeamLead", oldMember.memberData[Roster.TEAM_LEAD]);
                model.addAttribute("hiddenMentoring", oldMember.memberData[Roster.MENTORING]);
                model.addAttribute("hiddenComments", oldMember.memberData[Roster.COMMENTS]);
            }

            // Add resort metadata
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            return "update-info";
        }

        try {
            // Load old member data
            Roster oldMember = patrolData.getMemberByID(oldMemberID);
            if (oldMember == null) {
                LOG.error("Old member not found: " + oldMemberID);
                response.sendRedirect("Directors?resort=" + resort + "&ID=" + loggedInUserId);
                return null;
            }

            // Apply form values to create new roster entry
            memberForm.applyToRoster(oldMember, sessionData, LOG);

            Connection connection = patrolData.getConnection(resort, sessionData, LOG);

            if (newMemberID.equals(oldMemberID)) {
                // ID unchanged - just update the record
                String sql = oldMember.getUpdateSQLString(resort, sessionData);
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.executeUpdate();
                LOG.info("Updated member without ID change: " + oldMemberID);
            } else {
                // ID changed - update assignments, insert new, delete old
                updateAssignmentsForIDChange(oldMemberID, newMemberID, connection);

                String insertSql = oldMember.getInsertSQLString(resort, sessionData);
                PreparedStatement insertStatement = connection.prepareStatement(insertSql);
                insertStatement.executeUpdate();

                String deleteSql = "DELETE FROM roster WHERE IDNumber = ?";
                PreparedStatement deleteStatement = connection.prepareStatement(deleteSql);
                deleteStatement.setString(1, oldMemberID);
                deleteStatement.executeUpdate();

                LOG.info("Changed member ID from " + oldMemberID + " to " + newMemberID);
            }

            // Redirect to Directors with success
            response.sendRedirect("Directors?resort=" + resort + "&ID=" + loggedInUserId);
            return null;

        } catch (Exception e) {
            LOG.logException("Failed to change member ID", e);
            response.sendRedirect("Directors?resort=" + resort + "&ID=" + loggedInUserId + "&error=id_change_failed");
            return null;
        }
    }

    /**
     * Update assignments table when changing a patroller's ID.
     * Replace old ID with new ID in all assignment slots.
     */
    private void updateAssignmentsForIDChange(String oldId, String newId, Connection connection) throws Exception {
        // Update all assignment patroller fields (P0 through P9)
        for (int i = 0; i < 10; i++) {
            String sql = "UPDATE assignments SET P" + i + " = ? WHERE P" + i + " = ?";
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, newId);
            statement.setString(2, oldId);
            statement.executeUpdate();
        }
    }
}
