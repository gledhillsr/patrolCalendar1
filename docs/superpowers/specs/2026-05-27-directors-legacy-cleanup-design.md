# Directors Legacy Servlet Cleanup Design

**Date:** 2026-05-27  
**Author:** Claude Code  
**Status:** Approved

## Overview

Remove the legacy `Director.java` servlet and its references after successful migration to Spring Boot with DirectorsController. Verify the Spring Boot implementation works correctly through comprehensive integration tests.

## Current State

The Directors endpoint has been migrated to Spring Boot but the legacy servlet remains:

- ✅ **Spring Boot controller** exists: `DirectorsController.java` with `@GetMapping("/Directors")`
- ✅ **Thymeleaf template** exists: `directors-menu.html`
- ❌ **Legacy servlet** still exists: `Director.java` in `resources/` package
- ❌ **WebResource wrapper** still references legacy servlet (lines 78-83 in `WebResource.java`)
- ⚠️ Listed in CLAUDE.md as migrated, but cleanup incomplete

## Goals

1. Remove all legacy servlet code for Directors endpoint
2. Verify Spring Boot DirectorsController works correctly via integration tests
3. Ensure no regression in functionality
4. Clean documentation and remove obsolete references

## Scope of Changes

### Files to Remove

1. **`src/main/java/org/nsponline/calendar/resources/Director.java`**
   - The legacy servlet implementation (~300 lines)
   - Extends `ResourceBase` and uses legacy HTML printing

2. **`WebResource.Directors` inner class**
   - Lines 74-83 in `src/main/java/org/nsponline/calendar/resources/WebResource.java`
   - Servlet wrapper that routes GET requests to `Director.java`

### Files to Update

1. **`src/main/java/org/nsponline/calendar/resources/WebResource.java`**
   - Remove `Directors` inner class (lines 74-83)
   - Update class-level JavaDoc if it references Directors servlet

### Files to Create

1. **`src/test/java/org/nsponline/calendar/controller/DirectorsControllerTest.java`**
   - Integration tests for the Spring Boot DirectorsController
   - Cover authentication, authorization, and rendering scenarios

## Testing Strategy

### Integration Test Design

Create `DirectorsControllerTest.java` using Spring Boot testing framework:

**Test Framework:**
- `@SpringBootTest` - Full application context
- `@AutoConfigureMockMvc` - Mock HTTP requests
- `MockMvc` - Perform HTTP requests and verify responses

**Test Scenarios:**

1. **Authentication Required Test**
   - Request `/Directors?resort=Sample` without session
   - Verify redirect to `MemberLogin`
   - Status: 302 redirect

2. **Non-Director Access Test**
   - Request with valid session for non-director user
   - Verify "not authorized" message displayed
   - Verify only "Customized Listing" button available
   - Status: 200 OK

3. **Director Access Test**
   - Request with valid session for director user
   - Verify full directors menu displayed
   - Verify three main sections present:
     - Patroller Maintenance
     - Patrol Listings
     - Patrol Setup / Cleanup
   - Verify roster dropdown populated
   - Status: 200 OK

4. **Backdoor User Test**
   - Request with backdoor user credentials
   - Verify director access granted
   - Status: 200 OK

### Test Data Strategy

**Mock/Test Fixtures:**
- Use test database or mock `PatrolData` connections
- Create test users: director, non-director, backdoor
- Create test resort: "Sample"
- Mock session data via cookies or session attributes

### Verification Steps

1. **Unit tests:** `mvn test` - must pass
2. **Integration tests:** `mvn clean package -Dintegration.test` - must pass
3. **Compilation:** Verify no compilation errors after removal
4. **Spring Boot startup:** Verify clean startup without warnings
5. **Manual smoke test:** Access `/Directors?resort=Sample` in browser

## Implementation Plan

### Phase 1: Create Integration Tests

1. Create `DirectorsControllerTest.java`
2. Implement test helper methods for authentication/session setup
3. Write all four test scenarios
4. Run tests to establish baseline (should pass with current controller)

### Phase 2: Remove Legacy Code

1. Delete `src/main/java/org/nsponline/calendar/resources/Director.java`
2. Remove `WebResource.Directors` inner class (lines 74-83)
3. Clean up any JavaDoc references to Directors servlet

### Phase 3: Verification

1. Run `mvn clean test` - verify all tests pass
2. Run `mvn clean package -Dintegration.test` - verify integration tests pass
3. Run `mvn spring-boot:run` - verify clean startup
4. Manual test: Access `/Directors?resort=Sample` and verify functionality
5. Check logs for any warnings or errors related to Directors

## Success Criteria

- ✅ All unit tests pass
- ✅ All integration tests pass
- ✅ No compilation errors
- ✅ Spring Boot app starts without warnings about Directors
- ✅ `/Directors` endpoint accessible via Spring Boot controller
- ✅ No remaining references to legacy `Director.java` servlet in codebase
- ✅ Directors menu displays correctly for director users
- ✅ Non-director users see appropriate "not authorized" message
- ✅ Authentication properly redirects to login

## Risk Assessment

**Low Risk:**
- Spring Boot controller already exists and is functional
- Template already exists and renders correctly
- Only removing unused legacy code

**Mitigation:**
- Comprehensive integration tests before removal
- Git allows easy rollback if issues discovered
- Legacy servlet not removed until tests confirm controller works

## Migration Pattern Consistency

This cleanup follows the established migration pattern used for other endpoints:

1. ✅ ListPatrollers - migrated and cleaned up
2. ✅ MemberLogin - migrated and cleaned up
3. ✅ VolunteerForm - migrated and cleaned up
4. ✅ MonthCalendar - migrated and cleaned up
5. ✅ SubList - migrated and cleaned up
6. ✅ MemberList - migrated and cleaned up
7. ✅ ListAssignments - migrated and cleaned up
8. ✅ UpdateInfo - migrated and cleaned up
9. 🔄 **Directors** - migrated, cleanup in progress

## Notes

- The `Director.java` servlet uses legacy HTML printing with `printCommonHeader()` and `printBody()`
- DirectorsController uses modern Thymeleaf templates
- Both implementations have identical functionality
- WebResource.java contains wrappers for many other servlets that may need similar cleanup in the future
