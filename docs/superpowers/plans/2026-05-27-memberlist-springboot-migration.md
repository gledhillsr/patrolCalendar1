# MemberList Spring Boot Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate MemberList servlet to Spring MVC controller with Thymeleaf template

**Architecture:** Create MemberListController with @GetMapping endpoint, inner MemberInfo model class, and member-list.html Thymeleaf template. Follow existing migration patterns from SubListController.

**Tech Stack:** Spring Boot 3.4, Thymeleaf, ServletAdapter for legacy compatibility, existing PatrolData/Roster infrastructure

---

## File Structure

**New Files:**
- `src/main/java/org/nsponline/calendar/controller/MemberListController.java` - Spring MVC controller
- `src/main/resources/templates/member-list.html` - Thymeleaf template

**Reference Files:**
- `src/main/java/org/nsponline/calendar/resources/MemberList.java` - Original servlet (for logic reference)
- `src/main/java/org/nsponline/calendar/controller/SubListController.java` - Pattern reference
- `src/main/resources/templates/sub-list.html` - Styling reference

---

## Task 1: Create MemberListController with MemberInfo Model

**Files:**
- Create: `src/main/java/org/nsponline/calendar/controller/MemberListController.java`

- [ ] **Step 1: Create controller skeleton with MemberInfo inner class**

```java
package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

/**
 * Spring Boot controller for member list.
 * Migrated from MemberList servlet.
 *
 * Handles GET /MemberList - displays all active patrol members
 */
@Controller
public class MemberListController {

   /**
    * Model class for member information
    */
   public static class MemberInfo {
      private String name;
      private String homePhone;
      private String workPhone;
      private String cellPhone;
      private String email;

      public MemberInfo(String name, String homePhone, String workPhone,
                        String cellPhone, String email) {
         this.name = name;
         this.homePhone = homePhone;
         this.workPhone = workPhone;
         this.cellPhone = cellPhone;
         this.email = email;
      }

      // Getters
      public String getName() {
         return name;
      }

      public String getHomePhone() {
         return homePhone;
      }

      public String getWorkPhone() {
         return workPhone;
      }

      public String getCellPhone() {
         return cellPhone;
      }

      public String getEmail() {
         return email;
      }
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
      // Implementation in next steps
      return "member-list";
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
git add src/main/java/org/nsponline/calendar/controller/MemberListController.java
git commit -m "feat: add MemberListController skeleton with MemberInfo model"
```

---

