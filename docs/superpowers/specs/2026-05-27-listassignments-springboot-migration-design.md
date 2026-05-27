# ListAssignments Spring Boot Migration Design

**Date:** 2026-05-27  
**Author:** Claude (via brainstorming session)  
**Status:** Approved

## Overview

Migrate the ListAssignments servlet to a Spring MVC controller with Thymeleaf template. ListAssignments displays a patroller's shift assignments with optional filtering by season, alternating row colors by month, missed shift indicators, and shift count totals.

## Goals

- Convert ListAssignments servlet to Spring MVC controller
- Create Thymeleaf template with styling consistent with existing migrations
- Maintain existing functionality: season toggle, alternating colors, shift counts
- Use existing patterns from SubList, MemberList, and other migrated controllers

## Architecture

### Controller: ListAssignmentsController.java

**Package:** `org.nsponline.calendar.controller`

**Endpoint:** `@GetMapping("/ListAssignments")`

**Responsibilities:**
- Validate resort parameter and user session
- Handle DisplayAllAssignments parameter (true/false toggle)
- Calculate season start date based on current date and toggle setting
- Load assignments for logged-in patroller
- Filter assignments by season start date
- Build assignment list with alternating row colors by month
- Detect missed shifts (negative patroller IDs)
- Calculate shift counts by type
- Handle backdoor user special case
- Populate model and render template

**Request Parameters:**
- `resort` (required) - Resort identifier
- `ID` (required) - Logged-in user's patroller ID
- `DisplayAllAssignments` (optional) - "true" or "false", defaults to false (current season only)

**Model Attributes:**
- `resort` (String) - Resort short name
- `resortFullName` (String) - Resort display name
- `patrollerId` (String) - Logged-in user ID
- `memberName` (String) - Patroller's full name
- `displayAllAssignments` (boolean) - Current filter mode
- `toggleUrl` (String) - URL to switch filter mode
- `toggleText` (String) - Text for toggle link
- `assignments` (List<AssignmentInfo>) - List of shift assignments
- `shiftCounts` (Map<String, Integer>) - Shift type name → count
- `totalShifts` (int) - Total number of shifts
- `isBackdoorUser` (boolean) - Special backdoor user flag
- `resortUrl`, `resortImage`, `resortImgHeight` (String/int) - For header

### Model Class: AssignmentInfo

**Location:** Inner static class in ListAssignmentsController

