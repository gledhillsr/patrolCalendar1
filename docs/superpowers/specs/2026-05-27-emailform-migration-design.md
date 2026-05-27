# EmailForm Spring Boot Migration Design

**Date:** 2026-05-27  
**Status:** Approved  
**Approach:** Full Modernization (Approach 3)

## Overview

Migrate the EmailForm servlet to Spring MVC with Thymeleaf templates using a full modernization approach. This migration will restructure the email sending functionality into a layered architecture with Spring Boot best practices, including async email sending, modern validation, and clear separation of concerns.

## Background

EmailForm is an authenticated feature that allows patrol directors to compose and send emails to filtered lists of patrol members. It's accessed from multiple pages (SubList, ListPatrollers, MemberList) with query parameters that pre-filter recipients based on:
- Classifications (BAS, INA, SR, SRA, ALM, PRO, AUX, TRA, CAN, OTH)
- Commitment levels (FullTime, PartTime, Inactive)
- Instructor/director flags (OEC, CPR, Ski, Toboggan)
- Shift counts within date ranges (Day, Swing, Night, Training, Other, Holiday)
- Minimum days threshold

## Architecture

### Layered Design

**Controller Layer:**
- `EmailFormController` - Thin controller handling HTTP requests, delegates business logic to services
- Handles GET (display form) and POST (submit email request)
- Uses Spring validation framework
- Converts between DTOs and domain objects

**Service Layer:**
- `EmailService` - Orchestrates email composition and async sending via JavaMailSender
- `PatrolMemberFilterService` - Handles complex member filtering logic
- `ShiftAssignmentService` - Calculates shift counts for date ranges and filters by minimum days
- All services are stateless with clearly defined interfaces

**Repository/Data Layer:**
- Continue using existing `PatrolData`, `Roster`, `Assignments` classes
- Services encapsulate all database access
- Future enhancement: gradual JPA migration

**DTOs:**
- `EmailFormRequest` - Immutable request parameters (filtering criteria from query string)
- `EmailCompositionForm` - Mutable form for subject/message/recipient selection (POST body)
- `EmailRecipient` - Immutable recipient data with computed properties
- `EmailSendResult` - Async operation result with tracking information

**Domain Objects:**
- `MemberFilter` - Encapsulates filtering criteria
- `EmailMessage` - Composed email with template variable substitution

## Request Flow

### GET Request (Display Form)

1. Controller receives `EmailFormRequest` with filter parameters from query string
2. Controller validates session/authentication via existing `SessionData`
3. `PatrolMemberFilterService.filterMembers(request)` applies filtering logic, returns filtered members
4. `ShiftAssignmentService.enrichWithShiftCounts(members, dateRange)` reads assignments and calculates shift counts
5. Split members into two lists:
   - Valid email addresses (pre-selected in form)
   - Invalid email addresses (display-only warning list)
6. Controller adds data to model:
   - From member (logged-in user info)
   - Valid recipients list
   - Invalid email list
   - Filter parameters (preserved in hidden fields)
7. Render `email-form.html` template

### POST Request (Send Emails)

1. Controller receives `EmailCompositionForm` (subject, message, selected member IDs)
2. Spring validation executes (annotations on DTO fields)
3. If validation fails → return to form with error messages displayed at top
4. If valid → Controller calls `EmailService.sendEmailsAsync()`
5. EmailService:
   - Loads member data for selected IDs from PatrolData
   - Detects template variables in message body
   - Creates `EmailMessage` objects (one per recipient with variable substitution)
   - Submits batch to `@Async` method for background sending
   - Returns `EmailSendResult` with tracking ID
6. Controller redirects to confirmation page showing "Emails are being sent in background"
7. Background thread sends emails via `JavaMailSender`, logs each send operation

### Error Handling

- **Validation errors:** Display at top of form with field-level highlights (Spring validation)
- **No recipients selected:** Validation error on `patrollerIds` field
- **Invalid session:** Redirect to MemberLogin
- **Individual email failures:** Logged but don't stop batch processing (matches current behavior)

## Data Transfer Objects

### EmailFormRequest (Immutable)

Binds query parameters for recipient filtering:

