# MemberList Spring Boot Migration Design

**Date:** 2026-05-27  
**Author:** Claude (via brainstorming session)  
**Status:** Approved

## Overview

Migrate the MemberList servlet to a Spring MVC controller with Thymeleaf template. MemberList displays all active patrol members with their contact information and provides an email button for directors or users with email-all permission.

## Goals

- Convert MemberList servlet to Spring MVC controller
- Create Thymeleaf template with styling consistent with SubList
- Maintain existing functionality: display all active members and email button
- Use existing patterns from SubList and other migrated controllers

## Architecture

### Controller: MemberListController.java

**Package:** `org.nsponline.calendar.controller`

**Endpoint:** `@GetMapping("/MemberList")`

**Responsibilities:**
- Validate resort parameter and user session
- Query roster for all active members (commitment != "0")
- Check director status and emailAll permission
- Build list of member contact information
- Generate comma-separated email list for EmailForm button
- Populate model and render template

**Request Parameters:**
- `resort` (required) - Resort identifier
- `ID` (required) - Logged-in user's patroller ID

**Model Attributes:**
- `resort` (String) - Resort short name
- `resortFullName` (String) - Resort display name
- `patrollerId` (String) - Logged-in user ID
- `members` (List<MemberInfo>) - List of active patrol members
- `memberCount` (int) - Total count of active members
- `showEmailButton` (boolean) - True if director OR emailAll enabled
- `emailFormUrl` (String) - URL for "e-mail THESE patrollers" button
- `resortUrl`, `resortImage`, `resortImgHeight` (String/int) - For header

### Model Class: MemberInfo

**Location:** Inner static class in MemberListController

**Fields:**
- `name` (String) - Full name in "Last, First" format
- `homePhone` (String) - Home phone number
- `workPhone` (String) - Work phone number
- `cellPhone` (String) - Cell phone number
- `email` (String) - Email address

**Methods:**
- Standard getters
- Constructor accepting all fields

### Template: member-list.html

**Location:** `src/main/resources/templates/member-list.html`

**Structure:**
```
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  - Title: "List of Members of {resortFullName} Ski Patrollers"
  - CSS styles matching sub-list.html
  - Background image: ncmnthbk.jpg
</head>
<body>
  - Header fragment with resort info and navigation
  - Page title
  - "e-mail THESE patrollers" button (conditional)
  - Table with members
  - Member count display
  - Footer fragment
</body>
</html>
```

**Table Columns:**
1. Name - Last, First format
2. Home - Home phone
3. Work - Work phone
4. Cell - Cell phone
5. Email - Mailto link (if valid email)

**Styling:**
- Use same table styles as sub-list.html for consistency
- Font size: 10pt
- Border: solid 1px with border-collapse
- Header background: #C0C0C0
- Empty cells render as empty (no "&nbsp;")

## Data Flow

### Request Processing

1. User navigates to `/MemberList?resort=Sample&ID=12345`
2. Controller receives request via Spring DispatcherServlet
3. Extract `resort` and `ID` parameters
4. Validate resort using `PatrolData.isValidResort(resort)`
5. If invalid resort → return 400 error
6. Use ServletAdapter to create javax.servlet request for legacy code
7. Initialize Logger, SessionData, PatrolData with resort
8. Use `ValidateCredentialsRedirectIfNeeded` for session validation
9. If user not logged in → redirect to `MemberLogin?resort={resort}`
10. If logged in → proceed to load data

### Data Loading

1. Read DirectorSettings: `patrolData.readDirectorSettings()`
2. Get logged-in user: `patrolData.getMemberByID(patrollerId)`
3. Check if director: `editor.isDirector()`
4. Call `patrolData.resetRoster()` to get ResultSet
5. Iterate through roster with `patrolData.nextMember("&nbsp;", rosterResults)`
6. For each member:
   - Check if active: skip if `member.getCommitment().equals("0")`
   - If active, create MemberInfo with:
     - name: `member.getFullName_lastNameFirst()`
     - homePhone: `cleanField(member.getHomePhone())`
     - workPhone: `cleanField(member.getWorkPhone())`
     - cellPhone: `cleanField(member.getCellPhone())`
     - email: `cleanField(member.getEmailAddress())`
   - Add to members list
   - Increment member count
7. Build email list in same pass:
   - For each active member with valid email
   - Check if email is valid using `StaticUtils.isValidEmailAddress()`
   - If valid, add to comma-separated email list string

### Email List Building

Single pass through roster to build both member list and email list:
1. For each active member (commitment != "0"):
   - Add to members list
   - Check if email is valid using `StaticUtils.isValidEmailAddress()`
   - Email must contain '@' and '.', length > 6
   - If valid, add to comma-separated email list string
