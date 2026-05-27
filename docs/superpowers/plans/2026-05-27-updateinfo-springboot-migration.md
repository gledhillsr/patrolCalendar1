# UpdateInfo Spring Boot Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate UpdateInfo servlet to Spring MVC controller with Thymeleaf template for self-service profile updates

**Architecture:** Create UpdateInfoController with separate GET/POST mappings, MemberUpdateForm inner class with validation annotations, update-info.html Thymeleaf template with organized field sections. Follow existing migration patterns from SubListController, MemberListController, and ListAssignmentsController.

**Tech Stack:** Spring Boot 3.4, Thymeleaf, Spring Validation, ServletAdapter for legacy compatibility, existing PatrolData/Roster infrastructure, SaltUtils for password hashing

---

## File Structure

**New Files:**
- `src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java` - Spring MVC controller
- `src/main/resources/templates/update-info.html` - Thymeleaf template

**Reference Files:**
- `src/main/java/org/nsponline/calendar/resources/UpdateInfo.java` - Original servlet (for logic reference)
- `src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java` - Pattern reference
- `src/main/resources/templates/list-assignments.html` - Styling reference

---

## Task 1: Create UpdateInfoController with MemberUpdateForm Model

**Files:**
- Create: `src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java`

- [ ] **Step 1: Create controller skeleton with MemberUpdateForm inner class**

```java
package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot controller for updating member profile information.
 * Migrated from UpdateInfo servlet.
 *
 * Handles GET /UpdateInfo - displays profile update form
 * Handles POST /UpdateInfo - processes profile updates
 */
@Controller
public class UpdateInfoController {

    /**
     * Form model class for editable member information
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
        public MemberUpdateForm(String firstName, String lastName, String spouse,
                               String address, String city, String state, String zipCode,
                               String homePhone, String workPhone, String cellPhone, String pager,
                               String email, String emergencyCallUp, String substituteList) {
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
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        
        public String getSpouse() { return spouse; }
        public void setSpouse(String spouse) { this.spouse = spouse; }
        
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        
        public String getZipCode() { return zipCode; }
        public void setZipCode(String zipCode) { this.zipCode = zipCode; }
        
        public String getHomePhone() { return homePhone; }
        public void setHomePhone(String homePhone) { this.homePhone = homePhone; }
        
        public String getWorkPhone() { return workPhone; }
        public void setWorkPhone(String workPhone) { this.workPhone = workPhone; }
        
        public String getCellPhone() { return cellPhone; }
        public void setCellPhone(String cellPhone) { this.cellPhone = cellPhone; }
        
        public String getPager() { return pager; }
        public void setPager(String pager) { this.pager = pager; }
        
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        
        public String getEmergencyCallUp() { return emergencyCallUp; }
        public void setEmergencyCallUp(String emergencyCallUp) { this.emergencyCallUp = emergencyCallUp; }
        
        public String getSubstituteList() { return substituteList; }
        public void setSubstituteList(String substituteList) { this.substituteList = substituteList; }
        
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    @GetMapping("/UpdateInfo")
    public String showUpdateForm(HttpServletRequest request, HttpServletResponse response,
                                 Model model) throws IOException {
        // Implementation in next steps
        return "update-info";
    }

    @PostMapping("/UpdateInfo")
    public String updateProfile(HttpServletRequest request, HttpServletResponse response,
                               @ModelAttribute("memberForm") @Valid MemberUpdateForm form,
                               BindingResult bindingResult,
                               Model model) throws IOException {
        // Implementation in next steps
        return "update-info";
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit controller skeleton**

```bash
git add src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java
git commit -m "feat: add UpdateInfoController skeleton with MemberUpdateForm model"
```

---

## Task 2: Implement GET Mapping - Session Validation and Backdoor User Check

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java`

- [ ] **Step 1: Add GET implementation with session validation**

Replace the `showUpdateForm` method with:

```java
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

        // TODO: Load member data and populate form (next task)

        return "update-info";

    } finally {
        patrolData.close();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit session validation**

```bash
git add src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java
git commit -m "feat: add session validation and backdoor user check to UpdateInfo GET"
```

---

## Task 3: Implement GET Mapping - Load Member Data and Populate Form

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java`

- [ ] **Step 1: Add populateFromRoster static method to MemberUpdateForm**

Add this static method inside the MemberUpdateForm class:

```java
/**
 * Create form populated from Roster data
 */
public static MemberUpdateForm populateFromRoster(Roster roster) {
    return new MemberUpdateForm(
        roster.memberData[Roster.FIRST],
        roster.memberData[Roster.LAST],
        roster.memberData[Roster.SPOUSE],
        roster.memberData[Roster.ADDRESS],
        roster.memberData[Roster.CITY],
        roster.memberData[Roster.STATE],
        roster.memberData[Roster.ZIPCODE],
        roster.memberData[Roster.HOMEPHONE],
        roster.memberData[Roster.WORKPHONE],
        roster.memberData[Roster.CELLPHONE],
        roster.memberData[Roster.PAGER],
        roster.memberData[Roster.EMAIL],
        roster.memberData[Roster.EMERGENCY],
        roster.memberData[Roster.SUB]
    );
}
```

- [ ] **Step 2: Add helper methods for label conversion**

Add these helper methods to UpdateInfoController class (after the MemberUpdateForm class):

```java
/**
 * Convert commitment code to display label
 */
private String getCommitmentLabel(String value) {
    if ("2".equals(value)) return "Full Time";
    if ("1".equals(value)) return "Part Time";
    return "Inactive";
}

/**
 * Convert instructor bit flags to display label
 */
private String getInstructorLabel(String value) {
    int flags = 0;
    try {
        flags = Integer.parseInt(value);
    } catch (Exception e) {
        return "None";
    }
    
    List<String> certs = new ArrayList<>();
    if ((flags & 1) == 1) certs.add("OEC");
    if ((flags & 2) == 2) certs.add("CPR");
    if ((flags & 4) == 4) certs.add("Ski");
    if ((flags & 8) == 8) certs.add("Toboggan");
    
    return certs.isEmpty() ? "None" : String.join(", ", certs);
}

/**
 * Get classification full name from code
 */
private String getClassificationLabel(String code) {
    return Roster.getFullClassification(code);
}
```

- [ ] **Step 3: Replace TODO comment with member data loading**

Replace the `// TODO: Load member data and populate form (next task)` comment with:

```java
        // 8. Load member data
        Roster member = patrolData.getMemberByID(patrollerId);
        if (member == null) {
            model.addAttribute("errorMessage", "Internal Error, invalid patroller number: " + patrollerId);
            return "update-info";
        }

        // 9. Populate form from roster data
        MemberUpdateForm memberForm = MemberUpdateForm.populateFromRoster(member);

        // 10. Add model attributes
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("patrollerId", patrollerId);
        model.addAttribute("memberName", member.getFullName());
        model.addAttribute("isBackdoorUser", false);
        model.addAttribute("memberForm", memberForm);

        // 11. Add read-only field values for display
        model.addAttribute("idNumber", member.memberData[Roster.ID_NUM]);
        model.addAttribute("classification", getClassificationLabel(member.memberData[Roster.CLASSIFICATION]));
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
```

- [ ] **Step 4: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit member data loading**

```bash
git add src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java
git commit -m "feat: add member data loading and form population to UpdateInfo GET"
```

---

## Task 4: Implement POST Mapping - Form Submission and Validation

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java`

- [ ] **Step 1: Add applyToRoster method to MemberUpdateForm**

Add this method inside the MemberUpdateForm class:

```java
/**
 * Apply form values to existing Roster object
 */
