# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Patrol Calendar is a multi-tenant web application for managing ski patrol shift scheduling across multiple ski patrol organizations. Built with Java 25, Spring Boot 4.0.5, Thymeleaf, and MySQL.

## Build & Development Commands

```bash
# Build (produces target/calendar-1.jar)
mvn clean package

# Run (starts at http://localhost:8080/calendar-1/)
java -jar target/calendar-1.jar
# or
mvn spring-boot:run

# Unit tests only (default)
mvn test

# Run a specific test
mvn test -Dtest=ClassName#testMethod
```

**Note**: The pom.xml contains an integration-test profile (`-Dintegration.test`) that references a Jetty plugin, but this profile is incompatible with Spring Boot 4 and is not functional.

## Architecture

### Multi-Tenant Design

All requests require a `?resort=<name>` parameter identifying the ski patrol organization. This maps to database table prefixes (e.g., `Sample_roster`, `Sample_assignments`) and per-resort configuration in `PatrolData.resortMap`. The resort name is threaded through every layer.

### Package Layout

```
com.gledhills.calendar/
  CalendarApplication.java       Spring Boot entry point
  controller/                    Spring MVC controllers — every served endpoint
  service/                       Business logic (EmailService, RosterQueryService, etc.)
  dto/                           Form/request/response data objects for controllers
  config/                        Spring config (AsyncConfig: email thread pool + SMTP bean;
                                 BotBlockingFilter: rejects crawlers/scanners with 403)
  store/                         Data access objects (Roster, Assignments, ShiftDefinitions,
                                 DirectorSettings, PasswordResetToken)
  utils/                         Shared utilities (PatrolData, SessionData, Logger, MailMan,
                                 AuthCookie, ValidateCredentialsRedirectIfNeeded,
                                 EventNameSanitizer)
```

There is no longer a `resources/` package: the servlet migration is complete.

### Migration Status

**The servlet migration is finished.** Every endpoint is a Spring Boot controller in `controller/`,
rendering Thymeleaf templates:
`/ChangeShiftAssignments`, `/DayShifts`, `/Directors`, `/DownloadRoster`, `/EditShiftTemplates`,
`/EmailForm`, `/ListAssignments`, `/ListPatrollers`, `/LoginHelp`, `/MemberList`, `/MemberLogin`,
`/MemberLogout`, `/MonthCalendar`, `/ProcessChangeShiftAssignments`, `/PurgeAssignments`,
`/RefresherTraining`, `/ResetPassword`, `/SetupListPatrollers`, `/SubList`, `/UpdateInfo`,
`/VolunteerForm`, `/WebSitePreferences`

Gone, and recoverable from git history if ever needed: the `resources/` package (the legacy
servlets, `ResourceBase`, the `WebResource`/`InnerWebResource` registries and the `ApiResources`
JSON API), `utils/OuterPage` with its `resortHeader1.html`/`resortFooter1.html` chrome, and
`NspSession`. `ServletAdapter` is still needed because `store/` and `utils/` remain on the old
`javax.servlet` API — that is the next cleanup if you want one.

### javax/jakarta Bridge

Legacy `store/` and `utils/` classes use the old `javax.servlet` API. Spring Boot 4 uses `jakarta.servlet`. `ServletAdapter` in `controller/` wraps Jakarta request/response/session objects to implement the Javax interfaces, allowing controllers to call into legacy store and utility code without rewriting it.

Pattern used in every controller:
```java
javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
SessionData sessionData = new SessionData(javaxRequest, null, LOG);
PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
```

### Authentication & Session Flow

- **Logged-in identity** lives in the HTTP session (`loggedInUserId` / `loggedInResort`), read and
  written through `SessionData`
- **Remember-me**: a signed `nspToken` cookie. `ValidateCredentialsRedirectIfNeeded` verifies the
  signature with `AuthCookie.verify` and, if the session is empty, logs the member back in from it.
  The signature is what stops someone typing another member's id into their browser
- Controllers gate access by constructing `ValidateCredentialsRedirectIfNeeded`, which redirects to
  `MemberLogin?resort=<name>&NSPgoto=<parent>` when credentials do not check out
- Director-only pages check `Roster.isDirector` after loading the logged-in user's record
- `BotBlockingFilter` rejects crawler/scanner user agents with 403 before any controller runs — note
  a request with **no** User-Agent counts as a bot, which matters when writing MockMvc tests

### Data Layer

Store classes manage SQL directly (no ORM). Each method receives a `PatrolData` (holds the live `Connection`) and uses the resort name as a table prefix. `PatrolData` connects to MySQL at `MYSQL_ADDRESS` (default `127.0.0.1`).

### Month Calendar

`MonthCalendarService` builds a `CalendarMonth` view model (weeks → days → shift rows) and
`month-calendar.html` draws it. The service owns every decision the old servlet baked into
markup: which days are closed (season, blackout, the "patroller id 1" closed flag), whether the
date links to DayShifts (directors only), whether a slot links to signup, and the dense
two-column weekend layout (`textLen` override, Brighton, or more than 12 slots on a day).

