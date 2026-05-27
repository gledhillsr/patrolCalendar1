# ListAssignments Spring Boot Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate ListAssignments servlet to Spring MVC controller with Thymeleaf template

**Architecture:** Create ListAssignmentsController with @GetMapping endpoint, inner AssignmentInfo model class, season calculation logic, assignment filtering, and list-assignments.html Thymeleaf template. Follow existing migration patterns from SubListController and MemberListController.

**Tech Stack:** Spring Boot 3.4, Thymeleaf, ServletAdapter for legacy compatibility, existing PatrolData/Assignments infrastructure, Java Calendar for date handling

---

## File Structure

**New Files:**
- `src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java` - Spring MVC controller
- `src/main/resources/templates/list-assignments.html` - Thymeleaf template

**Reference Files:**
- `src/main/java/org/nsponline/calendar/resources/ListAssignments.java` - Original servlet (for logic reference)
- `src/main/java/org/nsponline/calendar/controller/SubListController.java` - Pattern reference
- `src/main/java/org/nsponline/calendar/controller/MemberListController.java` - Pattern reference  
- `src/main/resources/templates/member-list.html` - Styling reference

---

## Task 1: Create ListAssignmentsController with AssignmentInfo Model

**Files:**
- Create: `src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java`

- [ ] **Step 1: Create controller skeleton with AssignmentInfo inner class**

```java
package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nsponline.calendar.store.Assignments;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot controller for listing patrol assignments.
 * Migrated from ListAssignments servlet.
 *
 * Handles GET /ListAssignments - displays patroller's shift assignments
 */
@Controller
public class ListAssignmentsController {

    public static final String DISPLAY_ALL_ASSIGNMENTS = "DisplayAllAssignments";

    /**
     * Model class for assignment information
     */
    public static class AssignmentInfo {
        private String dateTimeRange;
        private String shiftType;
        private boolean missedShift;
        private String bgColor;

        public AssignmentInfo(String dateTimeRange, String shiftType, 
                             boolean missedShift, String bgColor) {
            this.dateTimeRange = dateTimeRange;
            this.shiftType = shiftType;
            this.missedShift = missedShift;
            this.bgColor = bgColor;
        }

        // Getters
        public String getDateTimeRange() { return dateTimeRange; }
        public String getShiftType() { return shiftType; }
        public boolean isMissedShift() { return missedShift; }
        public String getBgColor() { return bgColor; }
    }

    @GetMapping("/ListAssignments")
    public String showAssignments(HttpServletRequest request, HttpServletResponse response,
                                  Model model) throws IOException {
        // Implementation in next steps
        return "list-assignments";
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
git add src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java
git commit -m "feat: add ListAssignmentsController skeleton with AssignmentInfo model"
```

---

## Task 2: Implement Controller Request Handling and Session Validation

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java`

- [ ] **Step 1: Add resort validation and session handling**

Replace the `showAssignments` method with:

```java
@GetMapping("/ListAssignments")
public String showAssignments(HttpServletRequest request, HttpServletResponse response,
                              Model model) throws IOException {
    // 1. Read resort parameter
    String resort = request.getParameter("resort");

    // 2. Use ServletAdapter for legacy code compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
    Logger LOG = new Logger(ListAssignmentsController.class, javaxRequest, "GET", resort, Logger.INFO);

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
                "ListAssignments", patrolData, LOG);

        if (credentials.hasInvalidCredentials()) {
            return null; // Redirect already sent
        }

        // 6. Get logged-in user info
        String patrollerId = sessionData.getLoggedInUserId();

        // TODO: Load data and populate model (next task)

        return "list-assignments";

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
git add src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java
git commit -m "feat: add ListAssignments session validation and resort handling"
```

---

## Task 3: Implement Backdoor User Check and Season Calculation

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java`

- [ ] **Step 1: Add backdoor check and season start logic**

Replace the `// TODO: Load data and populate model` comment with:

