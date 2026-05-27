# UpdateInfo Spring Boot Migration Design

**Date:** 2026-05-27  
**Author:** Claude (via brainstorming session)  
**Status:** Approved

## Overview

Migrate the UpdateInfo servlet to a Spring MVC controller with Thymeleaf template. This migration focuses on the **self-service profile update workflow** - allowing patrollers to update their own contact information and preferences. Director operations (add/edit/delete members) will be handled in a separate future migration.

## Goals

- Convert UpdateInfo servlet to Spring MVC controller with separate GET/POST mappings
- Create Thymeleaf template with organized form layout
- Implement Spring form binding and validation
- Maintain existing field visibility rules (read-only vs editable)
- Preserve client-side JavaScript validation
- Support password reset functionality
- Use existing patterns from SubList, MemberList, and ListAssignments migrations

## Scope

**In Scope:**
- Self-service profile updates (patrollers editing their own info)
- Form display (GET) and submission (POST)
- Editable fields: personal info, contact info, preferences, password
- Read-only display: classification, commitment, instructor, director status
- Backdoor user handling
- Success confirmation after update

**Out of Scope (Future Migrations):**
- Director operations (add new patroller, edit other members, delete members)
- ID number changes
- Classification/commitment changes (directors only)
- Instructor certification changes (directors only)
- Director status changes (directors only)
- Comments field (directors only)

## Architecture

### Controller: UpdateInfoController.java

**Package:** `org.nsponline.calendar.controller`

**Endpoints:**

1. **GET `/UpdateInfo`** - Display update form
   - Parameters: `resort` (required), `ID` (required)
   - Returns: `update-info.html` template

2. **POST `/UpdateInfo`** - Process form submission
   - Parameters: `resort`, `ID`, form fields via `@ModelAttribute`
   - Returns: `update-info.html` with success message

**Responsibilities:**
- Validate resort parameter and user session
- Load member data from database
- Check backdoor user status
- Populate form model for GET requests
- Process form submission for POST requests
- Validate input data (server-side)
- Update database record
- Display success/error messages

**Request Parameters:**
- `resort` (required) - Resort identifier
- `ID` (required) - Logged-in user's patroller ID

**Model Attributes:**
- `resort` (String) - Resort short name
- `resortFullName` (String) - Resort display name
- `patrollerId` (String) - Logged-in user ID
- `memberName` (String) - Full name of member
- `isBackdoorUser` (boolean) - True if backdoor user
- `memberForm` (MemberUpdateForm) - Form data object
- `updateSuccess` (boolean) - True if POST succeeded
- `errorMessage` (String) - Error message if validation failed
- `resortUrl`, `resortImage`, `resortImgHeight` (String/int) - For header

### Model Class: MemberUpdateForm

**Location:** Inner static class in UpdateInfoController

**Editable Fields:**
- `firstName` (String) - First name (required, validation: not blank)
- `lastName` (String) - Last name (required, validation: not blank)
- `spouse` (String) - Spouse name
- `address` (String) - Street address
- `city` (String) - City
- `state` (String) - State
- `zipCode` (String) - Zip code
- `homePhone` (String) - Home phone number
- `workPhone` (String) - Work phone number
- `cellPhone` (String) - Cell phone number
- `pager` (String) - Pager number
- `email` (String) - Email address (validation: valid email format if provided)
- `emergencyCallUp` (String) - Emergency call-up preference: "" / "day" / "night" / "both"
- `substituteList` (String) - Substitute list opt-in: "" / "yes"
- `password` (String) - New password (optional, blank = keep current)

**Read-Only Fields (display only, not in form model):**
- `idNumber` (String) - Member ID
- `classification` (String) - Classification code (BAS, SR, AUX, etc.)
- `commitment` (String) - Commitment level: "0" / "1" / "2"
- `instructor` (String) - Instructor certifications (bit flags)
- `director` (String) - Director status
- `teamLead` (String) - Team lead trained (Brighton only)
- `mentoring` (String) - Currently being mentored (Brighton only)
- `lastUpdated` (String) - Last update timestamp
- `comments` (String) - Private comments (directors only)

**Methods:**
- Constructor accepting all editable fields
- Standard getters and setters
- `populateFromRoster(Roster roster)` - Helper to populate form from Roster object
- `applyToRoster(Roster roster)` - Helper to apply form values to Roster object

