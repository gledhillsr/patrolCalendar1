# SubList Spring Boot Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate SubList servlet to Spring MVC controller with Thymeleaf template

**Architecture:** Create SubListController with @GetMapping endpoint, inner SubstituteInfo model class, and sub-list.html Thymeleaf template. Follow existing migration patterns from DirectorsController and ListPatrollersController.

**Tech Stack:** Spring Boot 3.4, Thymeleaf, ServletAdapter for legacy compatibility, existing PatrolData/Roster infrastructure

---

## File Structure

**New Files:**
- `src/main/java/org/nsponline/calendar/controller/SubListController.java` - Spring MVC controller
- `src/main/resources/templates/sub-list.html` - Thymeleaf template

**Reference Files:**
- `src/main/java/org/nsponline/calendar/resources/SubList.java` - Original servlet (for logic reference)
- `src/main/java/org/nsponline/calendar/controller/DirectorsController.java` - Pattern reference
- `src/main/resources/templates/list-patrollers.html` - Styling reference

---

## Task 1: Create SubListController with SubstituteInfo Model

**Files:**
- Create: `src/main/java/org/nsponline/calendar/controller/SubListController.java`

- [ ] **Step 1: Create controller skeleton with SubstituteInfo inner class**

```java
package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot controller for substitute list.
 * Migrated from SubList servlet.
 *
 * Handles GET /SubList - displays patrollers who opted in as substitutes
 */
@Controller
public class SubListController {

    /**
     * Model class for substitute patroller information
     */
    public static class SubstituteInfo {
        private String name;
        private String homePhone;
        private String workPhone;
        private String cellPhone;
        private String email;

        public SubstituteInfo(String name, String homePhone, String workPhone, 
                             String cellPhone, String email) {
            this.name = name;
            this.homePhone = homePhone;
            this.workPhone = workPhone;
            this.cellPhone = cellPhone;
            this.email = email;
        }

        // Getters
        public String getName() { return name; }
        public String getHomePhone() { return homePhone; }
        public String getWorkPhone() { return workPhone; }
        public String getCellPhone() { return cellPhone; }
        public String getEmail() { return email; }
    }

    @GetMapping("/SubList")
    public String showSubList(HttpServletRequest request, HttpServletResponse response,
                              Model model) throws IOException {
        // Implementation in next steps
        return "sub-list";
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
git add src/main/java/org/nsponline/calendar/controller/SubListController.java
git commit -m "feat: add SubListController skeleton with SubstituteInfo model"
```

---

