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

**Nobody holds two slots on the same shift.** Both bulk grids — "Assign Patrollers to Today's
Shifts" on DayShifts and "Assign Patrollers to this template" on EditShiftTemplates — enforce it,
in the browser when a name is inserted and again on save, because the browser check can be
bypassed. The save drops the duplicate and keeps the first slot rather than rejecting the form.
**A shift is keyed by its times, not by its row** (`shiftKey` in both controllers): a shift needing
more than 10 patrollers is entered as several rows sharing a start and end time, and those are one
shift to the person working it. The key has to match the time string the grid renders, since that
is what the browser groups by. Someone may of course work 8am *and* 4pm. Covered by
`NoDuplicateOnAShiftTest`.

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

**"e-mail THESE patrollers" must mail exactly who the page listed.** Two pages carry that button and
neither shares a filter implementation with EmailForm, so they drift silently — three separate
mismatches have already been fixed, all of which mailed people the page never showed:

- `ListPatrollersController.buildEmailFormUrl` re-emits the page's filters as query parameters. It
  must forward `MIN_DAYS` for **every** counter the setup page allows it on (day/swing/night/
  training/other/holiday) — gating on day/swing/night alone sent EmailForm no limit and no date
  range, and no limit means everybody.
- `ShiftAssignmentService.filterByShiftLimit` keeps `count < limit`. The checkbox reads "Only list
  if less than N shifts" and `RosterQueryService.applyMinDaysFilter` keeps `< limit`; when this held
  `>= limit` the email went to the exact **complement** of the page. `EmailFormRequest`'s accessor
  is still named `minShiftsThreshold()` — it is a ceiling, not a floor.
- The **Patrollers tab in the header goes to `MemberList`, not `ListPatrollers`**, and builds its own
  EmailForm URL. Both the listing loop and that URL come from `MemberListController
  .LISTED_CLASSIFICATIONS` (`BAS SR SRA PRO AUX TRA CAN` — Alumni, Other and the legacy Inactive
  code are excluded), so they cannot drift; the loop also skips commitment `"0"`, which is why the
  URL carries `FullTime`/`PartTime` but never `Inactive=1`. **Change the list, not either copy.**

`EmailListMatchesPatrollerListTest` walks the real route — reads the page, pulls the button's URL
out of it, follows it, compares the two name lists. Note the Sample roster has no inactive member,
so the inactive case is pinned on the URL rather than on the lists.

### Viewing Another Patroller's History

`ListAssignments` normally shows the logged-in member their own shifts. The Directors page's
"Show Patrollers History ..." button submits the *same form* the Edit/Delete buttons use — via
`formaction="ListAssignments" formmethod="get"` on that one button — so the roster `<select>`
arrives as `NameToEdit` without the form having to stop posting to `UpdateInfo` for everything else.

The controller honours `NameToEdit` **only when the logged-in member is a director**; for anyone
else it is logged and ignored, so a hand-typed URL shows the asker their own shifts rather than
somebody else's. The name is resolved with `getMemberByName2`, which expects the `"Last, First"`
format the Directors roster builds. When the parameter is honoured the page grows a **Done** button
back to Directors, and the season toggle link carries `NameToEdit` along — without that, toggling
would silently switch the page back to the director's own history.

### Custom Menu Buttons

`ButtonMaintenanceController` ("Menu Configuration" on the Directors page, `/ButtonMaintenance`)
lets a resort define up to `MenuButton.MAX_BUTTONS` (4) of its own header buttons: text, url,
**Member Only** and **Enabled**. Director-only, since these appear in every member's header.

Stored in `menubuttons`, keyed by `Position` 1..4 — a slot rather than a list entry, so clearing
the middle one renumbers nothing. The table **self-creates via `CREATE TABLE IF NOT EXISTS`**, like
`ShiftTemplatePatrollers` and `PasswordResetToken`, so no resort needs a migration; a failed create
degrades to "no custom buttons". A blank slot is deleted rather than stored.

**A url is validated on save with `EventNameSanitizer.isSafeUrl`** — the same test the calendar's
day comments use — so `javascript:`, `data:` and `//host` are refused at the point they are typed
rather than sanitized later. That method was made public for this; **anywhere a link is built from
something a person typed should use it.**

**There is now one header for every resort.** `brighton-header` is gone: its logo and title were
already in `ResortData` under identical values, and its three extra buttons — Volunteer Application,
Weather, Refresher/Training — are rows in Brighton's `menubuttons`. `(Members Only):` labels the
members bar for everybody now, which Brighton alone used to show.

`fragments/header.html` draws them in two places, from one `MenuButtonService.forHeader` call:
**public** buttons in the light grey bar above the menu (drawn only when this viewer has one, so
nobody gets an empty strip), and **Member Only** buttons in the members bar *between Directors and
Logout*. The service is called straight from the template — `${@menuButtonService.forHeader(...)}` —
because every page draws this header and threading a list through twenty controllers means the
twenty-first forgets. It never throws: a header that cannot reach the database draws no custom
buttons rather than taking the whole app down.

Two things that bite tests: the header now depends on a **database table**, so
`MonthCalendarGoldenTest` blanks the menu buttons for its run and restores them (a golden recorded
while somebody had a button configured would fail when they removed it), and any test that writes
buttons must **snapshot and restore** rather than blanking — a director's real configuration is not
the test's to delete.

### Site Wide Configuration

`SiteWideConfigurationController` (`/SiteWideConfiguration`) holds jobs that walk **every** resort
in `PatrolData.resortMap`, not just the one whose page you are on. The Directors button and the
controller both gate on `isSiteOwner` — resort `Brighton` **and** id `192443` together. "Is a
director" would not do: every resort has directors, and none of them should reach into the other 47
databases.

Its one job creates the self-made tables everywhere — currently `menubuttons` and
`shifttemplatepatrollers`, via each class's `createTable`. It is idempotent
(`CREATE TABLE IF NOT EXISTS`) and reports per resort rather than stopping at the first failure — a
resort in the map may have no database yet.

**The migration this enables:** once every resort reports ok for a table, that class's
create-on-first-use call can come out of its read and write paths. `MenuButton`,
`ShiftTemplatePatrollers` and `PasswordResetToken` have all been through this — **nothing creates a
table on demand any more**, so the job is the only thing that can give a resort one.
**Keep every table in the job even after its migration** — a resort added to `resortMap` later needs
all of them, and this is the only thing that can give it one.

A resort whose table is missing degrades rather than failing: no menu buttons, no template
patrollers. Verified by dropping each table and loading the pages. But **a write that silently did
nothing must not report success** — that caught `MenuButton.writeAll` once, which is why it and its
callers now return and check a boolean.

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

## Adding a New Resort

`createTablesForNewResort.sql` in the project root holds every table a resort's database needs.
Recorded from a working resort, so it is the real schema rather than a reconstruction, and every
statement is `CREATE TABLE IF NOT EXISTS` — safe to re-run against a resort that is already up.

    1. add it to PatrolData.resortMap
    2. CREATE DATABASE `NewResort` DEFAULT CHARSET=utf8mb4;
    3. mysql -u<user> -p NewResort < createTablesForNewResort.sql
    4. replace {RESORT} with the resort name for the password-reset table
    5. insert the resort's directors into `roster`, or nobody can sign in

**`CreateTablesForNewResortSqlTest` compares the file against a live resort's schema and fails when
they drift**, so adding a table to the app is what makes the file stale-and-noisy rather than
stale-and-silent. Re-record it from a healthy resort and read the diff.

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