2. Build EmailForm URL with default options:
   - `EmailForm?resort={resort}&ID={patrollerId}&BAS=1&INA=1&SR=1&SRA=1&ALM=1&PRO=1&AUX=1&TRA=1&CAN=1&FullTime=1&PartTime=1&Inactive=1&ALL=1`
3. Determine email button visibility: `isDirector OR ds.getEmailAll()`
4. Add all attributes to model

### Template Rendering

1. Thymeleaf processes model attributes
2. Render header fragment with resort parameter
3. Display page title: "List of Members of {resortFullName} Ski Patrollers"
4. Conditionally render email button using `th:if="${showEmailButton}"`
5. Generate table:
   - Fixed headers: Name | Home | Work | Cell | Email
   - Loop through members list
   - For each member, render row with contact info
   - Email column: use `th:if` to conditionally show mailto link
   - Empty fields: cleaned by `cleanField()`, render as blank
6. Display member count: "As of: {date}, {memberCount} members listed."
7. Render footer fragment
8. Return HTML to browser

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

### Empty Member List
- If no active members (all have commitment == "0"):
  - Display table headers
  - Show message: "No active members"
  - Hide email button (showEmailButton = false)
  - Display "0 members listed"
  - Template uses `th:if="${members.isEmpty()}"` for empty state

### Email Validation
- Use `StaticUtils.isValidEmailAddress()` to validate each email
- Invalid emails:
  - Display in table as plain text (no mailto link)
  - Exclude from email list for EmailForm button
- Template logic: `<a th:if="${member.email != null and member.email != ''}" th:href="'mailto:' + ${member.email}">`

### Database Connection Errors
- PatrolData throws IOException on connection failure
- Controller method declares `throws IOException`
- Spring's default error handling catches and displays error page
- Log exception via Logger before propagating
- PatrolData closed in finally block ensures connection cleanup

### Edge Cases
- Missing contact info: cells render empty (no "&nbsp;" placeholder)
- Member with no email: name and phones display, email cell empty
- All members have invalid emails: email button hidden if not director
- Director with no members: email button visible but EmailForm shows empty list

## Implementation Notes

### ServletAdapter Pattern
Follow existing pattern from SubList and other migrated controllers:
```java
javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
Logger LOG = new Logger(MemberListController.class, javaxRequest, "GET", resort, Logger.INFO);
SessionData sessionData = new SessionData(javaxRequest, null, LOG);
PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
```

### Field Cleaning Helper
```java
private String cleanField(String value) {
    if (value == null || value.trim().isEmpty() || "&nbsp;".equals(value.trim())) {
        return "";
    }
    return value.trim();
}
```

### Resource Management
- PatrolData connection closed in finally block
- Follow pattern from SubList:
```java
try {
    // Load data and populate model
    return "member-list";
} finally {
    patrolData.close();
}
```

### Email Button Access Control
- Button visible if `isDirector` OR `directorSettings.getEmailAll()` is true
- Check both conditions:
  1. Get logged-in user: `patrolData.getMemberByID(patrollerId)`
  2. Check director: `editor.isDirector()`
  3. Read settings: `patrolData.readDirectorSettings()`
  4. Check emailAll: `ds != null && ds.getEmailAll()`
- Model attribute: `showEmailButton = isDirector || (ds != null && ds.getEmailAll())`

### EmailForm URL Format
Default options match legacy servlet:
```
EmailForm?resort={resort}&ID={patrollerId}&BAS=1&INA=1&SR=1&SRA=1&ALM=1&PRO=1&AUX=1&TRA=1&CAN=1&FullTime=1&PartTime=1&Inactive=1&ALL=1
```

These options represent classification filters:
- BAS=1: Basic
- INA=1: Inactive (for email button, includes all)
- SR=1: Senior
- SRA=1: Senior Auxiliary
- ALM=1: Alumni
- PRO=1: Professional
- AUX=1: Auxiliary
- TRA=1: Transfer
- CAN=1: Candidate
- FullTime=1, PartTime=1, Inactive=1: Commitment types
- ALL=1: Include all

### Single-Pass Optimization
Build member list and email list in single roster iteration:
1. Initialize both `List<MemberInfo> members` and `StringBuilder emailList`
2. For each active member:
   - Create MemberInfo and add to members list
   - If valid email, append to emailList with comma separator
3. More efficient than two separate passes through roster

## Testing Plan

### Manual Testing Checklist