```java
        // 7. Check for backdoor user
        boolean isBackdoorUser = patrollerId.equalsIgnoreCase(sessionData.getBackDoorUser());
        
        if (isBackdoorUser) {
            // Backdoor user - skip assignment loading
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", patrollerId);
            model.addAttribute("isBackdoorUser", true);
            model.addAttribute("assignments", new ArrayList<AssignmentInfo>());
            model.addAttribute("shiftCounts", new LinkedHashMap<String, Integer>());
            model.addAttribute("totalShifts", 0);
            
            // Add resort metadata for header
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }
            model.addAttribute("logoutLink", "");
            
            return "list-assignments";
        }

        // 8. Get DisplayAllAssignments parameter
        String displayAllParam = request.getParameter(DISPLAY_ALL_ASSIGNMENTS);
        boolean displayAllAssignments = "true".equals(displayAllParam);

        // 9. Calculate season start date
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

        // TODO: Load assignments (next task)
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit backdoor and season logic**

```bash
git add src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java
git commit -m "feat: add backdoor user check and season start calculation to ListAssignments"
```

---

## Task 4: Implement Assignment Loading and Filtering

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java`

- [ ] **Step 1: Add assignment loading and filtering logic**

Replace the `// TODO: Load assignments` comment with:

```java
        // 10. Get member info
        Roster member = patrolData.getMemberByID(patrollerId);
        String memberName = member != null ? member.getFullName() : "Unknown";
        int myID = Integer.parseInt(patrollerId);

        // 11. Initialize collections for assignment data
        List<AssignmentInfo> assignments = new ArrayList<>();
        int[] shiftCounts = new int[Assignments.MAX_SHIFT_TYPES];
        int totalShifts = 0;

        // 12. Set up alternating row colors
        String WHITE_BG_COLOR = "#FFFFFF";
        String LT_GREEN_BG_COLOR = "#D7F5ED";
        String bgColor = WHITE_BG_COLOR;
        int lastMonth = 0;

        // 13. Load and process assignments
        for (Assignments ns : patrolData.readAllSortedAssignments(patrollerId)) {
            // Check if assignment is within season range
            Calendar assignmentDate = new GregorianCalendar(ns.getYear(), ns.getMonth(), ns.getDay());
            if (assignmentDate.before(calendarStart)) {
                continue; // Skip assignments before season start
            }

            // Build date/time strings
            String szDate = ns.getExpandedDateAndTimeString();
            String szStartTime = ns.getStartingTimeString();
            String szEndTime = ns.getEndingTimeString();
            String dateTimeRange = szDate + " (" + szStartTime + " - " + szEndTime + ")";

            // Handle month change for alternating colors
            if (lastMonth != ns.getMonth()) {
                lastMonth = ns.getMonth();
                bgColor = bgColor.equals(WHITE_BG_COLOR) ? LT_GREEN_BG_COLOR : WHITE_BG_COLOR;
            }

            // Loop through all patrollers on this assignment
            for (int shiftType = 0; shiftType < Assignments.MAX_ASSIGNMENT_SIZE; shiftType++) {
                String posID = ns.getPosID(shiftType);
                if (posID == null || posID.equals("")) {
                    continue;
                }

                try {
                    int pat0 = Integer.parseInt(posID);
                    if (Math.abs(pat0) == myID) {
                        // Found matching patroller
                        boolean missedShift = (pat0 < 0);
                        String shiftTypeName = Assignments.getShiftName(ns.getType());

                        assignments.add(new AssignmentInfo(dateTimeRange, shiftTypeName, missedShift, bgColor));
                        shiftCounts[ns.getType()]++;
                        totalShifts++;
                    }
                } catch (Exception e) {
                    LOG.error("error id (" + posID + ")");
                    continue;
                }
            }
        }

        // TODO: Populate model (next task)
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit assignment loading**

```bash
git add src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java
git commit -m "feat: add assignment loading and filtering to ListAssignments"
```

---

## Task 5: Populate Model Attributes

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java`

- [ ] **Step 1: Add model population logic**

Replace the `// TODO: Populate model` comment with:

```java
        // 14. Convert shift counts array to map (only non-zero)
        Map<String, Integer> shiftCountsMap = new LinkedHashMap<>();
        for (int shiftType = 0; shiftType < Assignments.MAX_SHIFT_TYPES; shiftType++) {
            if (shiftCounts[shiftType] > 0) {
                String shiftTypeName = Assignments.getShiftName(shiftType);
                shiftCountsMap.put(shiftTypeName, shiftCounts[shiftType]);
            }
        }

        // 15. Build toggle URL and text
        String toggleUrl = "ListAssignments?resort=" + resort + "&ID=" + patrollerId + 
            "&" + DISPLAY_ALL_ASSIGNMENTS + "=" + !displayAllAssignments;
        String toggleText = displayAllAssignments 
            ? "Change to current season only" 
            : "Change to display all assignments";

        // 16. Add model attributes
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("patrollerId", patrollerId);
        model.addAttribute("memberName", memberName);
        model.addAttribute("displayAllAssignments", displayAllAssignments);
        model.addAttribute("toggleUrl", toggleUrl);
        model.addAttribute("toggleText", toggleText);
        model.addAttribute("assignments", assignments);
        model.addAttribute("shiftCounts", shiftCountsMap);
        model.addAttribute("totalShifts", totalShifts);
        model.addAttribute("isBackdoorUser", false);

        // 17. Add resort metadata for header
        ResortData resortData = PatrolData.getResortInfo(resort);
        if (resortData != null) {
            model.addAttribute("resortUrl", resortData.getResortURL());
            model.addAttribute("resortImage", resortData.getResortImage());
            model.addAttribute("resortImgHeight", resortData.getImageHeight());
        }
        model.addAttribute("logoutLink", "");
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit model population**

```bash
git add src/main/java/org/nsponline/calendar/controller/ListAssignmentsController.java
git commit -m "feat: add model population to ListAssignments"
```

---

## Task 6: Create Thymeleaf Template Structure

**Files:**
- Create: `src/main/resources/templates/list-assignments.html`

- [ ] **Step 1: Create template with header and basic structure**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title th:text="${memberName} + '''s Assignment Schedule for ' + ${resortFullName}">Assignment Schedule</title>
    <style type="text/css">
        body {
            font-size: 10pt;
            color: #000000;
            background-color: #ffffff;
        }
    </style>
</head>
<body topmargin="0" leftmargin="0" style="background-image: url(/calendar-1/images/ncmnthbk.jpg)">
    <div th:replace="~{fragments/header :: header(resort=${resort}, resortFullName=${resortFullName}, patrollerId=${patrollerId})}"></div>

    <h1>
        <img border="0" src="/calendar-1/images/ski_accident_md_wht.gif" align="top">
        &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Shift Summary
    </h1>

    <!-- Backdoor user message -->
    <div th:if="${isBackdoorUser}">
        Have a great day<br>
    </div>

    <!-- Main content for normal users -->
    <div th:unless="${isBackdoorUser}">
        <!-- Toggle link - next step -->
        
        <!-- Member info - next step -->
        
        <!-- Assignment table - next step -->
        
        <!-- Shift counts - next step -->
    </div>

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
git add src/main/resources/templates/list-assignments.html
git commit -m "feat: add ListAssignments Thymeleaf template structure"
```

---

## Task 7: Add Toggle Link and Member Info to Template

**Files:**
- Modify: `src/main/resources/templates/list-assignments.html`

- [ ] **Step 1: Add toggle link and member info sections**

Replace the `<!-- Toggle link - next step -->` and `<!-- Member info - next step -->` comments with:

```html
        <!-- Toggle link -->
        <p th:if="${displayAllAssignments}">
            Displaying all assignments.  
            <a target="main" th:href="${toggleUrl}"><b th:text="${toggleText}">Toggle</b></a>
        </p>
        <p th:unless="${displayAllAssignments}">
            Displaying current season ONLY.  
            <a target="main" th:href="${toggleUrl}"><b th:text="${toggleText}">Toggle</b></a>
        </p>

        <!-- Member name and resort -->
        <p>
            <font size="5" th:text="${memberName} + '''s Assignment Schedule for ' + ${resortFullName}">Assignment Schedule</font>
        </p>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit toggle and member info**

```bash
git add src/main/resources/templates/list-assignments.html
git commit -m "feat: add toggle link and member info to ListAssignments template"
```

---

## Task 8: Add Assignment Table to Template

**Files:**
- Modify: `src/main/resources/templates/list-assignments.html`

- [ ] **Step 1: Add assignment table**

Replace the `<!-- Assignment table - next step -->` comment with:

```html
        <!-- Assignment table -->
        <div align="left">
            <table border="1" width="550" bgcolor="#FFFFFF" cellpadding="0" cellspacing="0">
                <!-- Empty state -->
                <tr th:if="${assignments.isEmpty()}">
                    <td th:attr="bgcolor=${#strings.equals(assignments.isEmpty(), true) ? '#FFFFFF' : ''}">
                        <td valign="middle" align="left">
                            &nbsp;No shifts were scheduled
                        </td>
                        <td>&nbsp;</td>
                    </td>
                </tr>

                <!-- Assignment rows -->
                <tr th:each="assignment : ${assignments}" th:attr="bgcolor=${assignment.bgColor}">
                    <td>
                        <td th:if="${assignment.missedShift}" valign="middle" bgcolor="#80ffff" align="left">
                            &nbsp;<span th:text="${assignment.dateTimeRange}">Date</span> &lt;Shift Missed&gt;
                        </td>
                        <td th:unless="${assignment.missedShift}" valign="middle" align="left">
                            &nbsp;<span th:text="${assignment.dateTimeRange}">Date</span>
                        </td>
                        <td>
                            &nbsp;<span th:text="${assignment.shiftType}">Type</span>
                        </td>
                    </td>
                </tr>
            </table>
        </div>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit assignment table**

```bash
git add src/main/resources/templates/list-assignments.html
git commit -m "feat: add assignment table to ListAssignments template"
```

---

## Task 9: Add Shift Counts and Totals to Template

**Files:**
- Modify: `src/main/resources/templates/list-assignments.html`

- [ ] **Step 1: Add shift counts display**

Replace the `<!-- Shift counts - next step -->` comment with:

```html
        <!-- Shift count totals -->
        <br><b>Totals</b>
        <span th:each="entry : ${shiftCounts}">
            &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<span th:text="${entry.key}">Shift Type</span> = <b th:text="${entry.value}">0</b>
        </span>
        <br><b>Total shifts</b>:&nbsp;&nbsp;&nbsp;<b th:text="${totalShifts}">0</b>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit shift counts**

```bash
git add src/main/resources/templates/list-assignments.html
git commit -m "feat: add shift counts and totals to ListAssignments template"
```

---

## Task 10: Build and Test Application

**Files:**
- Test: All ListAssignments components

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

- [ ] **Step 3: Test ListAssignments endpoint manually**

Open browser and navigate to:
```
http://localhost:8080/calendar-1/ListAssignments?resort=Sample&ID=12345
```

Expected behavior:
- If not logged in: redirects to MemberLogin
- If logged in: displays assignment list page
- Page shows "Shift Summary" title with ski accident image
- Toggle link displays (current season or all assignments)
- Assignment table shows with date/time and shift type
- Alternating row colors by month (white / light green)
- Missed shifts highlighted with cyan background
- Shift counts by type display at bottom
- Total shifts count displays

- [ ] **Step 4: Test toggle functionality**

Click toggle link

Expected:
- URL changes to include DisplayAllAssignments parameter
- Page reloads with different filter
- Toggle text changes
- Assignment list updates accordingly

- [ ] **Step 5: Test backdoor user**

Test with backdoor user credentials

Expected:
- "Have a great day" message displays
- No assignment table
- No errors

- [ ] **Step 6: Test empty state**

Test with patroller who has no assignments

Expected:
- "No shifts were scheduled" message
- Shift counts show no entries
- Total shows 0

- [ ] **Step 7: Test authentication redirect**

Clear session/cookies and access ListAssignments

Expected:
- Redirects to MemberLogin with resort parameter

- [ ] **Step 8: Stop the application**

Press Ctrl+C in terminal

Expected: Application shuts down cleanly

---

## Task 11: Update Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update migrated endpoints section**

Find the "### Migrated Endpoints" section in CLAUDE.md and add ListAssignments:

```markdown
### Migrated Endpoints