**Day comments are sanitized, not escaped.** The Event Name/Comment field deliberately accepts
basic HTML, and the calendar renders it with `th:utext`, so `MonthCalendarService` runs it through
`EventNameSanitizer` first. It escapes everything, then restores:

- `b/i/u/em/strong/small/sub/sup/br` — bare tags only, so no attribute can ride along
- `<a href=...>` — the one attribute allowed, and only when the anchor carries *nothing* else and
  the href starts literally with `http://`, `https://`, `mailto:` or a site-relative `/`. Rebuilt
  links get `target="_blank" rel="noopener noreferrer"`. `javascript:`, `data:`, an
  entity-obfuscated scheme (`java&#115;cript:`), `//host`, or an extra `onclick` all fail the
  check and stay visible as text.

**Anything else that renders a comment unescaped must sanitize it the same way.**

Tests: `MonthCalendarServiceTest` covers the day-cell branches with a mocked `PatrolData`
(including the XSS case), `MonthCalendarRenderTest` covers page behavior, and
`MonthCalendarGoldenTest` pins the exact HTML — re-record with
`mvn test -Dtest=MonthCalendarGoldenTest -Dgolden.update=true` and read the diff before committing.

### Shift Templates & Template Patrollers

`shiftdefinitions` holds the shift templates. A row's position lives in a `_<n>` suffix on its name
(`Saturday_0`, `Saturday_1`), so **a template name may not contain an underscore** —
`EditShiftTemplatesController` rejects it in the browser and again on the server, and
`EditShiftTemplatesControllerTest` scans the database for existing offenders.

`ShiftTemplatePatrollers` (table `shifttemplatepatrollers`, keyed `EventName` + `Position`) stores
who a director pencilled into each template slot. The table **self-creates via
`CREATE TABLE IF NOT EXISTS` on first use**, the same way `PasswordResetToken` does, so no resort
needs a manual migration. A failed create degrades to "no template patrollers" rather than breaking
the page.

Template patrollers are a starting point, not a schedule. They become real only when written into
`assignments` for a date — which is what `ListPatrollers` counts and, importantly, what
`DailyReminder` emails about. Two paths write them:

- **A day built from its day-of-week template**: `DayShiftsController.populateAssignmentsFromTemplate`
  seeds the displayed rows, and `saveShiftInfo` persists them. The row form carries no patroller
  fields, so they are threaded through the save rather than read back from the request.
- **`ShiftTemplateApplyService`** ("Apply to calendar..." on EditShiftTemplates): a date range, the
  weekday in the name picking the days out of it — a `Monday` template lands on every Monday in the
  range and nowhere else. The button is dead for any other template name, since nothing else has a
  weekday to match. The panel is a date range and one **Apply** button, which writes immediately.

  A matching day that is **already scheduled is never quietly rewritten**. Apply stops, names those
  days by date, and offers a skip/overwrite radio (skip checked): `ConflictMode.SKIP` leaves them
  and fills in only the empty days, `ConflictMode.OVERWRITE` deletes their rows, losing the
  patrollers on them, and rebuilds from the template. Pressing Apply again commits — the question's
  hidden `conflictAnswered` field is what tells the controller the answer is real, so Apply cannot
  write past an unanswered question. Overwrite is only offered when the template actually carries
  patrollers (an empty template has nothing to push out, so clobbering a set-up day could only lose
  work), and `ConflictMode.of` reads anything but a literal `"overwrite"` as skip, so no missing or
  mangled parameter can be the thing that deletes a shift.

**Real assignments win unless a director explicitly says otherwise.** Template patrollers only ever
fill a position that had nothing in it; the confirmed overwrite above is the one exception, and it
is a whole-day replace rather than a merge. Ids no longer on the roster are dropped, since the
calendar would otherwise render them as `Err id (...)`.

Replacing a day wholesale is the other destructive path: the shift-format dropdown on DayShifts
confirms how many patrollers will be removed, then POSTs `replaceShiftFormat` (a POST, not a URL, so
a refresh cannot repeat it). `processReplaceShiftFormat` deletes the day's rows — patrollers live on
them — and applies the chosen template. A name matching no template leaves the day empty, reverting
it to the day-of-week template. There is deliberately **no bulk replace** across a date range.

### Roster Listing & Download

`RosterQueryService` turns the ListPatrollers query parameters (filters, column choices, sort) into
the list of members to show. Both `ListPatrollersController` and `DownloadRosterController` call it,
so the `.xlsx` a director downloads holds exactly the rows and columns the page displayed —
the download button echoes the page's parameters through in its URL.

`RosterExcelExporter` writes the workbook with Apache POI (`poi-ooxml`). Its column list mirrors
`templates/list-patrollers.html`: **a column added to the page must be added there too.**

### Email

`EmailService` sends via Spring's `JavaMailSender` (configured in `AsyncConfig` using `smtp.host` from `application.properties`). Director-triggered bulk emails are `@Async` on the `emailTaskExecutor` thread pool. Password reset emails use one-time tokens stored in `{resort}_password_reset_tokens` (24-byte random, 15-minute expiry, single-use). The lifetime lives in `PasswordResetToken.LIFETIME_MINUTES` and is quoted to the member in the email — change it in that one place.

