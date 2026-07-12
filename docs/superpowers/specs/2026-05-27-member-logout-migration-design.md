# MemberLogout Spring Boot Migration Design

**Date:** 2026-05-27  
**Author:** Claude Code  
**Status:** Approved

## Overview

Migrate the MemberLogout servlet to Spring Boot controller. This is a simple action endpoint that clears authentication state (cookies and session) and redirects to the calendar. No UI template is needed since this is a pure redirect operation.

## Current State

The MemberLogout endpoint is implemented as a traditional servlet:

- **Servlet**: `src/main/java/org/nsponline/calendar/resources/MemberLogout.java` (~33 lines)
- **WebResource wrapper**: `WebResource.MemberLogout` inner class in `WebResource.java`
- **Behavior**:
  - Clears NSP_TOKEN_NAME cookie (sets maxAge=0)
  - Clears session attributes (loggedInUserId, loggedInResort)
  - Redirects to MonthCalendar with resort parameter

## Goals

1. Create Spring Boot controller to replace legacy servlet
2. Preserve exact logout behavior (cookie clearing, session clearing, redirect)
3. Remove legacy servlet code after verification
4. Add integration tests to verify logout functionality

## Design

### MemberLogoutController

Create a new Spring Boot controller with a single GET endpoint:

**Endpoint**: `/MemberLogout`  
**Method**: GET  
**Parameters**: `resort` (required)

**Logic**:
1. Validate resort parameter
2. Clear NSP_TOKEN_NAME cookie (set maxAge=0, add to response)
3. Use ServletAdapter to get javax.servlet.http.HttpServletRequest for legacy code compatibility
4. Create SessionData instance
5. Call `sessionData.clearLoggedInResort()`
6. Call `sessionData.clearLoggedInUserId()`
7. Log logout action
8. Return redirect to `/MonthCalendar?resort={resort}`

**Return**: Spring redirect string (no template needed)

### Controller Structure

```java
@Controller
public class MemberLogoutController {

    @GetMapping("/MemberLogout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        // 1. Read resort parameter
        String resort = request.getParameter("resort");
        
        // 2. Clear NSP_TOKEN_NAME cookie
        Cookie cookie = new Cookie(NSP_TOKEN_NAME, "");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        
        // 3. Use ServletAdapter for legacy compatibility
        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger LOG = new Logger(MemberLogoutController.class, javaxRequest, "GET", resort, Logger.INFO);
        
        // 4. Validate resort
        if (!PatrolData.isValidResort(resort)) {
            // Handle invalid resort
        }
        
        // 5. Clear session
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        sessionData.clearLoggedInResort();
        sessionData.clearLoggedInUserId();
        
        // 6. Log and redirect
        LOG.info("Logout redirect to MonthCalendar?resort=" + resort);
        return "redirect:/MonthCalendar?resort=" + resort;
    }
}
```

### Testing Strategy

Create `MemberLogoutControllerTest` with integration tests:

1. **Test logout action**:
   - Set up authenticated session (set loggedInUserId and loggedInResort attributes)
   - Perform GET to `/MemberLogout?resort=Sample`
   - Verify redirect to `/MonthCalendar?resort=Sample` (status 302)
   - Verify NSP_TOKEN_NAME cookie cleared (maxAge=0)

2. **Test invalid resort**:
   - Perform GET to `/MemberLogout?resort=InvalidResort`
   - Verify appropriate error handling

3. **Test without resort parameter**:
   - Perform GET to `/MemberLogout` (no resort)
   - Verify appropriate error handling

### Migration Steps

Following the established pattern from Directors migration:

1. **Create controller and tests** (verify before removing legacy code)
   - Create `MemberLogoutController.java`
   - Create `MemberLogoutControllerTest.java` with JUnit 5
   - Verify tests pass and logout works

2. **Remove legacy servlet**
   - Delete `src/main/java/org/nsponline/calendar/resources/MemberLogout.java`
   - Remove `WebResource.MemberLogout` inner class from `WebResource.java`

3. **Verify no remaining references**
   - Search for imports and references to old servlet
   - Verify application compiles and starts
   - Run full test suite

4. **Update documentation**
   - Add MemberLogout to "Migrated Endpoints" list in CLAUDE.md

## Files Affected

### Files to Create
- `src/main/java/org/nsponline/calendar/controller/MemberLogoutController.java` - Spring Boot controller
- `src/test/java/org/nsponline/calendar/controller/MemberLogoutControllerTest.java` - Integration tests

### Files to Delete
- `src/main/java/org/nsponline/calendar/resources/MemberLogout.java` - Legacy servlet (~33 lines)

### Files to Modify
- `src/main/java/org/nsponline/calendar/resources/WebResource.java` - Remove MemberLogout inner class
- `CLAUDE.md` - Add MemberLogout to migrated endpoints list

## Technical Details

### Cookie Handling

The controller must clear the NSP_TOKEN_NAME cookie exactly as the servlet does:

```java
import static utils.com.gledhills.calendar.ValidateCredentialsRedirectIfNeeded.NSP_TOKEN_NAME;

Cookie cookie = new Cookie(NSP_TOKEN_NAME, "");
cookie.

setMaxAge(0);  // Tells browser to delete the cookie
response.

addCookie(cookie);
```

### Session Clearing

Use the existing SessionData methods:

```java
SessionData sessionData = new SessionData(javaxRequest, null, LOG);
sessionData.clearLoggedInResort();
sessionData.clearLoggedInUserId();
```

### Redirect Pattern

Spring Boot controllers use redirect strings:

```java
return "redirect:/MonthCalendar?resort=" + resort;
```

This is equivalent to the servlet's:

```java
response.sendRedirect(PatrolData.SERVLET_URL + "MonthCalendar?resort=" + resort);
```

## Comparison to Similar Endpoints

### MemberLogin (already migrated)
- **Complexity**: High (login form, password reset, validation)
- **Template**: Yes (member-login.html)
- **Similarity**: Both deal with authentication state

### Directors (just migrated)
- **Complexity**: Medium (authentication, authorization, UI rendering)
- **Template**: Yes (directors-menu.html)
- **Similarity**: Both use SessionData for authentication

### MemberLogout (this migration)
- **Complexity**: Low (simple action, no UI)
- **Template**: No (pure redirect)
- **Unique aspect**: Simplest migration - no template needed

## Success Criteria

- ✅ MemberLogoutController created and handles GET /MemberLogout
- ✅ Cookie clearing works (NSP_TOKEN_NAME maxAge=0)
- ✅ Session attributes cleared (loggedInUserId, loggedInResort)
- ✅ Redirects to MonthCalendar with resort parameter
- ✅ Integration tests pass
- ✅ Legacy MemberLogout.java servlet deleted
- ✅ WebResource.MemberLogout inner class removed
- ✅ No compilation errors
- ✅ Application starts successfully
- ✅ No remaining references to legacy servlet
- ✅ CLAUDE.md updated with MemberLogout in migrated endpoints

## Risk Assessment

**Very Low Risk:**
- Simple functionality (clear cookies/session, redirect)
- No complex business logic
- No database operations
- No template rendering
- Existing SessionData and cookie handling code is well-tested
- Pattern already proven with 9 previous migrations

**Mitigation:**
- Integration tests verify logout behavior before removing servlet
- Can easily revert if issues found
- Low user impact (logout is typically infrequent operation)

## Notes

- This is the simplest migration so far - no template, minimal logic
- The controller is essentially a thin wrapper around cookie/session clearing and redirect
- No Thymeleaf template needed since there's no UI to render
- Follows the exact same cleanup pattern as Directors migration
- SessionData handles the actual session clearing logic (already tested)