- `/ListPatrollers` - Patrol member roster display (Spring MVC + Thymeleaf)
- `/MemberLogin` - Member login page with password reset (Spring MVC + Thymeleaf)
- `/VolunteerForm` - Public volunteer signup form (Spring MVC + Thymeleaf)
- `/MonthCalendar` - Monthly shift calendar (Spring MVC + Thymeleaf)
- `/Directors` - Director administration panel (Spring MVC + Thymeleaf)
- `/SubList` - Substitute patroller list (Spring MVC + Thymeleaf)
- `/MemberList` - All active members list (Spring MVC + Thymeleaf)
- `/ListAssignments` - Patroller shift assignments (Spring MVC + Thymeleaf)
```

- [ ] **Step 2: Verify CLAUDE.md syntax**

Read through the updated section to ensure formatting is correct.

- [ ] **Step 3: Commit documentation update**

```bash
git add CLAUDE.md
git commit -m "docs: add ListAssignments to migrated endpoints in CLAUDE.md"
```

---

## Task 12: Final Verification and Tag

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
git log --oneline -12
```

Expected: See all ListAssignments migration commits in order

- [ ] **Step 4: Create summary of changes**

Create a summary of what was accomplished:
- ListAssignmentsController.java created with AssignmentInfo model
- list-assignments.html Thymeleaf template created
- Session validation with ValidateCredentialsRedirectIfNeeded
- Season start calculation (current season vs all assignments)
- Assignment loading with date filtering
- Alternating row colors by month
- Missed shift detection and highlighting
- Shift count totals by type
- Backdoor user handling
- Documentation updated

- [ ] **Step 5: Optional - Create git tag**

```bash
git tag -a listassignments-migration-v1 -m "ListAssignments Spring Boot migration complete"
```

---

## Success Criteria Checklist

Verify all requirements from the spec are met:

- [ ] ListAssignments accessible at `/ListAssignments?resort={resort}&ID={id}`
- [ ] Displays patroller's shift assignments
- [ ] Current season filter applied by default (July to present)
- [ ] Toggle link switches between current season / all assignments
- [ ] Alternating row colors by month (white / light green)
- [ ] Missed shifts highlighted with cyan background
- [ ] "<Shift Missed>" text appears for missed shifts
- [ ] Shift counts by type displayed (only non-zero)
- [ ] Total shifts count displayed
- [ ] Empty state: "No shifts were scheduled"
- [ ] Backdoor user: "Have a great day" message
- [ ] Redirects to login if session invalid
- [ ] Header and footer fragments render correctly
- [ ] Background image displays
- [ ] Ski accident image displays in title
- [ ] Legacy servlet coexists without conflicts

---

## Rollback Plan

If issues are discovered after deployment:

1. **Immediate rollback:**
   ```bash
   git revert HEAD~12..HEAD
   git push
   ```

2. **Selective rollback (if only template needs fixing):**
   ```bash
   git revert <commit-hash-of-template-change>
   ```

3. **Return to legacy servlet:**
   - Remove @GetMapping from ListAssignmentsController
   - Legacy servlet will handle /ListAssignments requests again

---

## Notes

- **ServletAdapter Pattern:** Used to bridge jakarta.servlet (Spring Boot 3) to javax.servlet (legacy code)
- **Resource Cleanup:** PatrolData.close() called in finally block to ensure connection cleanup
- **Season Calculation:** Current season starts July 1st of current or previous year based on current month
- **All Assignments:** When DisplayAllAssignments=true, shows assignments from 2002 onwards
- **Alternating Colors:** Row background toggles between white and light green when month changes
- **Missed Shifts:** Detected via negative patroller IDs in assignment positions
- **Backdoor User:** Special case that skips assignment loading and shows friendly message
- **Empty State:** Template handles zero assignments gracefully with message
- **Authentication:** Follows existing pattern from SubListController and other migrated endpoints