**EmailForm posts one multipart part per selected recipient**, because the recipient picker is a
multi-select bound to `patrollerIds`. Tomcat 11 caps a multipart request at **50 parts** and 1000
parameters, so a bulk email to a real roster blew straight through both and came back as a bare
`413`. `server.tomcat.max-part-count` / `max-parameter-count` in `application.properties` raise the
caps; `max-http-form-post-size` still holds the non-file part data to 2MB, so the count is not a
memory hole. **Anything that adds per-item fields to a form should be counted against those two
limits**, and note the failure mode is a raw 413 with nothing in the app log but a resolver warning.

Multipart bodies are parsed in `DispatcherServlet.checkMultipart`, **before** a handler is chosen,
so a parse failure has no controller and only `@ControllerAdvice` beans are consulted —
`UploadTooLargeAdvice` is where the friendly "attachments too large" redirect has to live. It reads
`resort`/`fromPatrollerId` out of `request.getQueryString()` by hand on purpose: calling
`getParameter` on a request whose multipart body failed to parse makes the container rethrow that
same parse failure, which takes the handler down *silently* (Spring sees its own exception come
back and quietly drops to the default resolver) and puts the user back on the raw 413.

MockMvc never parses a multipart body — it calls the controller directly — so none of this is
reachable from the usual `@SpringBootTest` + MockMvc tests. `EmailFormPartCountTest` runs a real
Tomcat on a random port and posts a hand-built multipart body; that is the only kind of test that
catches these.

**A batch logs in to SMTP once.** `JavaMailSenderImpl.send(MimeMessage)` connects, authenticates,
sends and disconnects on *every* call, so sending in a loop meant one Gmail login per recipient —
and at around the 80th, Gmail answers `454-4.7.0 Too many login attempts` and every remaining
message fails. `EmailService.SmtpConnection` holds one authenticated `Transport` open for the whole
batch and reconnects once if the server drops it (Gmail does, on long or large batches). Note the
protocol has to be read the way Spring's own protected `getTransport` reads it —
`getProtocol()` is null unless `setProtocol` was called, and `AsyncConfig` configures it through
`mail.transport.protocol` instead. `EmailServiceSmtpConnectionTest` runs a stub SMTP server and
counts logins, which is the only way to see any of this from a test.

**A rejection of the sender stops the batch; a rejection of one address does not.**
`isFatalToTheBatch` matches deliberately narrow signatures — `AuthenticationFailedException`,
"too many login attempts", `5.4.5`, "daily user sending" — because those apply to every remaining
message, and ploughing on turns one problem into one failure per member. A `550 5.1.1` for a dead
mailbox must *not* match, or one stale address would abandon everyone behind it in the list.
**Widening that matcher is how you break the whole batch for one bad address.**

**A batch that did not go out cleanly emails a summary** to the director who pressed Send (the
`replyToEmail`, when they have a usable address) and to `ADMIN_NOTIFICATION_ADDRESS`, saying how
many got through, why it stopped, how many were never attempted, and which addresses failed. This
exists because the confirmation page reports how many recipients were **queued**, not sent — a
director could watch a send half-fail and never know. The summary goes out on its own fresh
connection, and when the mail server itself is the problem it cannot get through either; the log is
the fallback and still holds every individual failure.

### Thymeleaf Templates

Located in `src/main/resources/templates/`. Shared page chrome is in `templates/fragments/`. Static assets are in `src/main/resources/static/`.

## Adding a New Endpoint

1. Create controller in `controller/` (plain `@Controller`, no base class)
2. Use `ServletAdapter.toJavaxRequest/toJavaxResponse` to pass into legacy store/util code
3. Create Thymeleaf template in `templates/`
4. There is no `web.xml` — Spring Boot auto-registers `@Controller` beans

## Key Configuration

- **Database**: `PatrolData.MYSQL_ADDRESS` — update to point at your MySQL instance
- **SMTP**: `smtp.host` in `application.properties` (default `localhost`)
- **New resorts**: Add entries to `PatrolData.resortMap` static initializer plus matching DB tables
- **`doReminder.sh`**: Standalone cron script for daily reminders. Runs `DailyReminder.main()` against the same executable jar the app runs from (`calendar-1.jar`), via Spring Boot's `PropertiesLauncher` (`-Dloader.main=com.gledhills.calendar.DailyReminder`) so it reuses the app's runtime classpath with no jar list to maintain. It runs `-Xmx128m -XX:+UseSerialGC` (override with `CALENDAR_JAVA_OPTS`) because **it shares a 1GB box with the web app and MySQL**: an uncapped JVM takes a quarter of system RAM as its max heap, and a second one of those is enough to invoke the kernel OOM killer — which kills the largest process, i.e. the web app or MySQL, not this script. **The app's own service unit needs a matching `-Xmx`;** without one, both JVMs size themselves from total RAM and the box has no headroom left
