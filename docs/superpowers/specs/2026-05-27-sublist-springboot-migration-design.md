# SubList Spring Boot Migration Design

**Date:** 2026-05-27  
**Author:** Claude (via brainstorming session)  
**Status:** Approved

## Overview

Migrate the SubList servlet to a Spring MVC controller with Thymeleaf template. SubList displays patrollers who have opted in as substitutes, showing their contact information and providing an email button to contact all substitutes.

## Goals

- Convert SubList servlet to Spring MVC controller
- Create Thymeleaf template with modern styling consistent with list-patrollers.html
- Maintain existing functionality: display substitute list and email button
- Use existing patterns from other migrated controllers (DirectorsController, ListPatrollersController)

## Architecture

### Controller: SubListController.java

**Package:** `org.nsponline.calendar.controller`

**Endpoint:** `@GetMapping("/SubList")`

**Responsibilities:**
- Validate resort parameter and user session
- Query roster for members with SUB='yes' or SUB='Yes'
- Build list of substitute contact information
- Generate comma-separated email list for EmailForm button
- Populate model and render template

**Request Parameters:**
- `resort` (required) - Resort identifier
- `ID` (required) - Logged-in user's patroller ID

**Model Attributes:**
- `resort` (String) - Resort short name
- `resortFullName` (String) - Resort display name
- `patrollerId` (String) - Logged-in user ID
- `substitutes` (List<SubstituteInfo>) - List of substitute patrollers
- `emailFormUrl` (String) - URL for "e-mail THESE patrollers" button

### Model Class: SubstituteInfo

**Location:** Inner static class in SubListController

**Fields:**
- `name` (String) - Full name in "Last, First" format
- `homePhone` (String) - Home phone number
- `workPhone` (String) - Work phone number
- `cellPhone` (String) - Cell phone number
- `email` (String) - Email address

**Methods:**
- Standard getters/setters
- Constructor accepting all fields

### Template: sub-list.html

**Location:** `src/main/resources/templates/sub-list.html`