### Template: update-info.html

**Location:** `src/main/resources/templates/update-info.html`

**Structure:**
```
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  - Title: "Update Personal Information for {memberName}"
  - CSS styles matching other templates
  - JavaScript validation for required fields
  - Background image: ncmnthbk.jpg
</head>
<body>
  - Header fragment with resort info and navigation
  - Page title: "Update Personal Information for: {memberName}"
  - Resort name display
  
  - Backdoor user section (conditional)
    * "Have a great day" message
  
  - Main form (non-backdoor users)
    * Success message (if updateSuccess)
    * Form tag: POST to /UpdateInfo with resort and ID
    * Personal Information section
      - First Name (text input, required)
      - Last Name (text input, required)
      - Spouse (text input)
    * Contact Information section
      - Address (text input)
      - City (text input)
      - State (text input)
      - Zip Code (text input)
      - Home Phone (text input)
      - Work Phone (text input)
      - Cell Phone (text input)
      - Pager (text input)
      - Email (text input, email validation)
    * Patrol Preferences section
      - Emergency Call Up (select dropdown)
      - Substitute List (select dropdown)
    * Security section
      - Password (password input, optional)
    * Read-Only Information section
      - ID Number (display text)
      - Classification (display text)
      - Commitment (display text with label: Full Time / Part Time / Inactive)
      - Instructor (display checkboxes as text: OEC, CPR, Ski, Toboggan)
      - Last Updated (display text)
    * Hidden fields for read-only values
    * Submit button: "Update Profile"
  
  - Footer fragment
</body>
</html>
```

**Field Rendering Rules:**

**Text Inputs:**
- First Name, Last Name, Spouse
- Address, City, State, Zip Code
- Home Phone, Work Phone, Cell Phone, Pager
- Email (with type="email" for HTML5 validation)

**Select Dropdowns:**
- Emergency Call Up:
  - "" → "No"
  - "day" → "Day Only"
  - "night" → "Night Only"
  - "both" → "Both (Days & Nights)"
  
- Substitute List:
  - "" → "No"
  - "yes" → "Yes"

**Password Field:**
- Type: password
- Placeholder: "Leave blank to keep current password"
- Only updates if non-empty value submitted

**Read-Only Display:**
- ID Number: Display as text
- Classification: Display full name (e.g., "Basic", "Senior")
- Commitment: Display as "Full Time" / "Part Time" / "Inactive"
- Instructor: Display as text list "OEC, CPR, Ski, Toboggan" based on bit flags
- Last Updated: Display as text
- Note: "(Can only be changed by a director)" appears next to read-only fields

**Hidden Fields:**
Pass read-only values to preserve them on form submission:
- `idNumber`
- `classification`
- `commitment`
- `instructor`
- `director`
- `teamLead`
- `mentoring`
- `lastUpdated`
- `comments`

**Styling:**
- Table-based layout: `<table border="0" width="900">`
- Each field row: `<tr><td>Label</td><td>Input</td></tr>`
- Label column width: 351px
- Input column width: 600px
- Font size: 10pt body text, size 3 for labels
- Consistent with SubList, MemberList, ListAssignments styling

## Data Flow

### GET Request Processing

1. User navigates to `/UpdateInfo?resort=Sample&ID=12345`
2. Controller receives request via Spring DispatcherServlet
3. Extract `resort` and `ID` parameters
4. Validate resort using `PatrolData.isValidResort(resort)`
5. If invalid resort → return 400 error
6. Use ServletAdapter to create javax.servlet request for legacy code
7. Initialize Logger, SessionData, PatrolData with resort
8. Use `ValidateCredentialsRedirectIfNeeded` for session validation
9. If user not logged in → redirect to `MemberLogin?resort={resort}`
10. If logged in → proceed to load data
11. Check if backdoor user via `sessionData.getBackDoorUser()`
12. If backdoor user:
    - Set `isBackdoorUser = true`
    - Add minimal model attributes
    - Return template (shows "Have a great day")
13. If normal user:
    - Load member data: `patrolData.getMemberByID(patrollerId)`
    - Check if member is director: `member.isDirector()`
    - Create `MemberUpdateForm` and populate from `Roster` data
    - Add all model attributes
    - Return template (shows form)