```java
private final String resort;
private final String ID;  // logged-in patroller ID
private final Boolean EveryBody;
private final Boolean SubList;
private final List<String> classifications;  // BAS, INA, SR, SRA, ALM, PRO, AUX, TRA, CAN, OTH
private final Boolean FullTime;
private final Boolean PartTime;
private final Boolean Inactive;
private final Boolean ALL;
private final Boolean ListDirector;
private final Boolean OEC;
private final Boolean CPR;
private final Boolean Ski;
private final Boolean Toboggan;
private final Boolean DAY_CNT;
private final Boolean SWING_CNT;
private final Boolean NIGHT_CNT;
private final Boolean TRAINING_CNT;
private final Boolean OTHER_CNT;
private final Boolean HOLIDAY_CNT;
private final Integer StartDay;
private final Integer StartMonth;
private final Integer StartYear;
private final Integer EndDay;
private final Integer EndMonth;
private final Integer EndYear;
private final Integer MinDays;
private final Boolean MIN_DAYS;  // flag to enable MinDays filter
```

### EmailCompositionForm (Mutable)

Binds POST form data with validation:

```java
@NotBlank(message = "Subject is required")
@Size(min = 2, message = "Subject must be at least 2 characters")
private String subject;

@NotBlank(message = "Message is required")
@Size(min = 4, message = "Message must be at least 4 characters")
private String message;

@NotEmpty(message = "At least one recipient must be selected")
private List<String> patrollerIds;

private String resort;
private String fromPatrollerId;
private Integer originalPatrollerCount;  // for display purposes
```

### EmailRecipient (Immutable)

Represents a potential email recipient with computed properties:

```java
private final String id;
private final String firstName;
private final String lastName;
private final String emailAddress;
private final boolean hasValidEmail;
private final Map<String, Integer> shiftCounts;  // keyed by type: DAY, SWING, NIGHT, etc.
private final String carryOverCredits;
private final String creditsEarned;
```

### EmailSendResult

Tracks async email sending operation:

```java
private final String trackingId;
private final int totalRecipients;
private final LocalDateTime startTime;
```

## Service Design

### PatrolMemberFilterService

**Method:** `filterMembers(EmailFormRequest request, PatrolData patrolData) -> List<EmailRecipient>`

**Logic:**
1. Build `MemberFilter` object from request parameters
2. Iterate through roster via `PatrolData.resetRoster()` and `nextMember()`
3. For each member, apply filters:
   - Classification filter (BAS, INA, SR, etc.)
   - Commitment filter (FullTime=4, PartTime=2, Inactive=1 bitflags)
   - Instructor/director flags (OEC=1, CPR=2, Ski=4, Toboggan=8 bitflags)
   - Special lists (EveryBody, SubList modes)
   - Uses `Roster.okToDisplay()` method for compatibility
4. Convert matching `Roster` objects to `EmailRecipient` objects
5. Return filtered list

### ShiftAssignmentService

**Method:** `enrichWithShiftCounts(List<EmailRecipient> recipients, DateRange range, ShiftTypeFlags flags) -> List<EmailRecipient>`

**Logic:**
1. Create map of recipient ID -> recipient for fast lookup
2. Read all assignments in date range via `PatrolData.resetAssignments()` and `readNextAssignment()`
3. For each assignment in range:
   - Loop through assignment positions (MAX_ASSIGNMENT_SIZE)
   - Check if position's member ID is in recipient map
   - Increment appropriate shift counter based on type flags and shift time:
     - DAY_CNT: `Assignments.isDayShift()`
     - SWING_CNT: `Assignments.isSwingShift()`
     - NIGHT_CNT: `Assignments.isNightShift()`
     - TRAINING_CNT: `Assignments.isTrainingShift()`
     - OTHER_CNT: `Assignments.isOtherShift()`
     - HOLIDAY_CNT: `Assignments.isHolidayShift()`
4. Filter out recipients below MinDays threshold (if enabled)
5. Return enriched recipient list with updated shift counts

### EmailService

**Method:** `sendEmailsAsync(EmailCompositionForm form, List<String> memberIds, String fromMemberId, String resort) -> EmailSendResult`

**Logic:**
1. Load from member via `PatrolData.getMemberByID(fromMemberId)`
2. Validate from member has valid email address (fallback to steve@gledhills.com if not)
3. Load recipient members for selected IDs
4. Detect template variables in message:
   - `$first$`, `$last$`, `$id$`, `$carryovercredits$`, `$credit$`
5. For each recipient, create `EmailMessage`:
   - Substitute template variables with recipient data
   - Apply `fixMagicCharactersFromMicrosoftWordPaste()` to message
   - Append email footer with sender info and auto-mailer signature
6. Submit list to `@Async sendBatch()` method
7. Generate tracking ID (UUID)
8. Return `EmailSendResult` immediately

**Method:** `@Async sendBatch(List<EmailMessage> messages)`