## Task 2: Implement Controller Request Handling and Session Validation

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/MemberListController.java`

- [ ] **Step 1: Add resort validation and session handling**

Replace the `showMemberList` method with:

```java
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

    // 5. Validate credentials
    ValidateCredentialsRedirectIfNeeded credentials =
        new ValidateCredentialsRedirectIfNeeded(
            sessionData, javaxRequest, javaxResponse,
            "MemberList", patrolData, LOG);

    if (credentials.hasInvalidCredentials()) {
        patrolData.close();
        return null; // Redirect already sent
    }

    try {
        // 6. Get logged-in user info
        String patrollerId = sessionData.getLoggedInUserId();

        // TODO: Load data and populate model (next task)

        return "member-list";

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
git add src/main/java/org/nsponline/calendar/controller/MemberListController.java
git commit -m "feat: add MemberList session validation and resort handling"
```

---

## Task 3: Implement Director Permission Check

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/MemberListController.java`

- [ ] **Step 1: Add director and emailAll permission logic**

Replace the `// TODO: Load data and populate model` comment with:

```java
        // 7. Check director status and emailAll permission
        DirectorSettings ds = patrolData.readDirectorSettings();
        Roster editor = patrolData.getMemberByID(patrollerId);
        boolean isDirector = editor != null && editor.isDirector();
        boolean showEmailButton = isDirector || (ds != null && ds.getEmailAll());

        // TODO: Load roster data (next task)
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit director permission check**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberListController.java
git commit -m "feat: add director and emailAll permission check to MemberList"
```

---

## Task 4: Implement Roster Data Loading and Active Member Filtering

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/MemberListController.java`

- [ ] **Step 1: Add roster loading and filtering logic**

Replace the `// TODO: Load roster data` comment with:

```java
        // 8. Load active members and build email list in single pass
        List<MemberInfo> members = new ArrayList<>();
        StringBuilder emailList = new StringBuilder();
        ResultSet rosterResults = patrolData.resetRoster();
        Roster member = patrolData.nextMember("&nbsp;", rosterResults);

        while (member != null) {
            // Only include active members (commitment != "0")
            if (!member.getCommitment().equals("0")) {
                // Create MemberInfo with contact details
                String name = member.getFullName_lastNameFirst();
                String homePhone = cleanField(member.getHomePhone());
                String workPhone = cleanField(member.getWorkPhone());
                String cellPhone = cleanField(member.getCellPhone());
                String email = cleanField(member.getEmailAddress());

                members.add(new MemberInfo(name, homePhone, workPhone, cellPhone, email));

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

        // 11. Build EmailForm URL with default options
        String emailFormUrl = "EmailForm?resort=" + resort + "&ID=" + patrollerId +
            "&BAS=1&INA=1&SR=1&SRA=1&ALM=1&PRO=1&AUX=1&TRA=1&CAN=1" +
            "&FullTime=1&PartTime=1&Inactive=1&ALL=1";
        model.addAttribute("emailFormUrl", emailFormUrl);
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit roster loading**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberListController.java
git commit -m "feat: add MemberList roster loading and active member filtering"
```

---

## Task 5: Create Thymeleaf Template Structure

**Files:**
- Create: `src/main/resources/templates/member-list.html`

- [ ] **Step 1: Create template with header, title, and structure**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title th:text="'List of Members of the ' + ${resortFullName} + ' Ski Patrollers'">Member List</title>
    <style type="text/css">
        body {
            font-size: 10pt;
            color: #000000;
            background-color: #ffffff;
        }
        table.member-list {
            font-size: 10pt;
            font-family: Verdana, Arial, Helvetica;
            border-width: 1px;
            border-color: #003366;
            border-style: solid;
            border-collapse: collapse;
            width: 99%;
        }
        table.member-list th {
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
        table.member-list td {
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
<body topmargin="0" leftmargin="0" style="background-image: url(/calendar-1/images/ncmnthbk.jpg)">
    <div th:replace="~{fragments/header :: header(resort=${resort}, resortFullName=${resortFullName}, patrollerId=${patrollerId})}"></div>

    <p><center><h2 th:text="'List of Members of the ' + ${resortFullName} + ' Ski Patrollers'">Member List</h2></center></p>

    <!-- Email button section - next step -->

    <!-- Table section - next step -->

    <!-- Member count - next step -->

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
git add src/main/resources/templates/member-list.html
git commit -m "feat: add MemberList Thymeleaf template structure"
```

---

## Task 6: Add Email Button to Template

**Files:**
- Modify: `src/main/resources/templates/member-list.html`

- [ ] **Step 1: Add email button section**

Replace the `<!-- Email button section - next step -->` comment with:

```html
    <!-- Email button -->
    <p th:if="${showEmailButton}">
        <b>
            <input type="button"
                   value="e-mail THESE patrollers"
                   th:data-url="${emailFormUrl}"
                   onclick="window.location=this.getAttribute('data-url')"/>
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
git add src/main/resources/templates/member-list.html
git commit -m "feat: add email button to MemberList template"
```

---

## Task 7: Add Member Table to Template

**Files:**
- Modify: `src/main/resources/templates/member-list.html`

- [ ] **Step 1: Add table with headers and member rows**

Replace the `<!-- Table section - next step -->` comment with:

```html
    <!-- Member table -->
    <table class="member-list">
        <tr>
            <th width="160">Name</th>
            <th width="90">Home</th>
            <th width="83">Work</th>
            <th width="70">Cell</th>
            <th width="136">Email</th>
        </tr>

        <!-- Empty state message -->
        <tr th:if="${members.isEmpty()}">
            <td colspan="5" style="text-align: center; padding: 20px;">
                No active members
            </td>
        </tr>

        <!-- Member rows -->
        <tr th:each="member : ${members}">
            <td th:text="${member.name}">Name</td>
            <td th:text="${member.homePhone != null and member.homePhone != '' ? member.homePhone : ''}">Home</td>
            <td th:text="${member.workPhone != null and member.workPhone != '' ? member.workPhone : ''}">Work</td>
            <td th:text="${member.cellPhone != null and member.cellPhone != '' ? member.cellPhone : ''}">Cell</td>
            <td>
                <a th:if="${member.email != null and member.email != ''}"
                   th:href="'mailto:' + ${member.email}"
                   th:text="${member.email}">email</a>
            </td>
        </tr>
    </table>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit member table**

```bash
git add src/main/resources/templates/member-list.html
git commit -m "feat: add member table with empty state to MemberList template"
```

---

## Task 8: Add Member Count Display to Template

**Files:**
- Modify: `src/main/resources/templates/member-list.html`

- [ ] **Step 1: Add member count section**

Replace the `<!-- Member count - next step -->` comment with:

```html
    <!-- Member count -->
    <br>
    <b><span th:text="${memberCount} + ' members listed.'">0 members listed.</span></b>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit member count**

```bash
git add src/main/resources/templates/member-list.html
git commit -m "feat: add member count display to MemberList template"
```

---

## Task 9: Build and Test Application

**Files:**
- Test: All MemberList components

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

- [ ] **Step 3: Test MemberList endpoint manually**

Open browser and navigate to:
```
http://localhost:8080/calendar-1/MemberList?resort=Sample&ID=12345
```

Expected behavior:
- If not logged in: redirects to MemberLogin
- If logged in: displays member list page
- Page shows "List of Members of [Resort Name] Ski Patrollers"
- Table displays with Name, Home, Work, Cell, Email columns
- If active members exist: rows display contact info
- If no active members: shows "No active members"
- Email button appears if user is director OR emailAll enabled
- Email addresses are clickable mailto links
- Inactive members (commitment == "0") are excluded

- [ ] **Step 4: Test email button**

If director or emailAll enabled, click "e-mail THESE patrollers" button

Expected:
- Redirects to EmailForm with correct parameters
- URL includes: `?resort=Sample&ID=12345&BAS=1&INA=1&SR=1&SRA=1&ALM=1&PRO=1&AUX=1&TRA=1&CAN=1&FullTime=1&PartTime=1&Inactive=1&ALL=1`

- [ ] **Step 5: Test empty state**

Test with resort that has no active members (all commitment == "0")

Expected:
- Empty state message displays
- Member count shows "0 members listed"
- Table headers still show

- [ ] **Step 6: Test email button permissions**

Test with non-director user when emailAll disabled

Expected:
- Email button is hidden
- Member list still displays

- [ ] **Step 7: Test authentication redirect**

Clear session/cookies and access MemberList

Expected:
- Redirects to MemberLogin with resort parameter

- [ ] **Step 8: Stop the application**

Press Ctrl+C in terminal

Expected: Application shuts down cleanly

---

## Task 10: Update Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update migrated endpoints section**

Find the "### Migrated Endpoints" section in CLAUDE.md and add MemberList:

```markdown
### Migrated Endpoints

- `/ListPatrollers` - Patrol member roster display (Spring MVC + Thymeleaf)
- `/MemberLogin` - Member login page with password reset (Spring MVC + Thymeleaf)
- `/VolunteerForm` - Public volunteer signup form (Spring MVC + Thymeleaf)
- `/MonthCalendar` - Monthly shift calendar (Spring MVC + Thymeleaf)
- `/Directors` - Director administration panel (Spring MVC + Thymeleaf)
- `/SubList` - Substitute patroller list (Spring MVC + Thymeleaf)
- `/MemberList` - All active members list (Spring MVC + Thymeleaf)
```

- [ ] **Step 2: Verify CLAUDE.md syntax**

Read through the updated section to ensure formatting is correct.

- [ ] **Step 3: Commit documentation update**

```bash
git add CLAUDE.md
git commit -m "docs: add MemberList to migrated endpoints in CLAUDE.md"
```

---

## Task 11: Final Verification and Tag

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

Expected: See all MemberList migration commits in order

- [ ] **Step 4: Create summary of changes**

Create a summary of what was accomplished:
- MemberListController.java created with MemberInfo model
- member-list.html Thymeleaf template created
- Session validation with ValidateCredentialsRedirectIfNeeded
- Director and emailAll permission checking
- Roster data loading with active member filtering (commitment != "0")
- Single-pass roster iteration for efficiency
- Email list building for EmailForm integration
- Responsive table display with empty state
- Member count display
- Documentation updated

- [ ] **Step 5: Optional - Create git tag**

```bash
git tag -a memberlist-migration-v1 -m "MemberList Spring Boot migration complete"
```

---

## Success Criteria Checklist

Verify all requirements from the spec are met:

- [ ] MemberList accessible at `/MemberList?resort={resort}&ID={id}`
- [ ] Displays all active members (commitment != "0")
- [ ] Excludes inactive members (commitment == "0")
- [ ] Shows contact info: name, home/work/cell phones, email
- [ ] Email button visible for directors OR users with emailAll permission
- [ ] Email button links to EmailForm with correct parameters and options
- [ ] Empty cells render as empty (no "&nbsp;")
- [ ] Email addresses are clickable mailto links
- [ ] Invalid emails display as plain text
- [ ] Redirects to login if session invalid
- [ ] Member count displays at bottom
- [ ] Styling consistent with sub-list.html
- [ ] Header and footer fragments render correctly
- [ ] Background image displays
- [ ] Legacy servlet coexists without conflicts

---

## Rollback Plan

If issues are discovered after deployment:

1. **Immediate rollback:**
   ```bash
   git revert HEAD~11..HEAD
   git push
   ```

2. **Selective rollback (if only template needs fixing):**
   ```bash
   git revert <commit-hash-of-template-change>
   ```

3. **Return to legacy servlet:**
   - Remove @GetMapping from MemberListController
   - Legacy servlet will handle /MemberList requests again

---

## Notes

- **ServletAdapter Pattern:** Used to bridge jakarta.servlet (Spring Boot 3) to javax.servlet (legacy code)
- **Resource Cleanup:** PatrolData.close() called in finally block to ensure connection cleanup
- **Email Validation:** Uses StaticUtils.isValidEmailAddress() for consistency with other controllers
- **Empty State:** Template handles zero active members gracefully with message and hidden button
- **Authentication:** Follows existing pattern from SubListController and other migrated endpoints
- **Single-Pass Optimization:** Builds member list and email list in single roster iteration
- **Active Member Filter:** Only displays members with commitment != "0" (excludes inactive)
- **EmailForm Options:** Default filter options match legacy servlet behavior