### POST Request Processing

1. User submits form from `/UpdateInfo`
2. Controller receives POST with form data
3. Validate resort and session (same as GET)
4. Spring binds form fields to `MemberUpdateForm` via `@ModelAttribute`
5. Run server-side validation:
   - Check firstName is not blank
   - Check lastName is not blank
   - If email provided, validate format
   - If password provided, validate strength (optional)
6. If validation fails:
   - Set error messages in model
   - Re-render form with errors
   - Return early
7. If validation passes:
   - Load existing member record: `patrolData.getMemberByID(patrollerId)`
   - Apply form values to Roster object via `memberForm.applyToRoster(roster)`
   - Keep read-only fields unchanged
   - Handle password specially:
     * If password field is blank → keep existing password hash
     * If password field has value → hash and update
   - Generate SQL update: `roster.getUpdateSQLString(resort, sessionData)`
   - Execute database update
   - Set `updateSuccess = true` in model
   - Re-render form with success message

### Field Update Logic

**Editable Fields (always update from form):**
- Personal: firstName, lastName, spouse
- Contact: address, city, state, zipCode, homePhone, workPhone, cellPhone, pager, email
- Preferences: emergencyCallUp, substituteList

**Password Handling:**
- If form password is blank or null → keep existing `newPassword` hash from database
- If form password has value → hash it using `SaltUtils` and update `newPassword` field
- Note: The `password` field in database is legacy; actual hash is in `newPassword` field

**Read-Only Fields (never update from form, preserve database values):**
- idNumber, classification, commitment, instructor, director, teamLead, mentoring, comments
- These are passed as hidden fields but ignored during update
- Database values are preserved by not including them in UPDATE statement

**Auto-Updated Fields:**
- `lastUpdated` - Set to current timestamp during update via `Roster.getUpdateSQLString()`

## Error Handling

### Session Validation
- `ValidateCredentialsRedirectIfNeeded` handles session validation
- If `hasInvalidCredentials()` returns true:
  - Redirect to `MemberLogin?resort={resort}` already sent
  - Return null from controller method
  - PatrolData closed before return

### Invalid Resort
- `PatrolData.isValidResort(resort)` validates resort parameter
- If invalid:
  - Call `response.sendError(400, "Resort not found: " + resort)`
  - Return null from controller method

### Validation Errors
- Spring validation catches invalid input
- Errors added to `BindingResult`
- Template displays errors using `th:if="${#fields.hasErrors('fieldName')}"`
- Form re-displays with user's input preserved
- Error messages: "First name is required", "Last name is required", "Invalid email format"

### Database Errors
- PatrolData throws IOException on connection failure
- Controller method declares `throws IOException`
- Spring's default error handling catches and displays error page
- Log exception via Logger before propagating
- PatrolData closed in finally block ensures connection cleanup

### Backdoor User
- If backdoor user detected:
  - Display friendly message: "Have a great day"
  - No form displayed
  - No database operations
  - No errors

### Edge Cases
- Member not found: Display error "Internal Error, invalid patroller number"
- Empty form submission: Validation errors prevent save
- Invalid email format: Display error, re-render form
- Database connection timeout: Log error, display generic error page

## Validation Rules

### Server-Side (Spring Validation)

**Required Fields:**
- `firstName` - Must not be blank
  - Validation: `@NotBlank(message = "First name is required")`
  
- `lastName` - Must not be blank
  - Validation: `@NotBlank(message = "Last name is required")`

**Conditional Validation:**
- `email` - If provided, must be valid email format
  - Validation: `@Email(message = "Invalid email format")`
  - Custom: Allow blank/null (email is optional)

**Optional Fields:**
- All other fields are optional (can be blank)
- No minimum/maximum length validation (matches legacy behavior)

### Client-Side (JavaScript)

**Validation Function:**
```javascript
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
```

**Form Submission:**
- Attach validation to form: `<form onsubmit="return validate()">`
- Prevents submission if validation fails
- Displays alert messages to user

**Additional HTML5 Validation:**
- Email field: `<input type="email">` provides browser validation
- Required fields: Consider adding `required` attribute for HTML5 support

## Implementation Notes

