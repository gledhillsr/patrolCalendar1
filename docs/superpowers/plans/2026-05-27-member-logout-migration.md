# MemberLogout Spring Boot Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate MemberLogout servlet to Spring Boot controller with integration tests, then remove legacy servlet.

**Architecture:** Create MemberLogoutController with single GET endpoint that clears NSP_TOKEN_NAME cookie, clears session attributes via SessionData, and redirects to MonthCalendar. No template needed (pure redirect). Follow the pattern from Directors migration.

**Tech Stack:** Spring Boot 4.0.5, JUnit 5, MockMvc, ServletAdapter for javax/jakarta compatibility

---

## File Structure

**Files to Create:**
- `src/main/java/org/nsponline/calendar/controller/MemberLogoutController.java` - Spring Boot controller (~50 lines)
- `src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java` - Integration tests (~80 lines)

**Files to Delete:**
- `src/main/java/org/nsponline/calendar/resources/MemberLogout.java` - Legacy servlet (33 lines)

**Files to Modify:**
- `src/main/java/org/nsponline/calendar/resources/WebResource.java` - Remove MemberLogout inner class (lines ~143-151)
- `CLAUDE.md` - Add MemberLogout to migrated endpoints list

---

## Task 1: Create MemberLogoutController

**Files:**
- Create: `src/main/java/org/nsponline/calendar/controller/MemberLogoutController.java`

- [ ] **Step 1: Create controller class with imports and structure**

```java
package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

/**
 * Spring Boot controller for member logout.
 * Migrated from MemberLogout servlet.
 *
 * Handles GET /MemberLogout - clears authentication state and redirects to calendar
 */
@Controller
public class MemberLogoutController {

    @GetMapping("/MemberLogout")
    public String logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Implementation in next step
        return null;
    }
}
```

- [ ] **Step 2: Implement logout logic**

Replace the logout method with:

```java
    @GetMapping("/MemberLogout")
    public String logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 1. Read resort parameter
        String resort = request.getParameter("resort");

        // 2. Clear NSP_TOKEN_NAME cookie
        Cookie cookie = new Cookie(NSP_TOKEN_NAME, "");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        // 3. Use ServletAdapter for legacy compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger LOG = new Logger(MemberLogoutController.class, javaxRequest, "GET", resort, Logger.INFO);
        LOG.warn("Deleting cookie in member logout");

        // 4. Validate resort
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Invalid resort: " + resort);
            return null;
        }

        // 5. Clear session
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        sessionData.clearLoggedInResort();
        sessionData.clearLoggedInUserId();

        // 6. Log and redirect
        LOG.info("Logout redirect to MonthCalendar?resort=" + resort);
        return "redirect:/MonthCalendar?resort=" + resort;
    }
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit controller**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberLogoutController.java
git commit -m "feat: add MemberLogoutController

Spring Boot controller for logout endpoint.
Clears NSP_TOKEN_NAME cookie, clears session attributes,
and redirects to MonthCalendar.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 2: Create MemberLogoutControllerTest Skeleton

**Files:**
- Create: `src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java`

- [ ] **Step 1: Create test class with Spring Boot test setup**

```java
package org.nsponline.calendar.controller;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Integration tests for MemberLogoutController.
 * Verifies cookie clearing, session clearing, and redirect behavior.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class MemberLogoutControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private MockHttpSession session;

    @BeforeEach
    public void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).build();
        session = new MockHttpSession();
    }

    // Tests will be added in subsequent tasks
}
```

- [ ] **Step 2: Verify test compiles**

Run: `mvn test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit test skeleton**