## Task 2: Implement Controller Request Handling and Session Validation

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/SubListController.java`

- [ ] **Step 1: Add resort parameter validation and ServletAdapter initialization**

Replace the `showSubList` method with:

```java
@GetMapping("/SubList")
public String showSubList(HttpServletRequest request, HttpServletResponse response,
                          Model model) throws IOException {
    // 1. Read resort parameter
    String resort = request.getParameter("resort");
    
    // 2. Use ServletAdapter for legacy code compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(SubListController.class, javaxRequest, "GET", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    
    try {
        // 3. Get logged-in user info
        String patrollerId = sessionData.getLoggedInUserId();
        
        // 4. Check authentication - redirect if not logged in
        if (patrollerId == null || patrollerId.isEmpty()) {
            response.sendRedirect("MemberLogin?resort=" + resort);
            return null;
        }
        
        // TODO: Load data and populate model (next task)
        
        return "sub-list";
        
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
git add src/main/java/org/nsponline/calendar/controller/SubListController.java
git commit -m "feat: add SubList session validation and resort handling"
```

---

## Task 3: Implement Roster Data Loading and Substitute Filtering

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/SubListController.java`

- [ ] **Step 1: Add data loading logic after authentication check**

Replace the `// TODO: Load data and populate model` comment with:

```java
        // 5. Load substitute list
        List<SubstituteInfo> substitutes = new ArrayList<>();
        ResultSet rosterResults = patrolData.resetRoster();
        Roster member = patrolData.nextMember("&nbsp;", rosterResults);
        
        while (member != null) {
            // Check if member opted in as substitute
            String subField = member.getSub();
            if (subField != null && (subField.startsWith("y") || subField.startsWith("Y"))) {
                // Create SubstituteInfo with contact details
                String name = member.getFullName_lastNameFirst();
                String homePhone = member.getHomePhone();
                String workPhone = member.getWorkPhone();
                String cellPhone = member.getCellPhone();
                String email = member.getEmailAddress();
                
                substitutes.add(new SubstituteInfo(name, homePhone, workPhone, cellPhone, email));
            }
            
            member = patrolData.nextMember("&nbsp;", rosterResults);
        }
        
        // 6. Add model attributes
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("patrollerId", patrollerId);
        model.addAttribute("substitutes", substitutes);
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit data loading**

```bash
git add src/main/java/org/nsponline/calendar/controller/SubListController.java
git commit -m "feat: add SubList roster loading and substitute filtering"
```

---

## Task 4: Build Email List for EmailForm Button

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/SubListController.java`

- [ ] **Step 1: Add email list building logic after substitute list is built**

Add this code after the `model.addAttribute("substitutes", substitutes);` line:

```java
        // 7. Build comma-separated email list for EmailForm button
        StringBuilder emailList = new StringBuilder();
        rosterResults = patrolData.resetRoster();
        member = patrolData.nextMember("&nbsp;", rosterResults);
        
        while (member != null) {
            String subField = member.getSub();
            if (subField != null && (subField.startsWith("y") || subField.startsWith("Y"))) {
                String email = member.getEmailAddress();
                // Validate email address
                if (StaticUtils.isValidEmailAddress(email)) {
                    if (emailList.length() > 0) {
                        emailList.append(",");
                    }
                    emailList.append(email);
                }
            }
            member = patrolData.nextMember("&nbsp;", rosterResults);
        }
        
        // 8. Build EmailForm URL with SubList parameter
        String emailFormUrl = "EmailForm?resort=" + resort + "&ID=" + patrollerId + "&SubList=1";
        model.addAttribute("emailFormUrl", emailFormUrl);
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit email list building**

```bash
git add src/main/java/org/nsponline/calendar/controller/SubListController.java
git commit -m "feat: add email list building for SubList EmailForm button"
```

---

## Task 5: Create Thymeleaf Template Structure

**Files:**
- Create: `src/main/resources/templates/sub-list.html`

- [ ] **Step 1: Create template with header, title, and structure**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title th:text="'Substitute List for ' + ${resortFullName}">Substitute List</title>
    <style type="text/css">
        body {
            font-size: 10pt;
            color: #000000;
            background-color: #ffffff;
        }
        table.substitute-list {
            font-size: 10pt;
            font-family: Verdana, Arial, Helvetica;
            border-width: 1px;
            border-color: #003366;
            border-style: solid;
            border-collapse: collapse;
            width: 99%;
        }
        table.substitute-list th {
            font-size: 10pt;
            font-family: Verdana, Arial, Helvetica;
            font-weight: normal;
            color: #000000;
            background-color: #C0C0C0;
            border-width: 1px;
            border-color: #003366;
            border-style: solid;
            padding: 2px;
        }
        table.substitute-list td {
            font-size: 10pt;
            font-family: Verdana, Arial, Helvetica;
            color: #000000;
            background-color: #ffffff;
            border-width: 1px;
            border-color: #C0C0C0;
            border-style: solid;
            padding: 2px;
        }
    </style>
</head>
<body>
    <div th:replace="~{fragments/header :: header(resort=${resort})}"></div>

    <h2 th:text="'Substitute List for ' + ${resortFullName}">Substitute List</h2>

    <!-- Email button section - next step -->
    
    <!-- Table section - next step -->

    <div th:replace="~{fragments/footer :: footer}"></div>
</body>
</html>
```

- [ ] **Step 2: Verify template syntax**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit template structure**

```bash
git add src/main/resources/templates/sub-list.html
git commit -m "feat: add SubList Thymeleaf template structure"
```

---

## Task 6: Add Email Button to Template

**Files:**
- Modify: `src/main/resources/templates/sub-list.html`

- [ ] **Step 1: Add email button section**

Replace the `<!-- Email button section - next step -->` comment with:

```html
    <!-- Email button -->
    <p th:if="${!substitutes.isEmpty()}">
        <b>
            <input type="button" 
                   value="e-mail THESE patrollers" 
                   th:onclick="'window.location=\'' + ${emailFormUrl} + '\''"/>
        </b>
    </p>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit email button**

```bash
git add src/main/resources/templates/sub-list.html
git commit -m "feat: add email button to SubList template"
```

---

## Task 7: Add Substitute Table to Template

**Files:**
- Modify: `src/main/resources/templates/sub-list.html`

- [ ] **Step 1: Add table with headers and substitute rows**

Replace the `<!-- Table section - next step -->` comment with:

```html
    <!-- Substitute table -->
    <table class="substitute-list">
        <tr>
            <th width="148">Name</th>
            <th width="90">Home</th>
            <th width="83">Work</th>
            <th width="70">Cell</th>
            <th width="136">Email</th>
        </tr>
        
        <!-- Empty state message -->
        <tr th:if="${substitutes.isEmpty()}">
            <td colspan="5" style="text-align: center; padding: 20px;">
                No substitutes currently available
            </td>
        </tr>
        
        <!-- Substitute rows -->
        <tr th:each="sub : ${substitutes}">
            <td th:text="${sub.name}">Name</td>
            <td th:text="${sub.homePhone != null and sub.homePhone != '' ? sub.homePhone : ''}">Home</td>
            <td th:text="${sub.workPhone != null and sub.workPhone != '' ? sub.workPhone : ''}">Work</td>
            <td th:text="${sub.cellPhone != null and sub.cellPhone != '' ? sub.cellPhone : ''}">Cell</td>
            <td>
                <a th:if="${sub.email != null and sub.email != ''}" 
                   th:href="'mailto:' + ${sub.email}" 
                   th:text="${sub.email}">email</a>
            </td>
        </tr>
    </table>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit substitute table**

```bash
git add src/main/resources/templates/sub-list.html
git commit -m "feat: add substitute table with empty state to SubList template"
```

---

## Task 8: Build and Test Application

**Files:**
- Test: All SubList components

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

- [ ] **Step 3: Test SubList endpoint manually**

Open browser and navigate to:
```
http://localhost:8080/calendar-1/SubList?resort=Sample&ID=12345
```

Expected behavior:
- If not logged in: redirects to MemberLogin
- If logged in: displays substitute list page
- Page shows "Substitute List for [Resort Name]"
- Table displays with Name, Home, Work, Cell, Email columns
- If substitutes exist: rows display contact info
- If no substitutes: shows "No substitutes currently available"
- Email button appears if substitutes exist
- Email addresses are clickable mailto links

- [ ] **Step 4: Test email button**

Click "e-mail THESE patrollers" button

Expected:
- Redirects to EmailForm with correct parameters
- URL includes: `?resort=Sample&ID=12345&SubList=1`

- [ ] **Step 5: Test empty state**

Test with resort that has no substitutes

Expected:
- Empty state message displays
- Email button is hidden
- Table headers still show

- [ ] **Step 6: Test authentication redirect**

Clear session/cookies and access SubList

Expected:
- Redirects to MemberLogin with resort parameter

- [ ] **Step 7: Stop the application**

Press Ctrl+C in terminal

Expected: Application shuts down cleanly

---

## Task 9: Update Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update migrated endpoints section**

Find the "### Migrated Endpoints" section in CLAUDE.md and add SubList:

```markdown
### Migrated Endpoints

- `/ListPatrollers` - Patrol member roster display (Spring MVC + Thymeleaf)
- `/MemberLogin` - Member login page with password reset (Spring MVC + Thymeleaf)
- `/VolunteerForm` - Public volunteer signup form (Spring MVC + Thymeleaf)
- `/MonthCalendar` - Monthly shift calendar (Spring MVC + Thymeleaf)
- `/Directors` - Director administration panel (Spring MVC + Thymeleaf)
- `/SubList` - Substitute patroller list (Spring MVC + Thymeleaf)
```

- [ ] **Step 2: Verify CLAUDE.md syntax**

Read through the updated section to ensure formatting is correct.

- [ ] **Step 3: Commit documentation update**

```bash
git add CLAUDE.md
git commit -m "docs: add SubList to migrated endpoints in CLAUDE.md"
```

---

## Task 10: Final Verification and Tag

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
git log --oneline -10
```

Expected: See all SubList migration commits in order

- [ ] **Step 4: Create summary of changes**

Create a summary of what was accomplished:
- SubListController.java created with SubstituteInfo model
- sub-list.html Thymeleaf template created
- Session validation and authentication redirect implemented
- Roster data loading with substitute filtering
- Email list building for EmailForm integration
- Responsive table display with empty state
- Documentation updated

- [ ] **Step 5: Optional - Create git tag**

```bash
git tag -a sublist-migration-v1 -m "SubList Spring Boot migration complete"
```

---

## Success Criteria Checklist

Verify all requirements from the spec are met:

- [ ] SubList accessible at `/SubList?resort={resort}&ID={id}`
- [ ] Displays only patrollers with SUB='yes'
- [ ] Shows contact info: name, home/work/cell phones, email
- [ ] Email button links to EmailForm with correct parameters
- [ ] Empty cells render as empty (no "&nbsp;")
- [ ] Email addresses are clickable mailto links
- [ ] Invalid emails display as plain text
- [ ] Redirects to login if session invalid
- [ ] Styling consistent with list-patrollers.html
- [ ] Header and footer fragments render correctly
- [ ] No timestamp footer (per requirements)
- [ ] Legacy servlet coexists without conflicts

---

## Rollback Plan

If issues are discovered after deployment:

1. **Immediate rollback:**
   ```bash
   git revert HEAD~10..HEAD
   git push
   ```

2. **Selective rollback (if only template needs fixing):**
   ```bash
   git revert <commit-hash-of-template-change>
   ```

3. **Return to legacy servlet:**
   - Remove @GetMapping from SubListController
   - Legacy servlet will handle /SubList requests again

---

## Notes

- **ServletAdapter Pattern:** Used to bridge jakarta.servlet (Spring Boot 3) to javax.servlet (legacy code)
- **Resource Cleanup:** PatrolData.close() called in finally block to ensure connection cleanup
- **Email Validation:** Uses StaticUtils.isValidEmailAddress() for consistency with other controllers
- **Empty State:** Template handles zero substitutes gracefully with message and hidden button
- **Authentication:** Follows existing pattern from DirectorsController and other migrated endpoints