### ServletAdapter Pattern
Follow existing pattern from SubList, MemberList, ListAssignments:
```java
javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
Logger LOG = new Logger(UpdateInfoController.class, javaxRequest, "GET", resort, Logger.INFO);
SessionData sessionData = new SessionData(javaxRequest, null, LOG);
PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
```

### Form Binding
Use Spring's `@ModelAttribute` for automatic form binding:
```java
@PostMapping("/UpdateInfo")
public String updateProfile(
    HttpServletRequest request,
    HttpServletResponse response,
    @ModelAttribute("memberForm") @Valid MemberUpdateForm form,
    BindingResult bindingResult,
    Model model) throws IOException {
  // Form automatically bound to MemberUpdateForm object
  // Validation errors in bindingResult
}
```

### Password Handling
```java
// In applyToRoster method
if (password != null && !password.trim().isEmpty()) {
    // User provided new password - hash it
    try {
        String hashedPassword = SaltUtils.hashPassword(password);
        roster.memberData[Roster.NEW_PASSWORD] = hashedPassword;
    } catch (NoSuchAlgorithmException e) {
        LOG.error("Error hashing password", e);
        // Keep old password
    }
}
// If password is blank/null, don't modify roster.memberData[NEW_PASSWORD]
```

### Resource Management
- PatrolData connection closed in finally block
- Follow pattern from ListAssignments:
```java
try {
    // Load data and process form
    return "update-info";
} finally {
    patrolData.close();
}
```

### Field Visibility Helper
```java
private boolean isDirectorOnlyField(int fieldIndex) {
    switch (fieldIndex) {
        case Roster.CLASSIFICATION:
        case Roster.COMMITMENT:
        case Roster.INSTRUCTOR:
        case Roster.DIRECTOR:
        case Roster.TEAM_LEAD:
        case Roster.COMMENTS:
            return true;
        default:
            return false;
    }
}
```

### Commitment String Conversion
```java
private String getCommitmentLabel(String value) {
    if ("2".equals(value)) return "Full Time";
    if ("1".equals(value)) return "Part Time";
    return "Inactive";
}
```

### Instructor Flags Parsing
```java
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
```

### Resort-Specific Fields
Brighton resort has additional fields (teamLead, mentoring). Handle conditionally:
```java
if (!resort.equalsIgnoreCase("Brighton")) {
    // Hide teamLead and mentoring fields
}
```

## Testing Plan

### Manual Testing Checklist

**Basic Functionality:**
1. Navigate to `/UpdateInfo?resort=Sample&ID={validId}`
2. Verify form displays with current member data populated
3. Update first name, last name, email
4. Submit form
5. Verify success message displays
6. Verify database updated with new values
7. Refresh page - verify updated values persist

**Field Validation:**
1. Clear first name, submit form → error message displays
2. Clear last name, submit form → error message displays
3. Enter invalid email format → error message displays
4. Enter valid data → form submits successfully

**Password Reset:**
1. Leave password blank → verify old password hash preserved
2. Enter new password → verify new hash generated and saved
3. Verify can log in with new password

**Read-Only Fields:**
1. Verify classification displays but not editable
2. Verify commitment displays with label (Full Time / Part Time / Inactive)
3. Verify instructor certifications display as text (OEC, CPR, etc.)
4. Verify ID number displays but not editable

**Authentication:**
1. Access UpdateInfo without valid session
2. Verify redirects to MemberLogin with resort parameter
3. Login with valid credentials
4. Retry UpdateInfo access → should display form

**Backdoor User:**
1. Test with backdoor user credentials
2. Verify "Have a great day" message displays
3. Verify no form displays
4. Verify no database operations

**Error Handling:**
1. Test with invalid resort parameter → 400 error
2. Test with invalid patroller ID → error message
3. Test database connection failure → error page

**Client-Side Validation:**
1. Clear first name, submit → JavaScript alert blocks submission
2. Clear last name, submit → JavaScript alert blocks submission
3. Enter valid data → form submits (no alert)

**Resort-Specific:**
1. Test with Brighton resort → verify teamLead/mentoring fields hidden
2. Test with non-Brighton resort → verify fields not displayed

### Integration Testing
- Not required for initial migration
- Can add Selenium tests later if needed
- Follow existing pattern: integration tests deferred