**Basic Functionality:**
1. Navigate to `/MemberList?resort=Sample&ID={validId}`
2. Verify member list displays correctly
3. Check that inactive members (commitment == "0") are excluded
4. Verify contact info (name, phones, email) displays properly
5. Verify table styling matches sub-list.html
6. Check member count displays at bottom

**Email Functionality:**
1. Login as director
2. Verify "e-mail THESE patrollers" button is visible
3. Click button
4. Verify redirects to EmailForm with correct parameters and options
5. Check that URL includes all filter options (BAS=1, INA=1, etc.)

**Email Permission Testing:**
1. Login as non-director
2. If emailAll disabled: button should be hidden
3. If emailAll enabled: button should be visible
4. Verify EmailForm receives correct email list

**Authentication:**
1. Access MemberList without valid session
2. Verify redirects to MemberLogin with resort parameter
3. Login with valid credentials
4. Retry MemberList access → should display list

**Edge Cases:**
1. Resort with all inactive members → displays empty message and member count = 0
2. Member with missing phone numbers → cells render empty
3. Member with invalid email → displays as text, not mailto link
4. Member with valid email → displays as clickable mailto link
5. Multiple active members → all display in table correctly
6. Check that inactive members (commitment == "0") are excluded

**Visual Consistency:**
1. Header fragment renders with resort logo and navigation
2. Footer fragment renders correctly
3. Table styling matches sub-list.html
4. Background image displays (ncmnthbk.jpg)
5. Font sizes consistent (10pt body text)
6. Layout responsive and clean on different browser sizes

### Integration Testing
- Not required for initial migration
- Can add Selenium tests later if needed
- Follow existing pattern: integration tests deferred

### Coexistence Verification
1. Legacy MemberList servlet remains registered in web.xml
2. New Spring controller takes precedence at `/MemberList` path
3. Other servlets continue working
4. No conflicts with existing endpoints

## Files to Create/Modify

### New Files
1. `src/main/java/org/nsponline/calendar/controller/MemberListController.java`
   - Spring MVC controller with @GetMapping
   - MemberInfo inner class
   - cleanField() helper method

2. `src/main/resources/templates/member-list.html`
   - Thymeleaf template
   - Uses fragments/header and fragments/footer
   - Table with member contact info
   - Member count display

### Modified Files
None - this is a new controller, no existing files modified

### Files to Reference
1. `src/main/java/org/nsponline/calendar/resources/MemberList.java` (original servlet)
2. `src/main/java/org/nsponline/calendar/controller/SubListController.java` (pattern reference)
3. `src/main/resources/templates/sub-list.html` (styling reference)
4. `src/main/resources/templates/fragments/header.html` (header fragment)
5. `src/main/resources/templates/fragments/footer.html` (footer fragment)
6. `src/main/java/org/nsponline/calendar/store/Roster.java` (printEmergencyCallRow method for reference)

## Migration Pattern Consistency

This migration follows the established pattern from:
- **SubListController:** Session validation, resort handling, model population, single-pass roster iteration
- **DirectorsController:** Director permission checking, settings access
- **ListPatrollersController:** Email list building, permission-based button visibility
- **MemberLoginController:** Thymeleaf template structure

Key consistencies:
- ServletAdapter for jakarta→javax bridge
- Logger, SessionData, PatrolData initialization
- ValidateCredentialsRedirectIfNeeded for session validation
- Thymeleaf fragments for header/footer
- Model attributes for template rendering
- Try-finally for resource cleanup
- cleanField() helper for "&nbsp;" filtering
- Single-pass roster iteration for efficiency

## Success Criteria

1. MemberList accessible at `/MemberList?resort={resort}&ID={id}`
2. Displays all active members (commitment != "0")
3. Excludes inactive members (commitment == "0")
4. Shows contact info: name, home/work/cell phones, email
5. Email button visible for directors OR users with emailAll permission
6. Email button links to EmailForm with correct parameters and options
7. Empty cells render as empty (no "&nbsp;")
8. Email addresses are clickable mailto links
9. Invalid emails display as plain text
10. Redirects to login if session invalid
11. Member count displays at bottom: "As of: {date}, {count} members listed."
12. Styling consistent with sub-list.html
13. Header and footer fragments render correctly
14. Background image displays
15. Legacy servlet coexists without conflicts

## Future Enhancements (Out of Scope)

- Sorting by name, classification, or commitment
- Filtering by classification type
- Export to CSV or PDF
- Search/filter functionality
- Pagination for large rosters
- Display additional member info (classification, commitment)
- Inline email composition
- Mobile-responsive layout improvements
- Print stylesheet for better printing