**Fields:**
- `dateTimeRange` (String) - Full date and time range (e.g., "Sat, Dec 25, 2021 (8:00 AM - 4:00 PM)")
- `shiftType` (String) - Shift type name (e.g., "Day", "Night", "Training")
- `missedShift` (boolean) - True if patroller missed this shift
- `bgColor` (String) - Background color (#FFFFFF or #D7F5ED)

**Methods:**
- Standard getters
- Constructor accepting all fields

### Template: list-assignments.html

**Location:** `src/main/resources/templates/list-assignments.html`

**Structure:**
```
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  - Title: "{memberName}'s Assignment Schedule for {resortFullName}"
  - CSS styles
  - Background image: ncmnthbk.jpg
</head>
<body>
  - Header fragment with resort info and navigation
  - Page title with ski accident image and "Shift Summary"
  - Toggle link (current season / all assignments)
  - Patroller name and resort name
  - Assignment table with date/time and shift type
  - Shift count totals by type
  - Total shifts count
  - Footer fragment
</body>
</html>
```

**Table Columns:**
1. Date/Time - Full date and time range
2. Shift Type - Name of shift type

**Special Features:**
- Alternating background colors by month (white / light green)
- Missed shift highlighting (#80ffff background with "Shift Missed" text)
- Empty state: "No shifts were scheduled"
- Backdoor user: "Have a great day" message instead of assignments

**Styling:**
- Legacy inline table styling (border="1", bgcolor attributes)
- Consistent with MemberList migration approach

## Data Flow

### Request Processing

1. User navigates to `/ListAssignments?resort=Sample&ID=12345`
2. Optional parameter: `DisplayAllAssignments=true` (default: false)
3. Controller receives request via Spring DispatcherServlet
4. Extract `resort`, `ID`, and `DisplayAllAssignments` parameters
5. Validate resort using `PatrolData.isValidResort(resort)`
6. If invalid resort → return 400 error
7. Use ServletAdapter to create javax.servlet request for legacy code
8. Initialize Logger, SessionData, PatrolData with resort
9. Use `ValidateCredentialsRedirectIfNeeded` for session validation
10. If user not logged in → redirect to `MemberLogin?resort={resort}`
11. If logged in → proceed to load data

### Data Loading

1. Get patrollerId from `sessionData.getLoggedInUserId()`
2. Check backdoor user: `patrollerId.equalsIgnoreCase(sessionData.getBackDoorUser())`
3. If backdoor user:
   - Set `isBackdoorUser = true`
   - Skip assignment loading
   - Return template with friendly message
4. If not backdoor user:
   - Get member info: `patrol.getMemberByID(patrollerId)`
   - Extract member name: `member.getFullName()`

### Season Start Calculation

1. Get current date: `Calendar calendarStart = new GregorianCalendar()`
2. Extract current year and month
3. Determine season parameters:
   ```java
   if (displayAllAssignments) {
       startYear = 2002;
   } else {
       startYear = (currentMonth < Calendar.JULY) ? currentYear - 1 : currentYear;
   }
   startMonth = Calendar.JULY;
   calendarStart.set(startYear, startMonth, 1);
   ```

### Assignment Loading and Filtering

1. Initialize collections:
   - `List<AssignmentInfo> assignments = new ArrayList<>()`
   - `int[] shiftCounts = new int[Assignments.MAX_SHIFT_TYPES]`
   - `int totalShifts = 0`
2. Load all assignments: `patrol.readAllSortedAssignments(patrollerId)`
3. Initialize alternating colors:
   - `String WHITE_BG_COLOR = "#FFFFFF"`
   - `String LT_GREEN_BG_COLOR = "#D7F5ED"`
   - `String bgColor = WHITE_BG_COLOR`
   - `int lastMonth = 0`
4. For each assignment:
   - Parse assignment date: `Calendar assignmentDate = new GregorianCalendar(ns.getYear(), ns.getMonth(), ns.getDay())`
   - If `assignmentDate.before(calendarStart)`: skip (filter out)
   - Extract date/time strings:
     - `szDate = ns.getExpandedDateAndTimeString()`
     - `szStartTime = ns.getStartingTimeString()`
     - `szEndTime = ns.getEndingTimeString()`
   - Check for month change:
     - If `ns.getMonth() != lastMonth`:
       - Update `lastMonth = ns.getMonth()`
       - Toggle `bgColor` between WHITE and LIGHT_GREEN
   - Loop through assignment positions (0 to MAX_ASSIGNMENT_SIZE):
     - Get position ID: `ns.getPosID(shiftType)`
     - If null or empty: skip
     - Parse as integer: `pat0 = Integer.parseInt(ns.getPosID(shiftType))`
     - If parsing fails: log error and skip
     - If `Math.abs(pat0) == myID`:
       - Found matching patroller
       - Determine missed shift: `boolean missedShift = (pat0 < 0)`
       - Build dateTimeRange: `szDate + " (" + szStartTime + " - " + szEndTime + ")"`
       - Get shift type name: `Assignments.getShiftName(ns.getType())`
       - Create AssignmentInfo(dateTimeRange, shiftTypeName, missedShift, bgColor)
       - Add to assignments list
       - Increment shift counts: `shiftCounts[ns.getType()]++`
       - Increment total: `totalShifts++`

### Model Population

1. Add basic attributes:
   - `resort`, `resortFullName`, `patrollerId`
   - `memberName`
   - `displayAllAssignments`
   - `isBackdoorUser`
2. Build toggle URL and text:
   - `toggleUrl = "ListAssignments?resort=" + resort + "&ID=" + patrollerId + "&DisplayAllAssignments=" + !displayAllAssignments`
   - If current mode is all assignments:
     - `toggleText = "Change to current season only"`
   - Else:
     - `toggleText = "Change to display all assignments"`
3. Add assignments data:
   - `assignments` (List<AssignmentInfo>)
   - Convert shiftCounts array to Map<String, Integer>:
     - Only include non-zero counts
     - Key: shift type name, Value: count
   - `totalShifts`
4. Add resort metadata for header:
   - `resortUrl`, `resortImage`, `resortImgHeight`
   - `logoutLink`

### Template Rendering

1. Thymeleaf processes model attributes
2. Render header fragment with resort parameter
3. Display page title with ski accident image
4. If backdoor user:
   - Show "Have a great day" message
   - Skip assignments section
5. If not backdoor user:
   - Display toggle link with current mode message
   - Display member name and resort name
   - Render assignment table:
     - If assignments empty: show "No shifts were scheduled"
     - Else: loop through assignments
       - Render row with bgColor
       - If missed shift: add special background (#80ffff) and " <Shift Missed>" suffix
       - Show date/time range and shift type
   - Display shift count totals:
     - Loop through shiftCounts map
     - Show: "{shiftType} = {count}"
   - Display total shifts count
6. Render footer fragment
7. Return HTML to browser

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

### Backdoor User
- Check if `patrollerId.equalsIgnoreCase(sessionData.getBackDoorUser())`
- If true:
  - Set `isBackdoorUser = true`
  - Set `assignments = empty list`
  - Skip assignment loading
  - Template displays friendly message: "Have a great day"

### Member Not Found
- `patrol.getMemberByID(patrollerId)` may return null
- If null:
  - Handle gracefully with error message or default name
  - Log warning

### No Assignments
- If assignments list is empty after filtering:
  - Template displays: "No shifts were scheduled"
  - shiftCounts map is empty
  - totalShifts = 0

### Invalid Assignment Position IDs
- Try-catch when parsing patroller IDs:
  ```java
  try {
      pat0 = Integer.parseInt(ns.getPosID(shiftType));
  } catch (Exception e) {
      LOG.error("error id (" + ns.getPosID(shiftType) + ")");
      continue; // Skip this position
  }
  ```
- Log error and continue processing remaining positions

### Database Connection Errors
- PatrolData throws IOException on connection failure
- Controller method declares `throws IOException`
- Spring's default error handling catches and displays error page
- Log exception via Logger before propagating
- PatrolData closed in finally block ensures connection cleanup

### Edge Cases
- Empty assignment list: handled with "No shifts were scheduled"
- All assignments before season start: filtered out, results in empty list
- Invalid DisplayAllAssignments parameter: treat as false (default)
- Missed shifts: detected via negative patroller IDs, highlighted appropriately

## Implementation Notes

### ServletAdapter Pattern
Follow existing pattern from SubList, MemberList, and other migrated controllers:
```java
javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
Logger LOG = new Logger(ListAssignmentsController.class, javaxRequest, "GET", resort, Logger.INFO);
SessionData sessionData = new SessionData(javaxRequest, null, LOG);
PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
```

### Season Start Calculation
```java
Calendar calendarStart = new GregorianCalendar();
int currentYear = calendarStart.get(Calendar.YEAR);
int currentMonth = calendarStart.get(Calendar.MONTH);
int startYear;
int startMonth = Calendar.JULY;

if (displayAllAssignments) {
    startYear = 2002;
} else {
    startYear = (currentMonth < Calendar.JULY) ? currentYear - 1 : currentYear;
}
calendarStart.set(startYear, startMonth, 1);
```

### Alternating Row Colors by Month
```java
String WHITE_BG_COLOR = "#FFFFFF";
String LT_GREEN_BG_COLOR = "#D7F5ED";
String bgColor = WHITE_BG_COLOR;
int lastMonth = 0;

// In assignment loop:
if (ns.getMonth() != lastMonth) {
    lastMonth = ns.getMonth();
    bgColor = bgColor.equals(WHITE_BG_COLOR) ? LT_GREEN_BG_COLOR : WHITE_BG_COLOR;
}
```

### Missed Shift Detection
```java
// Negative patroller ID indicates missed shift
int pat0 = Integer.parseInt(ns.getPosID(shiftType));
if (Math.abs(pat0) == myID) {
    boolean missedShift = (pat0 < 0);
    // ... create AssignmentInfo with missedShift flag
}
```

### Toggle URL Construction
```java
String toggleUrl = "ListAssignments?resort=" + resort + "&ID=" + patrollerId + 
    "&DisplayAllAssignments=" + !displayAllAssignments;
    
String toggleText = displayAllAssignments 
    ? "Change to current season only" 
    : "Change to display all assignments";
```

### Shift Counts Conversion
```java
// Original: int[] shiftCounts = new int[Assignments.MAX_SHIFT_TYPES]
// Convert to Map for template
Map<String, Integer> shiftCountsMap = new LinkedHashMap<>();
for (int shiftType = 0; shiftType < Assignments.MAX_SHIFT_TYPES; shiftType++) {
    if (shiftCounts[shiftType] > 0) {
        String shiftTypeName = Assignments.getShiftName(shiftType);
        shiftCountsMap.put(shiftTypeName, shiftCounts[shiftType]);
    }
}
```

### Resource Management
- PatrolData connection closed in finally block
- Follow pattern from SubList and MemberList:
```java
try {
    // Load data and populate model
    return "list-assignments";
} finally {
    patrolData.close();
}
```

### Assignment Position Loop
```java
// Loop through all positions in assignment to find matching patroller
for (int shiftType = 0; shiftType < Assignments.MAX_ASSIGNMENT_SIZE; shiftType++) {
    String posID = ns.getPosID(shiftType);
    if (posID == null || posID.equals("")) {
        continue;
    }
    try {
        int pat0 = Integer.parseInt(posID);
        if (Math.abs(pat0) == myID) {
            // Found matching assignment
            // ... process
        }
    } catch (Exception e) {
        LOG.error("error id (" + posID + ")");
        continue;
    }
}
```

## Testing Plan

### Manual Testing Checklist

**Basic Functionality:**
1. Navigate to `/ListAssignments?resort=Sample&ID={validId}`
2. Verify assignment list displays correctly
3. Check that current season filter is applied (default)
4. Verify date/time and shift type display properly
5. Verify alternating row colors by month

**Toggle Functionality:**
1. Click "Change to display all assignments" link
2. Verify URL includes `&DisplayAllAssignments=true`
3. Verify older assignments now appear
4. Verify toggle text changes to "Change to current season only"
5. Click toggle again to switch back
6. Verify URL includes `&DisplayAllAssignments=false`
7. Verify only current season assignments show

**Missed Shifts:**
1. Test with assignments that have negative patroller IDs
2. Verify missed shifts have cyan background (#80ffff)
3. Verify "<Shift Missed>" text appears after date/time
4. Verify missed shifts still count in totals

**Shift Counts:**
1. Verify shift counts by type display at bottom
2. Verify only non-zero counts appear
3. Verify total shifts count matches assignment list length
4. Verify shift type names are correct (Day, Night, etc.)

**Empty State:**
1. Test with patroller who has no assignments
2. Verify "No shifts were scheduled" message displays
3. Verify shift counts show 0
4. Verify table structure still renders

**Backdoor User:**
1. Test with backdoor user credentials
2. Verify "Have a great day" message displays
3. Verify no assignment table renders
4. Verify no errors occur

**Authentication:**
1. Access ListAssignments without valid session
2. Verify redirects to MemberLogin with resort parameter
3. Login with valid credentials
4. Retry ListAssignments access → should display assignments

**Edge Cases:**
1. Test with DisplayAllAssignments parameter missing (should default to false)
2. Test with invalid DisplayAllAssignments value (should treat as false)
3. Test with assignments spanning multiple years
4. Test with assignments in different months (verify color changes)
5. Test with mixed missed/completed shifts

**Visual Consistency:**
1. Header fragment renders with resort logo and navigation
2. Footer fragment renders correctly
3. Table styling matches legacy servlet output
4. Background image displays (ncmnthbk.jpg)
5. Ski accident image displays in title
6. Font sizes and spacing consistent

### Integration Testing
- Not required for initial migration
- Can add Selenium tests later if needed
- Follow existing pattern: integration tests deferred

### Coexistence Verification
1. Legacy ListAssignments servlet remains registered in web.xml
2. New Spring controller takes precedence at `/ListAssignments` path
3. Other servlets continue working
4. No conflicts with existing endpoints

## Files to Create/Modify

### New Files
1. `src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java`
   - Spring MVC controller with @GetMapping
   - AssignmentInfo inner class
   - Season calculation logic
   - Assignment filtering and color alternation

2. `src/main/resources/templates/list-assignments.html`
   - Thymeleaf template
   - Uses fragments/header and fragments/footer
   - Table with assignment details
   - Shift count totals display
   - Toggle link for season filter

### Modified Files
None - this is a new controller, no existing files modified

### Files to Reference
1. `src/main/java/org/nsponline/calendar/resources/ListAssignments.java` (original servlet)
2. `src/main/java/org/nsponline/calendar/controller/SubListController.java` (pattern reference)
3. `src/main/java/org/nsponline/calendar/controller/MemberListController.java` (pattern reference)
4. `src/main/resources/templates/member-list.html` (styling reference)
5. `src/main/resources/templates/fragments/header.html` (header fragment)
6. `src/main/resources/templates/fragments/footer.html` (footer fragment)
7. `src/main/java/org/nsponline/calendar/store/Assignments.java` (assignment data structure)

## Migration Pattern Consistency

This migration follows the established pattern from:
- **SubListController:** Session validation, resort handling, model population
- **MemberListController:** Single-pass data loading, legacy inline table styling
- **DirectorsController:** ServletAdapter usage, ValidateCredentialsRedirectIfNeeded
- **MemberLoginController:** Thymeleaf template structure

Key consistencies:
- ServletAdapter for jakarta→javax bridge
- Logger, SessionData, PatrolData initialization
- ValidateCredentialsRedirectIfNeeded for session validation
- Thymeleaf fragments for header/footer
- Model attributes for template rendering
- Try-finally for resource cleanup
- Legacy inline table styling for consistency

## Success Criteria

1. ListAssignments accessible at `/ListAssignments?resort={resort}&ID={id}`
2. Displays patroller's shift assignments
3. Current season filter applied by default (July to present)
4. Toggle link switches between current season / all assignments
5. Alternating row colors by month (white / light green)
6. Missed shifts highlighted with cyan background and "<Shift Missed>" text
7. Shift counts by type displayed (only non-zero)
8. Total shifts count displayed
9. Empty state: "No shifts were scheduled" when no assignments
10. Backdoor user: "Have a great day" message, no assignment table
11. Redirects to login if session invalid
12. Header and footer fragments render correctly
13. Background image displays
14. Ski accident image displays in title
15. Legacy servlet coexists without conflicts

## Future Enhancements (Out of Scope)

- Sorting by date, shift type, or missed status
- Filtering by specific shift types
- Export to PDF or calendar format
- Email notification for upcoming shifts
- Mobile-responsive layout improvements
- Print stylesheet optimization
- Date range picker for custom filtering
- Shift details modal/popup
- Calendar view of assignments