### Coexistence Verification
1. Legacy UpdateInfo servlet remains registered in web.xml
2. New Spring controller takes precedence at `/UpdateInfo` path
3. Other servlets continue working
4. No conflicts with existing endpoints

## Files to Create/Modify

### New Files
1. `src/main/java/org/nsponline/calendar/controller/UpdateInfoController.java`
   - Spring MVC controller with @GetMapping and @PostMapping
   - MemberUpdateForm inner class
   - Helper methods for field visibility, label conversion

2. `src/main/resources/templates/update-info.html`
   - Thymeleaf template
   - Uses fragments/header and fragments/footer
   - Form with organized field sections
   - JavaScript validation
   - Success/error message display

### Modified Files
None - this is a new controller, no existing files modified

### Files to Reference
1. `src/main/java/org/nsponline/calendar/resources/UpdateInfo.java` (original servlet)
2. `src/main/java/org/nsponline/calendar/controller/SubListController.java` (pattern reference)
3. `src/main/java/org/nsponline/calendar/controller/MemberListController.java` (pattern reference)
4. `src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java` (pattern reference)
5. `src/main/resources/templates/member-list.html` (styling reference)
6. `src/main/java/org/nsponline/calendar/store/Roster.java` (data model reference)

## Migration Pattern Consistency

This migration follows the established pattern from:
- **SubListController:** Session validation, resort handling, model population
- **MemberListController:** Email list building, permission-based visibility
- **ListAssignmentsController:** Try-finally resource cleanup, backdoor user handling

Key consistencies:
- ServletAdapter for jakarta→javax bridge
- Logger, SessionData, PatrolData initialization
- ValidateCredentialsRedirectIfNeeded for session validation
- Thymeleaf fragments for header/footer
- Model attributes for template rendering
- Try-finally for resource cleanup
- Backdoor user early return pattern

**New Patterns Introduced:**
- Spring form binding with `@ModelAttribute`
- Server-side validation with Spring Validation
- Separate GET/POST mappings for form display and submission
- Form model class for data binding
- Success/error message handling in model

## Success Criteria

1. UpdateInfo accessible at `/UpdateInfo?resort={resort}&ID={id}`
2. Form displays with current member data populated
3. Can edit: first/last name, spouse, contact info, emergency call-up, substitute list, password
4. Read-only display: ID, classification, commitment, instructor, last updated
5. First name and last name validation (client and server side)
6. Email format validation if provided
7. Password reset functionality (blank = keep current, value = hash and update)
8. Success message displays after successful update
9. Database updated with new values
10. Read-only fields preserved (not overwritten)
11. Redirects to login if session invalid
12. Backdoor user shows friendly message
13. Styling consistent with SubList, MemberList, ListAssignments
14. Header and footer fragments render correctly
15. Background image displays
16. Legacy servlet coexists without conflicts
17. JavaScript validation prevents blank name submission

## Future Enhancements (Out of Scope)

These features will be addressed in future migrations:

**Director Operations (separate ManageMemberController):**
- Add new patroller
- Edit other members' profiles
- Delete patroller records
- Change member ID numbers
- Update classification
- Update commitment level
- Update instructor certifications
- Update director status
- Edit private comments

**Additional Features:**
- Profile photo upload
- Multi-step form wizard
- Mobile-responsive layout
- Real-time validation feedback
- Password strength meter
- Email verification
- Audit trail for profile changes
- Bulk import/export

## Security Considerations

**Authentication:**
- Session validation required for all requests
- Backdoor user handled specially (no sensitive data displayed)

**Authorization:**
- Users can only edit their own profile (ID from session)
- Read-only fields enforced server-side (not just UI)
- Director-only fields hidden and preserved

**Input Validation:**
- Server-side validation prevents malicious input
- Email format validation prevents injection
- SQL injection prevented by PreparedStatement usage in Roster class

**Password Security:**
- Passwords hashed using SaltUtils (bcrypt/SHA-256)
- Plain text passwords never stored
- Password field type="password" prevents shoulder surfing

**Data Integrity:**
- Read-only fields passed as hidden inputs but ignored during update
- Database update only touches editable fields
- Transaction safety via existing Roster.getUpdateSQLString() method