public void applyToRoster(Roster roster, Logger LOG) {
    roster.memberData[Roster.FIRST] = firstName;
    roster.memberData[Roster.LAST] = lastName;
    roster.memberData[Roster.SPOUSE] = spouse != null ? spouse : "";
    roster.memberData[Roster.ADDRESS] = address != null ? address : "";
    roster.memberData[Roster.CITY] = city != null ? city : "";
    roster.memberData[Roster.STATE] = state != null ? state : "";
    roster.memberData[Roster.ZIPCODE] = zipCode != null ? zipCode : "";
    roster.memberData[Roster.HOMEPHONE] = homePhone != null ? homePhone : "";
    roster.memberData[Roster.WORKPHONE] = workPhone != null ? workPhone : "";
    roster.memberData[Roster.CELLPHONE] = cellPhone != null ? cellPhone : "";
    roster.memberData[Roster.PAGER] = pager != null ? pager : "";
    roster.memberData[Roster.EMAIL] = email != null ? email : "";
    roster.memberData[Roster.EMERGENCY] = emergencyCallUp != null ? emergencyCallUp : "";
    roster.memberData[Roster.SUB] = substituteList != null ? substituteList : "";
    
    // Handle password - only update if non-blank
    if (password != null && !password.trim().isEmpty()) {
        try {
            String hashedPassword = SaltUtils.hashPassword(password);
            roster.memberData[Roster.NEW_PASSWORD] = hashedPassword;
        } catch (NoSuchAlgorithmException e) {
            LOG.error("Error hashing password", e);
            // Keep old password
        }
    }
    // If password is blank/null, don't modify roster.memberData[NEW_PASSWORD]
}
```

- [ ] **Step 2: Replace POST method implementation**

Replace the `updateProfile` method with:

```java
@PostMapping("/UpdateInfo")
public String updateProfile(HttpServletRequest request, HttpServletResponse response,
                           @ModelAttribute("memberForm") @Valid MemberUpdateForm form,
                           BindingResult bindingResult,
                           Model model) throws IOException {
    // 1. Read resort and ID parameters
    String resort = request.getParameter("resort");
    String patrollerId = request.getParameter("ID");

    // 2. Use ServletAdapter for legacy code compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
    Logger LOG = new Logger(UpdateInfoController.class, javaxRequest, "POST", resort, Logger.INFO);

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

        // 7. Check for backdoor user (shouldn't post, but handle it)
        boolean isBackdoorUser = patrollerId.equalsIgnoreCase(sessionData.getBackDoorUser());
        if (isBackdoorUser) {
            return "redirect:/UpdateInfo?resort=" + resort + "&ID=" + patrollerId;
        }

        // 8. Check for validation errors
        if (bindingResult.hasErrors()) {
            // Re-populate model for form re-display
            Roster member = patrolData.getMemberByID(patrollerId);
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("memberName", member.getFullName());
            model.addAttribute("isBackdoorUser", false);
            
            // Add read-only field values
            model.addAttribute("idNumber", member.memberData[Roster.ID_NUM]);
            model.addAttribute("classification", getClassificationLabel(member.memberData[Roster.CLASSIFICATION]));
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
            
            // Add resort metadata
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }
            
            return "update-info";
        }

        // TODO: Update database (next task)

        return "update-info";

    } finally {
        patrolData.close();
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit POST validation**

```bash
git add src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java
git commit -m "feat: add form validation and error handling to UpdateInfo POST"
```

---

## Task 5: Implement POST Mapping - Database Update and Success Message

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java`

- [ ] **Step 1: Replace TODO comment with database update logic**

Replace the `// TODO: Update database (next task)` comment with:

```java
        // 9. Load existing member record
        Roster member = patrolData.getMemberByID(patrollerId);
        if (member == null) {
            model.addAttribute("errorMessage", "Internal Error, invalid patroller number: " + patrollerId);
            return "update-info";
        }

        // 10. Apply form values to roster (editable fields only)
        form.applyToRoster(member, LOG);

        // 11. Update database
        try {
            java.sql.Connection connection = patrolData.getConnection(resort, sessionData, LOG);
            String updateSQL = member.getUpdateSQLString(resort, sessionData);
            java.sql.PreparedStatement statement = connection.prepareStatement(updateSQL);
            statement.executeUpdate();
            connection.close();
            LOG.info("Updated profile for patroller " + patrollerId);
        } catch (Exception e) {
            LOG.error("Error updating profile for patroller " + patrollerId, e);
            model.addAttribute("errorMessage", "Error updating profile: " + e.getMessage());
            
            // Re-populate form for display
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("memberName", member.getFullName());
            model.addAttribute("isBackdoorUser", false);
            model.addAttribute("memberForm", form);
            
            return "update-info";
        }

        // 12. Reload member to get updated timestamp
        member = patrolData.getMemberByID(patrollerId);

        // 13. Set success flag and re-populate model
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("patrollerId", patrollerId);
        model.addAttribute("memberName", member.getFullName());
        model.addAttribute("isBackdoorUser", false);
        model.addAttribute("memberForm", form);
        model.addAttribute("updateSuccess", true);

        // 14. Add read-only field values for display
        model.addAttribute("idNumber", member.memberData[Roster.ID_NUM]);
        model.addAttribute("classification", getClassificationLabel(member.memberData[Roster.CLASSIFICATION]));
        model.addAttribute("commitment", getCommitmentLabel(member.memberData[Roster.COMMITMENT]));
        model.addAttribute("instructor", getInstructorLabel(member.memberData[Roster.INSTRUCTOR]));
        model.addAttribute("lastUpdated", member.memberData[Roster.LAST_UPDATED]);

        // 15. Add hidden field values
        model.addAttribute("hiddenClassification", member.memberData[Roster.CLASSIFICATION]);
        model.addAttribute("hiddenCommitment", member.memberData[Roster.COMMITMENT]);
        model.addAttribute("hiddenInstructor", member.memberData[Roster.INSTRUCTOR]);
        model.addAttribute("hiddenDirector", member.memberData[Roster.DIRECTOR]);
        model.addAttribute("hiddenTeamLead", member.memberData[Roster.TEAM_LEAD]);
        model.addAttribute("hiddenMentoring", member.memberData[Roster.MENTORING]);
        model.addAttribute("hiddenComments", member.memberData[Roster.COMMENTS]);

        // 16. Add resort metadata for header
        ResortData resortData = PatrolData.getResortInfo(resort);
        if (resortData != null) {
            model.addAttribute("resortUrl", resortData.getResortURL());
            model.addAttribute("resortImage", resortData.getResortImage());
            model.addAttribute("resortImgHeight", resortData.getImageHeight());
        }
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit database update**

```bash
git add src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java
git commit -m "feat: add database update and success message to UpdateInfo POST"
```

---

## Task 6: Create Thymeleaf Template Structure with Header and JavaScript

**Files:**
- Create: `src/main/resources/templates/update-info.html`

- [ ] **Step 1: Create template with head, header, and JavaScript validation**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title th:text="'Update Personal Information for ' + ${memberName}">Update Personal Information</title>
    <style type="text/css">
        body {
            font-size: 10pt;
            color: #000000;
            background-color: #ffffff;
        }
        .success-message {
            background-color: #d4edda;
            border: 1px solid #c3e6cb;
            color: #155724;
            padding: 12px;
            margin-bottom: 20px;
            border-radius: 4px;
        }
        .error-message {
            background-color: #f8d7da;
            border: 1px solid #f5c6cb;
            color: #721c24;
            padding: 12px;
            margin-bottom: 20px;
            border-radius: 4px;
        }
    </style>
    <script type="text/javascript">
        function validate() {
            var lastName = document.forms[0].lastName.value;
            var firstName = document.forms[0].firstName.value;
            
            // Check last name not blank
            var found = false;
            for(var i=0; i < lastName.length && !found; ++i) {
                if(lastName.charAt(i) != " ") found = true;
            }
            if(!found) {
                alert("You MUST enter a non-blank Last Name");
                return false;
            }
            
            // Check first name not blank
            found = false;
            for(var i=0; i < firstName.length && !found; ++i) {
                if(firstName.charAt(i) != " ") found = true;
            }
            if(!found) {
                alert("You MUST enter a non-blank First Name");
                return false;
            }
            
            return true;
        }
    </script>
    <meta http-equiv="Pragma" content="no-cache">
    <meta http-equiv="Expires" content="-1">
</head>
<body topmargin="0" leftmargin="0" style="background-image: url(/calendar-1/images/ncmnthbk.jpg)">
    <div th:replace="~{fragments/header :: header(resort=${resort}, resortFullName=${resortFullName}, patrollerId=${patrollerId})}"></div>

    <h2 th:text="'Personal Information for: ' + ${memberName}">Personal Information</h2>
    <p><b th:text="'host resort: ' + ${resortFullName}">host resort:</b></p>

    <!-- Backdoor user message -->
    <div th:if="${isBackdoorUser}">
        <p>Have a great day</p>
    </div>

    <!-- Main form content - next step -->
    
    <div th:replace="~{fragments/footer :: footer}"></div>
</body>
</html>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit template structure**

```bash
git add src/main/resources/templates/update-info.html
git commit -m "feat: add UpdateInfo Thymeleaf template structure with header and JavaScript"
```

---

## Task 7: Add Form with Success/Error Messages and Personal Information Section

**Files:**
- Modify: `src/main/resources/templates/update-info.html`

- [ ] **Step 1: Replace main form content comment with form start and messages**

Replace the `<!-- Main form content - next step -->` comment with:

```html
    <!-- Main form (non-backdoor users) -->
    <div th:unless="${isBackdoorUser}">
        <!-- Success message -->
        <div th:if="${updateSuccess}" class="success-message">
            <strong>Success!</strong> Your profile has been updated successfully.
        </div>

        <!-- Error message -->
        <div th:if="${errorMessage}" class="error-message">
            <strong>Error:</strong> <span th:text="${errorMessage}">Error message</span>
        </div>

        <!-- Validation errors -->
        <div th:if="${#fields.hasAnyErrors()}" class="error-message">
            <strong>Please correct the following errors:</strong>
            <ul>
                <li th:each="err : ${#fields.allErrors()}" th:text="${err}">Error</li>
            </ul>
        </div>

        <form th:action="@{/UpdateInfo(resort=${resort}, ID=${patrollerId})}" 
              th:object="${memberForm}"
              method="POST" 
              onsubmit="return validate()">

            <table border="0" width="900" cellspacing="0" cellpadding="0">
                <!-- Personal Information Section -->
                <tr>
                    <td colspan="2"><h3>Personal Information</h3></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">First Name:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{firstName}" />
                        <span th:if="${#fields.hasErrors('firstName')}" th:errors="*{firstName}" style="color: red;"></span>
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Last Name:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{lastName}" />
                        <span th:if="${#fields.hasErrors('lastName')}" th:errors="*{lastName}" style="color: red;"></span>
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Spouse:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{spouse}" />
                    </font></td>
                </tr>

                <!-- Contact Information Section - next step -->
                
                <!-- Patrol Preferences Section - next step -->
                
                <!-- Security Section - next step -->
                
                <!-- Read-Only Information Section - next step -->
                
                <!-- Hidden fields - next step -->

                <!-- Submit button -->
                <tr>
                    <td colspan="2">
                        <br>
                        <input type="submit" value="Update Profile" />
                    </td>
                </tr>
            </table>
        </form>
    </div>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit form start and personal info**

```bash
git add src/main/resources/templates/update-info.html
git commit -m "feat: add form structure and personal information section to UpdateInfo template"
```

---

## Task 8: Add Contact Information Section to Form

**Files:**
- Modify: `src/main/resources/templates/update-info.html`

- [ ] **Step 1: Replace contact information comment**

Replace the `<!-- Contact Information Section - next step -->` comment with:

```html
                <!-- Contact Information Section -->
                <tr>
                    <td colspan="2"><h3>Contact Information</h3></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Address:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{address}" />
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">City:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{city}" />
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">State:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{state}" />
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Zip Code:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{zipCode}" />
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Home Phone:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{homePhone}" />
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Work Phone:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{workPhone}" />
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Cell Phone:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{cellPhone}" />
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Pager:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="text" size="30" th:field="*{pager}" />
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">E-mail:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="email" size="30" th:field="*{email}" />
                        <span th:if="${#fields.hasErrors('email')}" th:errors="*{email}" style="color: red;"></span>
                    </font></td>
                </tr>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit contact information**

```bash
git add src/main/resources/templates/update-info.html
git commit -m "feat: add contact information section to UpdateInfo template"
```

---

## Task 9: Add Patrol Preferences, Security, and Read-Only Sections

**Files:**
- Modify: `src/main/resources/templates/update-info.html`

- [ ] **Step 1: Replace patrol preferences comment**

Replace the `<!-- Patrol Preferences Section - next step -->` comment with:

```html
                <!-- Patrol Preferences Section -->
                <tr>
                    <td colspan="2"><h3>Patrol Preferences</h3></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Emergency Call Up:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <select th:field="*{emergencyCallUp}" class="select">
                            <option value="">No</option>
                            <option value="day">Day Only</option>
                            <option value="night">Night Only</option>
                            <option value="both">Both (Days & Nights)</option>
                        </select>
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Substitute List:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <select th:field="*{substituteList}" class="select">
                            <option value="">No</option>
                            <option value="yes">Yes</option>
                        </select>
                    </font></td>
                </tr>
```

- [ ] **Step 2: Replace security section comment**

Replace the `<!-- Security Section - next step -->` comment with:

```html
                <!-- Security Section -->
                <tr>
                    <td colspan="2"><h3>Security</h3></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Reset Password to:&nbsp;<br>
                        <i>(Leave blank to keep current password)</i></font></td>
                    <td width="600" valign="left"><font size="3">
                        <input type="password" size="30" th:field="*{password}" placeholder="Leave blank to keep current password" />
                    </font></td>
                </tr>
```

- [ ] **Step 3: Replace read-only information comment**

Replace the `<!-- Read-Only Information Section - next step -->` comment with:

```html
                <!-- Read-Only Information Section -->
                <tr>
                    <td colspan="2"><h3>Read-Only Information</h3></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">ID Number:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <span th:text="${idNumber}">ID</span>
                        <i>(Can only be changed by a director)</i>
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Classification:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <span th:text="${classification}">Classification</span>
                        <i>(Can only be changed by a director)</i>
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Commitment:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <span th:text="${commitment}">Commitment</span>
                        <i>(Can only be changed by a director)</i>
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Instructor:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <span th:text="${instructor}">Instructor</span>
                        <i>(Can only be changed by a director)</i>
                    </font></td>
                </tr>
                <tr>
                    <td width="351" valign="middle"><font size="3">Last Updated:&nbsp;</font></td>
                    <td width="600" valign="left"><font size="3">
                        <span th:text="${lastUpdated}">Last Updated</span>
                    </font></td>
                </tr>
```

- [ ] **Step 4: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit preferences and read-only sections**

```bash
git add src/main/resources/templates/update-info.html
git commit -m "feat: add patrol preferences, security, and read-only sections to UpdateInfo template"
```

---

## Task 10: Add Hidden Fields to Preserve Read-Only Values

**Files:**
- Modify: `src/main/resources/templates/update-info.html`

- [ ] **Step 1: Replace hidden fields comment**

Replace the `<!-- Hidden fields - next step -->` comment with:

```html
                <!-- Hidden fields to preserve read-only values -->
                <tr style="display: none;">
                    <td colspan="2">
                        <input type="hidden" name="hiddenClassification" th:value="${hiddenClassification}" />
                        <input type="hidden" name="hiddenCommitment" th:value="${hiddenCommitment}" />
                        <input type="hidden" name="hiddenInstructor" th:value="${hiddenInstructor}" />
                        <input type="hidden" name="hiddenDirector" th:value="${hiddenDirector}" />
                        <input type="hidden" name="hiddenTeamLead" th:value="${hiddenTeamLead}" />
                        <input type="hidden" name="hiddenMentoring" th:value="${hiddenMentoring}" />
                        <input type="hidden" name="hiddenComments" th:value="${hiddenComments}" />
                    </td>
                </tr>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit hidden fields**

```bash
git add src/main/resources/templates/update-info.html
git commit -m "feat: add hidden fields to preserve read-only values in UpdateInfo template"
```

---

## Task 11: Build and Test Application

**Files:**
- Test: All UpdateInfo components

- [ ] **Step 1: Build the application**

```bash
mvn clean package -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Start the application**

```bash
java -jar target/calendar-1.jar
```

Expected:
- Application starts on port 8080
- No errors in console
- "Started CalendarApplication in X seconds"

- [ ] **Step 3: Test UpdateInfo GET endpoint manually**

Open browser and navigate to:
```
http://localhost:8080/calendar-1/UpdateInfo?resort=Sample&ID=12345
```

Expected behavior:
- If not logged in: redirects to MemberLogin
- If logged in: displays update form with current data populated
- Page shows "Personal Information for: {Member Name}"
- All sections display: Personal, Contact, Patrol Preferences, Security, Read-Only
- Read-only fields show current values with "(Can only be changed by a director)" note
- Submit button displays

- [ ] **Step 4: Test form submission**

Fill out form with updated values and submit

Expected:
- Form validates (alerts if first/last name blank)
- After submission, success message displays: "Success! Your profile has been updated successfully."
- Form re-displays with updated values
- Database updated with new values

- [ ] **Step 5: Test validation errors**

Clear first name and submit form

Expected:
- JavaScript alert blocks submission
- If JavaScript disabled, server-side validation catches error
- Error message displays: "First name is required"
- Form re-displays with user's input preserved

- [ ] **Step 6: Test email validation**

Enter invalid email format and submit

Expected:
- Error message displays: "Invalid email format"
- Form re-displays

- [ ] **Step 7: Test password reset**

Enter new password and submit

Expected:
- Success message displays
- Database updated with new hashed password
- Can log in with new password

- [ ] **Step 8: Test backdoor user**

Test with backdoor user credentials

Expected:
- "Have a great day" message displays
- No form displays
- No errors

- [ ] **Step 9: Test authentication redirect**

Clear session/cookies and access UpdateInfo

Expected:
- Redirects to MemberLogin with resort parameter

- [ ] **Step 10: Stop the application**

Press Ctrl+C in terminal

Expected: Application shuts down cleanly

---

## Task 12: Update Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update migrated endpoints section**

Find the "### Migrated Endpoints" section in CLAUDE.md and add UpdateInfo:

```markdown
### Migrated Endpoints

- `/ListPatrollers` - Patrol member roster display (Spring Boot + Thymeleaf)
- `/MemberLogin` - Member login page with password reset (Spring Boot + Thymeleaf)
- `/VolunteerForm` - Public volunteer signup form (Spring Boot + Thymeleaf)
- `/MonthCalendar` - Monthly shift calendar (Spring Boot + Thymeleaf)
- `/Directors` - Director administration panel (Spring Boot + Thymeleaf)
- `/SubList` - Substitute patroller list (Spring Boot + Thymeleaf)
- `/MemberList` - All active members list (Spring Boot + Thymeleaf)
- `/ListAssignments` - Patroller shift assignments (Spring Boot + Thymeleaf)
- `/UpdateInfo` - Self-service profile updates (Spring Boot + Thymeleaf)
```

- [ ] **Step 2: Verify CLAUDE.md syntax**

Read through the updated section to ensure formatting is correct.

- [ ] **Step 3: Commit documentation update**

```bash
git add CLAUDE.md
git commit -m "docs: add UpdateInfo to migrated endpoints in CLAUDE.md"
```

---

## Task 13: Final Verification and Tag

**Files:**
- All project files

- [ ] **Step 1: Run full build with tests**

```bash
mvn clean package
```

Expected: BUILD SUCCESS with all tests passing

- [ ] **Step 2: Verify git status**

```bash
git status
```

Expected: Working tree clean (all changes committed)

- [ ] **Step 3: Review commit history**

```bash
git log --oneline -13
```

Expected: See all UpdateInfo migration commits in order

- [ ] **Step 4: Create summary of changes**

Create a summary of what was accomplished:
- UpdateInfoController.java created with MemberUpdateForm model
- update-info.html Thymeleaf template created with organized sections
- GET mapping displays form with current data
- POST mapping processes updates with validation
- Spring form binding and validation
- JavaScript client-side validation
- Password reset functionality (blank = keep, value = hash and update)
- Read-only field preservation
- Backdoor user handling
- Success/error message display
- Documentation updated

- [ ] **Step 5: Optional - Create git tag**

```bash
git tag -a updateinfo-migration-v1 -m "UpdateInfo Spring Boot migration complete"
```

---

## Success Criteria Checklist

Verify all requirements from the spec are met:

- [ ] UpdateInfo accessible at `/UpdateInfo?resort={resort}&ID={id}`
- [ ] Form displays with current member data populated
- [ ] Can edit: first/last name, spouse, contact info, emergency call-up, substitute list, password
- [ ] Read-only display: ID, classification, commitment, instructor, last updated
- [ ] First name and last name validation (client and server side)
- [ ] Email format validation if provided
- [ ] Password reset functionality (blank = keep current, value = hash and update)
- [ ] Success message displays after successful update
- [ ] Database updated with new values
- [ ] Read-only fields preserved (not overwritten)
- [ ] Redirects to login if session invalid
- [ ] Backdoor user shows friendly message
- [ ] Styling consistent with SubList, MemberList, ListAssignments
- [ ] Header and footer fragments render correctly
- [ ] Background image displays
- [ ] Legacy servlet coexists without conflicts
- [ ] JavaScript validation prevents blank name submission
- [ ] Validation errors display with preserved form input
- [ ] Email validation displays appropriate error message

---

## Rollback Plan

If issues are discovered after deployment:

1. **Immediate rollback:**
   ```bash
   git revert HEAD~13..HEAD
   git push
   ```

2. **Selective rollback (if only template needs fixing):**
   ```bash
   git revert <commit-hash-of-template-change>
   ```

3. **Return to legacy servlet:**
   - Remove @GetMapping and @PostMapping from UpdateInfoController
   - Legacy servlet will handle /UpdateInfo requests again

---

## Notes

- **ServletAdapter Pattern:** Used to bridge jakarta.servlet (Spring Boot 3) to javax.servlet (legacy code)
- **Resource Cleanup:** PatrolData.close() called in finally block to ensure connection cleanup
- **Form Binding:** Spring automatically binds form fields to MemberUpdateForm object
- **Validation:** Both client-side (JavaScript) and server-side (Spring Validation) for robust error handling
- **Password Handling:** Only updates if non-blank value provided; uses SaltUtils for hashing
- **Read-Only Fields:** Displayed but not editable; preserved via hidden fields but ignored during update
- **Backdoor User:** Special case that shows friendly message and skips form display
- **Success Feedback:** updateSuccess flag triggers success message display
- **Error Feedback:** BindingResult errors and errorMessage attribute display validation/database errors