**Structure:**
```
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  - Title: "Substitute List for {resortFullName}"
  - CSS styles matching list-patrollers.html
</head>
<body>
  - Header fragment
  - Page title
  - "e-mail THESE patrollers" button
  - Table with substitutes
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
- Use same table styles as list-patrollers.html for consistency
- Font size: 10pt
- Border: solid 1px with border-collapse
- Header background: #C0C0C0
- Empty cells render as empty (no "&nbsp;")

## Data Flow

### Request Processing

1. User navigates to `/SubList?resort=Sample&ID=12345`
2. Controller receives request via Spring DispatcherServlet
3. Extract `resort` and `ID` parameters
4. Use ServletAdapter to create javax.servlet request for legacy code
5. Initialize Logger, SessionData, PatrolData with resort
6. Validate session: get `sessionData.getLoggedInUserId()`
7. If user not logged in → redirect to `MemberLogin?resort={resort}`
8. If logged in → proceed to load data

### Data Loading

1. Call `patrolData.resetRoster()` to get ResultSet
2. Iterate through roster with `patrolData.nextMember("&nbsp;", rosterResults)`
3. For each member:
   - Check if `member.getSub()` starts with 'y' or 'Y'
   - If yes, create SubstituteInfo with:
     - name: `member.getFullName_lastNameFirst()`
     - homePhone: `member.getHomePhone()`
     - workPhone: `member.getWorkPhone()`
     - cellPhone: `member.getCellPhone()`
     - email: `member.getEmailAddress()`
   - Add to substitutes list

### Email List Building

Separate pass through full roster to build email list:
1. Call `patrolData.resetRoster()` again
2. For each member with SUB='yes':
   - Check if email is valid using `StaticUtils.isValidEmailAddress()`
   - Email must contain '@' and '.', length > 6
   - If valid, add to comma-separated email list string
3. Build EmailForm URL: `EmailForm?resort={resort}&ID={patrollerId}&SubList=1`
4. Add URL to model

### Template Rendering

1. Thymeleaf processes model attributes
2. Render header fragment with resort parameter
3. Display page title: "Substitute List for {resortFullName}"
4. Render email button with onclick handler
5. Generate table:
   - Fixed headers: Name | Home | Work | Cell | Email
   - Loop through substitutes list
   - For each substitute, render row with contact info
   - Email column: use `th:if` to conditionally show mailto link
   - Empty fields: use `th:if` to only show content when non-empty
6. Render footer fragment
7. Return HTML to browser

## Error Handling

### Session Validation
- If `sessionData.getLoggedInUserId()` is null or empty:
  - Redirect to `MemberLogin?resort={resort}`
  - Return null from controller method

### Invalid Resort
- PatrolData validates resort via existing `initBase()` logic
- Invalid resort handled by legacy error handling
- No additional controller-level validation needed

### Empty Substitute List
- If no members have SUB='yes':
  - Display table headers
  - Show message: "No substitutes currently available"
  - Hide or disable email button (use `th:if="${!substitutes.isEmpty()}"`)

### Email Validation
- Use `StaticUtils.isValidEmailAddress()` to validate each email
- Invalid emails:
  - Display in table as plain text (no mailto link)
  - Exclude from email list for EmailForm button
- Template logic: `<a th:if="${sub.email != null and sub.email != ''}" th:href="'mailto:' + ${sub.email}">`

### Database Connection Errors
- PatrolData throws IOException on connection failure
- Controller method declares `throws IOException`
- Spring's default error handling catches and displays error page
- Log exception via Logger before propagating

### Edge Cases
- Missing contact info: cells render empty (no "&nbsp;" placeholder)
- Member with no email: name and phones display, email cell empty
- All members have invalid emails: email button hidden

## Implementation Notes

### ServletAdapter Pattern
Follow existing pattern from DirectorsController and other migrated controllers:
```java
javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
Logger LOG = new Logger(SubListController.class, javaxRequest, "GET", resort, Logger.INFO);
SessionData sessionData = new SessionData(javaxRequest, null, LOG);
PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
```

### Resource Management
- PatrolData connection closed automatically by try-with-resources or explicit close
- Follow pattern from existing controllers

### Email Button Access
- Button available to all logged-in users (not just directors)
- Current behavior: anyone can email substitute list
- No access control restrictions

## Testing Plan

### Manual Testing Checklist

**Basic Functionality:**
1. Navigate to `/SubList?resort=Sample&ID={validId}`
2. Verify substitute list displays correctly
3. Check that only members with SUB='yes' appear
4. Verify contact info (name, phones, email) displays properly
5. Verify table styling matches list-patrollers.html

**Email Functionality:**
1. Click "e-mail THESE patrollers" button
2. Verify redirects to EmailForm with `&SubList=1` parameter
3. Check that URL includes correct resort and ID
4. Verify EmailForm receives comma-separated email list

**Authentication:**
1. Access SubList without valid session
2. Verify redirects to MemberLogin with resort parameter
3. Login with valid credentials
4. Retry SubList access → should display list

**Edge Cases:**
1. Resort with no substitutes → displays empty message and hidden button
2. Member with missing phone numbers → cells render empty
3. Member with invalid email → displays as text, not mailto link
4. Member with valid email → displays as clickable mailto link
5. Multiple substitutes → all display in table correctly

**Visual Consistency:**
1. Header fragment renders with resort logo and navigation
2. Footer fragment renders correctly
3. Table styling matches list-patrollers.html
4. Font sizes consistent (10pt body text)
5. Layout responsive and clean on different browser sizes

### Integration Testing
- Not required for initial migration
- Can add Selenium tests later if needed
- Follow existing pattern: integration tests deferred

### Coexistence Verification
1. Legacy SubList servlet remains registered in web.xml
2. New Spring controller takes precedence at `/SubList` path
3. Other servlets (MonthCalendar, Directors, etc.) continue working
4. No conflicts with existing endpoints

## Files to Create/Modify

### New Files
1. `src/main/java/org/nsponline/calendar/controller/SubListController.java`
   - Spring MVC controller with @GetMapping
   - SubstituteInfo inner class

2. `src/main/resources/templates/sub-list.html`
   - Thymeleaf template
   - Uses fragments/header and fragments/footer

### Modified Files
None - this is a new controller, no existing files modified

### Files to Reference
1. `src/main/java/org/nsponline/calendar/resources/SubList.java` (original servlet)
2. `src/main/java/org/nsponline/calendar/controller/DirectorsController.java` (pattern reference)
3. `src/main/resources/templates/list-patrollers.html` (styling reference)
4. `src/main/resources/templates/fragments/header.html` (header fragment)
5. `src/main/resources/templates/fragments/footer.html` (footer fragment)

## Migration Pattern Consistency

This migration follows the established pattern from:
- **DirectorsController:** Session validation, resort handling, model population
- **ListPatrollersController:** Roster iteration, email list building, table rendering
- **MemberLoginController:** Thymeleaf template structure, form handling
- **MonthCalendarController:** ServletAdapter usage, legacy code integration

Key consistencies:
- ServletAdapter for jakarta→javax bridge
- Logger, SessionData, PatrolData initialization
- Redirect to MemberLogin if not authenticated
- Thymeleaf fragments for header/footer
- Model attributes for template rendering
- Try-catch for IOException handling

## Success Criteria

1. SubList accessible at `/SubList?resort={resort}&ID={id}`
2. Displays only patrollers with SUB='yes'
3. Shows contact info: name, home/work/cell phones, email
4. Email button links to EmailForm with correct parameters
5. Empty cells render as empty (no "&nbsp;")
6. Email addresses are clickable mailto links
7. Invalid emails display as plain text
8. Redirects to login if session invalid
9. Styling consistent with list-patrollers.html
10. Header and footer fragments render correctly
11. No timestamp footer (removed per requirements)
12. Legacy servlet coexists without conflicts

## Future Enhancements (Out of Scope)

- Sorting by name, phone, or email
- Filtering by classification or commitment
- Export to CSV or PDF
- Inline email composition (instead of redirect to EmailForm)
- Display substitute availability or preferences
- Show shift assignment history for substitutes
- Mobile-responsive layout improvements