**Logic:**
1. For each message in batch:
   - Use `JavaMailSender.send()` with MimeMessage
   - Set from address, to address, subject, body
   - Log each send operation (recipient name and email)
   - Catch individual send exceptions, log but continue batch
2. Complete asynchronously (no return to caller)

**Template Variable Substitution:**
- `$first$` → `member.getFirst()`
- `$last$` → `member.getLast()`
- `$id$` → `member.getID()`
- `$carryovercredits$` → `member.getCarryOverCredits()`
- `$credit$` → `member.getCreditsEarned()`

**Email Footer Format:**
```
----------------------------------------------
This message sent by: {fromMemberName} at ( {fromMemberEmail} )
from {fullPatrolName}'s online scheduling web site.

[If invalid return email:]
Please Don't respond to this email. SEND any responses
to: {fromMemberName}, who had an invalid email address in the system.

[If no verified email configured:]
Please Don't respond to this email. SEND any responses
to: {fromMemberEmail}. I am working on setting this 'From' address to be specific to your patrol.

This was sent from the Ski Patrol Web Site Auto Mailer.
--------------------------------------------------------
```

## UI Template Design

### email-form.html Structure

1. **Header Section** (Thymeleaf fragment)
   - Resort logo and name
   - Standard navigation

2. **Error Display Area**
   - Red alert box at top if validation errors exist
   - Use Thymeleaf `#fields.hasErrors()` to check
   - List all error messages from Spring validation

3. **Form Section** (POST to /EmailForm)
   - Hidden fields to preserve filter state:
     - resort, ID, all classification flags, commitment flags, instructor flags
     - shift type flags, date range fields, MinDays
   - From field: readonly text input showing "{name} <{email}>"

4. **Recipient Selection Table**
   - Left column:
     - Label: "To:"
     - Instructions: "Use CTRL+Click to unselect specific members"
     - Multi-select list (size=6, name="patrollerIds")
     - All options pre-selected with `selected` attribute
     - Format: "Last, First" sorted
     - Count display below: "{count} Email's to send"
   - Right column:
     - Label: "Patrollers with No Valid Email Address"
     - Display-only select list (size=5, readonly)
     - Format: "Last, First" sorted
     - Count display below: "{count} Email's SKIPPED"

5. **Email Composition**
   - Subject: text input (size=60, name="subject", required)
   - Message: textarea (rows=10, width=100%, name="message", required)

6. **Action Buttons**
   - "Send Mail" submit button with note: "(be patient, emails are being sent in background)"
   - For Sample resort: show "Disabled in Demo" disabled button instead
   - "Cancel" button with `onClick="history.back()"`

7. **Template Variable Help Section** (table)
   - Header: "Special replacement codes for email Message (must be lower case ONLY)"
   - Rows:
     - `$first$` -- replace with patrollers First Name
     - `$last$` -- replace with patrollers Last Name
     - `$id$` -- replace with patrollers ID number
     - `$carryovercredits$` -- replace with number of 'Carry Over' credits on file
     - `$credit$` -- replace with number of credits available

**Styling:**
- Use existing `/css/styles.css`
- Match visual layout of current servlet output
- Background image: `/calendar-1/images/ncmnthbk.jpg`
- Table-based layout matching directors-menu pattern

### email-confirmation.html

Simple confirmation page after POST:
- "Emails sent." heading
- Message: "Your emails are being sent in background. {totalRecipients} emails are being delivered."
- Link back to main menu or calendar

## Configuration

### Spring Boot Configuration

**JavaMailSender Bean:**
```java
@Bean
public JavaMailSender javaMailSender(SessionData sessionData) {
    JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
    mailSender.setHost(sessionData.getSmtpHost());
    // Additional SMTP configuration as needed
    return mailSender;
}
```

**Enable Async Processing:**
```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean
    public Executor emailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("email-");
        executor.initialize();
        return executor;
    }
}
```

**SMTP Settings:**
- Read from `SessionData.getSmtpHost()` (existing pattern)
- Fallback from address: steve@gledhills.com
- Use resort's `directorsVerifiedEmail` if configured in `ResortData`

## Testing Strategy

### Unit Tests

**PatrolMemberFilterServiceTest:**
- Test filtering by each classification type
- Test commitment level combinations (bitflag logic)
- Test instructor flag combinations
- Test EveryBody and SubList modes
- Test edge cases (empty roster, no matches)

**ShiftAssignmentServiceTest:**
- Test shift counting for each type (day/swing/night/training/other/holiday)
- Test date range filtering
- Test MinDays threshold filtering
- Test with overlapping assignments