```bash
git add src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java
git commit -m "test: add MemberLogoutController test skeleton

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 3: Add Logout Redirect Test

**Files:**
- Modify: `src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java`

- [ ] **Step 1: Write test for logout redirect**

Add this test method to the class:

```java
    /**
     * Test that logout redirects to MonthCalendar with resort parameter.
     */
    @Test
    public void testLogoutRedirectsToMonthCalendar() throws Exception {
        // Set up authenticated session
        session.setAttribute("loggedInUserId", "12345");
        session.setAttribute("loggedInResort", "Sample");

        // Perform logout
        mockMvc.perform(get("/MemberLogout")
                .param("resort", "Sample")
                .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/MonthCalendar?resort=Sample"));
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=MemberLogoutControllerTest#testLogoutRedirectsToMonthCalendar`
Expected: Test may pass or fail depending on database availability. The redirect check should work.

- [ ] **Step 3: Commit test**

```bash
git add src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java
git commit -m "test: add logout redirect test

Verifies that logout redirects to MonthCalendar with resort parameter.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 4: Add Cookie Clearing Test

**Files:**
- Modify: `src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java`

- [ ] **Step 1: Write test for cookie clearing**

Add this test method:

```java
    /**
     * Test that logout clears the NSP_TOKEN_NAME cookie.
     */
    @Test
    public void testLogoutClearsCookie() throws Exception {
        // Set up authenticated session
        session.setAttribute("loggedInUserId", "12345");
        session.setAttribute("loggedInResort", "Sample");

        // Perform logout
        MvcResult result = mockMvc.perform(get("/MemberLogout")
                .param("resort", "Sample")
                .session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // Verify cookie was cleared (maxAge=0)
        Cookie[] cookies = result.getResponse().getCookies();
        assertNotNull(cookies, "Response should contain cookies");
        
        boolean foundClearedCookie = false;
        for (Cookie cookie : cookies) {
            if ("nspToken".equals(cookie.getName())) {
                assertEquals(0, cookie.getMaxAge(), "Cookie maxAge should be 0 to clear it");
                assertEquals("", cookie.getValue(), "Cookie value should be empty");
                foundClearedCookie = true;
                break;
            }
        }
        assertTrue(foundClearedCookie, "Should have found nspToken cookie being cleared");
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=MemberLogoutControllerTest#testLogoutClearsCookie`
Expected: Test should pass - verifies cookie clearing behavior

- [ ] **Step 3: Commit test**

```bash
git add src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java
git commit -m "test: add cookie clearing test for logout

Verifies that NSP_TOKEN_NAME cookie is cleared (maxAge=0).

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 5: Add Invalid Resort Test

**Files:**
- Modify: `src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java`

- [ ] **Step 1: Write test for invalid resort**

Add this test method:

```java
    /**
     * Test that logout with invalid resort returns error.
     */
    @Test
    public void testLogoutWithInvalidResort() throws Exception {
        mockMvc.perform(get("/MemberLogout")
                .param("resort", "InvalidResort"))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=MemberLogoutControllerTest#testLogoutWithInvalidResort`
Expected: Test should pass - verifies error handling

- [ ] **Step 3: Commit test**

```bash
git add src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java
git commit -m "test: add invalid resort test for logout

Verifies that logout with invalid resort returns 400 error.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 6: Run All Tests to Establish Baseline

**Files:**
- N/A (testing only)

- [ ] **Step 1: Run all MemberLogoutControllerTest tests**

Run: `mvn test -Dtest=MemberLogoutControllerTest`
Expected: Tests should pass (redirect and cookie tests demonstrate controller works)

- [ ] **Step 2: Run all unit tests**

Run: `mvn clean test`
Expected: All existing tests plus new logout tests should pass or have documented expected failures

- [ ] **Step 3: Document test results**

Note which tests pass and which fail (if any). This establishes the baseline before removing legacy code.

---

## Task 7: Delete Legacy MemberLogout Servlet

**Files:**
- Delete: `src/main/java/org/nsponline/calendar/resources/MemberLogout.java`

- [ ] **Step 1: Verify the file exists**

Run: `ls -la src/main/java/org/nsponline/calendar/resources/MemberLogout.java`
Expected: File exists (~33 lines)

- [ ] **Step 2: Delete the legacy servlet file**

Run: `git rm src/main/java/org/nsponline/calendar/resources/MemberLogout.java`
Expected: File staged for deletion

- [ ] **Step 3: Verify compilation still works**

Run: `mvn clean compile`
Expected: BUILD SUCCESS (Spring Boot controller handles /MemberLogout endpoint)

- [ ] **Step 4: Commit deletion**

```bash
git commit -m "refactor: remove legacy MemberLogout servlet

Spring Boot MemberLogoutController has replaced this servlet.
Integration tests verify the new implementation works.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 8: Remove WebResource.MemberLogout Inner Class

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/resources/WebResource.java`

- [ ] **Step 1: Find the MemberLogout inner class**

Run: `grep -n "class MemberLogout" src/main/java/org/nsponline/calendar/resources/WebResource.java`
Expected: See line number where MemberLogout inner class is defined (approximately line 143)

- [ ] **Step 2: Remove the MemberLogout inner class**

Remove these lines from WebResource.java (the entire MemberLogout inner class including JavaDoc):

```java
  /**
   * @author Steve Gledhill
   * <p>
   * clear cookies, and push to MonthCalendar (no longer logged in)
   */
  public static class MemberLogout extends HttpServlet {

    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
      Logger LOG = new Logger(this.getClass(), request, "GET", request.getParameter("resort"), Logger.INFO);
      new org.nsponline.calendar.resources.MemberLogout(request, response, LOG);
    }
  }
```

Use the Edit tool to remove this section.

- [ ] **Step 3: Verify compilation**

Run: `mvn clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit the removal**

```bash
git add src/main/java/org/nsponline/calendar/resources/WebResource.java
git commit -m "refactor: remove WebResource.MemberLogout servlet wrapper

Removed servlet wrapper that routed to legacy MemberLogout.java.
Spring Boot MemberLogoutController now handles all /MemberLogout requests.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 9: Verify No Remaining References

**Files:**
- N/A (verification only)

- [ ] **Step 1: Search for any remaining references to MemberLogout servlet**

Run: `grep -r "MemberLogout\.java\|new.*MemberLogout\(" src/main/java --include="*.java" || echo "No references found"`
Expected: "No references found" (or only MemberLogoutController references)

- [ ] **Step 2: Search for imports of the old servlet**

Run: `grep -r "import.*resources\.MemberLogout" src --include="*.java" || echo "No imports found"`
Expected: "No imports found"

- [ ] **Step 3: Search for references in comments or documentation**

Run: `grep -r "MemberLogout servlet" src/main/java --include="*.java" | grep -v "MemberLogoutController" || echo "No references found"`
Expected: "No references found"

- [ ] **Step 4: Document verification results**

If any unexpected references are found, they should be removed or updated to reference MemberLogoutController.

---

## Task 10: Run Full Test Suite

**Files:**
- N/A (testing only)

- [ ] **Step 1: Run all unit tests**

Run: `mvn clean test`
Expected: All tests pass (same or better results than baseline in Task 6)

- [ ] **Step 2: Run all tests including compilation**

Run: `mvn clean package`
Expected: BUILD SUCCESS with all tests passing

- [ ] **Step 3: Check for any warnings related to MemberLogout**

Review the Maven output for any warnings or errors mentioning "MemberLogout" or "Logout"
Expected: No MemberLogout servlet warnings

---

## Task 11: Verify Spring Boot Startup

**Files:**
- N/A (runtime verification)

- [ ] **Step 1: Start the Spring Boot application**

Run: `mvn spring-boot:run`
Expected: Application starts successfully on port 8080

- [ ] **Step 2: Check startup logs for MemberLogout endpoint**

Watch the console output for MemberLogoutController mapping
Expected: Should see mapping for GET /MemberLogout

- [ ] **Step 3: Stop the application**

Press Ctrl+C to stop the application

---

## Task 12: Update CLAUDE.md Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Find the Migrated Endpoints section**

Run: `grep -n "Migrated Endpoints" CLAUDE.md`
Expected: See line number of the Migrated Endpoints section

- [ ] **Step 2: Add MemberLogout to the list**

Add this line to the Migrated Endpoints section (after UpdateInfo):

```markdown
- `/MemberLogout` - Member logout action (Spring Boot + redirect)
```

- [ ] **Step 3: Verify the change**

Run: `grep "MemberLogout" CLAUDE.md`
Expected: See the new line in the migrated endpoints list

- [ ] **Step 4: Commit documentation update**

```bash
git add CLAUDE.md
git commit -m "docs: add MemberLogout to migrated endpoints

MemberLogout servlet has been migrated to Spring Boot controller.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 13: Final Verification and Tagging

**Files:**
- N/A (final verification)

- [ ] **Step 1: Run final complete build**

Run: `mvn clean package`
Expected: BUILD SUCCESS

- [ ] **Step 2: Review git log**

Run: `git log --oneline -10`
Expected: See commits for:
- Controller creation
- Test skeleton and tests
- Legacy servlet deletion
- WebResource.MemberLogout removal
- Documentation update

- [ ] **Step 3: Verify clean working directory**

Run: `git status`
Expected: Clean working directory (all changes committed)

- [ ] **Step 4: Optional: Tag completion**

Run: `git tag -a member-logout-migration-complete -m "MemberLogout servlet migration to Spring Boot complete"`

---

## Success Criteria Checklist

After completing all tasks, verify:

- [x] MemberLogoutController created with GET /MemberLogout endpoint
- [x] Cookie clearing works (NSP_TOKEN_NAME maxAge=0)
- [x] Session attributes cleared via SessionData
- [x] Redirects to MonthCalendar with resort parameter
- [x] Integration tests created and pass
- [x] Legacy MemberLogout.java servlet deleted
- [x] WebResource.MemberLogout inner class removed
- [x] No compilation errors
- [x] Application starts successfully
- [x] No remaining references to legacy servlet
- [x] CLAUDE.md updated with MemberLogout in migrated endpoints
- [x] Git history shows clean commits for each step

## Notes

**Test Expectations:**
- Redirect test should pass reliably
- Cookie clearing test should pass reliably
- Invalid resort test should pass reliably
- Tests are simpler than Directors tests (no template rendering, no database queries)

**Key Differences from Directors Migration:**
- No template file (pure redirect)
- Simpler logic (just cookie/session clearing + redirect)
- Fewer test scenarios needed
- No authorization checks (logout is always allowed)

**SessionData Behavior:**
- SessionData constructor may try to read credentials.properties
- This is fine - SessionData.clearLoggedInUserId() and clearLoggedInResort() work regardless
- No database connection needed for logout (unlike Directors which queries roster)