**EmailServiceTest:**
- Test template variable substitution for each variable type
- Test email footer generation (valid/invalid return address cases)
- Test magic character replacement (Microsoft Word paste cleanup)
- Mock JavaMailSender to verify send calls

**DTO Validation Tests:**
- Test `@NotBlank` on subject (empty, whitespace-only)
- Test `@Size` constraints (too short subject/message)
- Test `@NotEmpty` on patrollerIds (null, empty list)

### Integration Tests

**EmailFormControllerTest:**
- Test GET with various filter combinations
- Verify recipient lists are correctly populated
- Verify valid/invalid email separation
- Test POST with valid form data (mock async sending)
- Test POST with validation errors (verify error display)
- Test Sample resort shows disabled button
- Verify redirect after successful submission
- Use HtmlUnit to parse and verify HTML structure

**End-to-End Verification:**
- Compare HTML output between servlet and controller for identical inputs
- Test all filter parameter combinations
- Verify email content matches (subject, body, footer)
- Test with real SMTP server in development environment

### Migration Verification Checklist

1. **Functional Equivalence:**
   - [ ] All filter parameters work identically
   - [ ] Recipient lists match between servlet and controller
   - [ ] Email content identical (subject, body, footer, variable substitution)
   - [ ] Error messages appear correctly
   - [ ] Sample resort disables sending

2. **Performance:**
   - [ ] Async sending completes successfully
   - [ ] Form responds immediately after submission
   - [ ] Background email sending logs progress

3. **Integration:**
   - [ ] Links from SubList, ListPatrollers, MemberList work
   - [ ] Session validation works
   - [ ] Authentication redirect works

## Backward Compatibility

**Coexistence Period:**
- Keep `InnerEmailForm` servlet active initially
- Deploy new controller at `/EmailForm` endpoint (Spring takes precedence)
- Test both implementations side-by-side
- Gradually update links in calling pages

**Link Updates Required:**
- SubList template: update EmailForm button URL
- ListPatrollers: update email link (may still be servlet-based, update when migrated)
- MemberList: update email link (may still be servlet-based, update when migrated)

**Removal Plan:**
1. Deploy new controller and verify functionality
2. Update all calling links to new endpoint
3. Monitor for 2-4 weeks
4. Remove `InnerEmailForm` servlet class
5. Remove servlet mapping from `LegacyServletConfig` or `web.xml`

## Deployment Notes

**Configuration Changes:**
- Ensure `@EnableAsync` is enabled in Spring Boot application
- Configure email thread pool size based on expected load
- Verify SMTP settings in production

**Monitoring:**
- Log each email send operation (recipient, timestamp)
- Log batch completion (total sent, total failed)
- Consider adding metrics for email send success/failure rates

**Future Enhancements:**
- Email send status endpoint (check progress via tracking ID)
- Email templates stored in database
- BCC to director for record-keeping
- Attachment support
- Rich HTML email formatting
- Scheduled email sending

## Security Considerations

**Authentication:**
- Verify session via existing `SessionData.getLoggedInUserId()`
- Redirect to login if session invalid
- No additional Spring Security required (uses existing session)

**Authorization:**
- Email form is available to authenticated patrol members
- No specific director check required (any member can email filtered lists)

**Input Validation:**
- Spring validation on all form inputs
- Email address validation via `StaticUtils.isValidEmailAddress()`
- Escape HTML in error messages (Thymeleaf handles automatically)

**Data Protection:**
- Don't log email message content (contains PII)
- Log only metadata (recipient count, timestamp)
- Validate resort parameter to prevent cross-resort access

## Open Questions / Decisions Made

1. **Email delivery approach:** Spring's JavaMailSender (Decision: Approved)
2. **Validation approach:** Spring validation framework with errors at top (Decision: Approved)
3. **Async sending:** Yes, with @Async and immediate redirect (Decision: Approved)
4. **Parameter binding:** DTO objects for type safety (Decision: Approved)
5. **Modernization level:** Full modernization with layered architecture (Decision: Approved)

## Success Criteria

1. All existing EmailForm functionality works identically in new implementation
2. Users see immediate feedback after submission (no 30-second wait)
3. All template variables substitute correctly
4. Email footers match current format
5. Filter parameters preserve state correctly
6. Valid/invalid email lists display correctly
7. Integration tests pass with 100% coverage of request flows
8. Performance improvement: form submission < 1 second (vs. 30+ seconds)
