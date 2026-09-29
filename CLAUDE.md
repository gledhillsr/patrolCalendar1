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
- **A cookie authenticates on the resort it was issued for and nowhere else.** It used to be
  checked with `patrolData.getMemberByID`, against whichever resort's database the *current page*
  belonged to — so a Sample cookie was looked up in Brighton's roster, logged
  `called getMemberByID with invalid memberID=[...]` on every request, and then fell into the
  rejected-cookie branch, which **deleted it**. Glancing at another patrol's site logged you out of
  your own. A cookie for another resort is now ignored: not used, and deliberately **not deleted** —
  that branch is for cookies that are forged or expired, which a perfectly good cookie for somewhere
  else is not. `LoginCookieIsResortScopedTest` pins both halves
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

**A day that has already happened is frozen for members.** `mayEdit` gives directors everything,
refuses everyone when `directorsOnlyChange` is set, and otherwise allows only today and later —
`day >= realDate`, so tonight's shift can still be picked up or dropped. It covers **empty slots as
much as filled ones**, because both slot paths (`templateSlots` and `savedSlots`) ask the same
question: a member cannot add themselves to a vacancy on a day that has gone either. This used to
apply at `KellyCanyon` and `Brighton` alone — a hard-coded pair the servlet carried, with its
author's own `//todo fix this crap` beside it — so at the other 42 resorts a member could sign into
a shift that finished last March and change a record the director had been reading.

**"Today" is the resort's own date**, via `ResortTimeZones.inForce`, not the server's — the same
rule `ShiftTimeCleanup.cleanScheduledDays` follows, and it matters here for the same reason: a
resort three hours behind must not have tonight's shift treated as past because the box has rolled
over. That is why `readDirectorSettings()` happens before the clock is read.

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
(`Saturday_0`, `Saturday_1`).

**A template name may not *start* with an underscore**, and that is the only restriction.
`EditShiftTemplatesController` refuses one in the browser and again on the server, and
`EditShiftTemplatesControllerTest` scans the database for existing offenders. The reason is not the
suffix: an unnamed row is stored as `_0`, and `ShiftDefinitions.UNNAMED_TEMPLATE` finds those with
`LIKE '\_%'` so the Site Wide Configuration job can delete them — a template genuinely called
`_old` would be swept away with the artifacts.

**An underscore *inside* the name is fine.** The rule used to ban them outright, which was never
necessary: `parsedEventName` and `getEventIndex` split on the **last** underscore, so
`Saturday_old_3` is row 3 of `Saturday_old` — and Brighton has run a template by that exact name for
years, through every one of these code paths, without trouble. `DayShiftsController.splitEventName`
carries the same `lastIndexOf` rule and a comment saying so. `anUnderscoreInsideTheNameIsAllowed`
pins the round trip, because a split that got this wrong would file the row under a template nobody
created rather than failing outright.

Two traps in testing this, both hit and both now commented in place:

- **The refusal message is inline JavaScript on every render**, so `page.contains("cannot start with
  an underscore")` passes whether or not the name was refused. The server's wording ends
  **"was not saved"** and the browser's **"is invalid"**; assert on the half that only one of them
  says. The old test had the same flaw and had been passing on the JavaScript text.
- The New button posts **`addShift`**, not `addShiftBtn` — the latter silently writes nothing.

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
  weekday to match. **The button is also dead when the template has no patrollers on it** — an
  unassigned day-of-week template needs no applying, because the calendar already draws its shifts
  on every matching in-season day by itself, so applying it would look like doing nothing. The panel
  is a date range and one **Apply** button, which writes immediately.

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

**Reducing a shift's patroller count closes the gaps rather than stranding people.** Slots are a
grid, not a queue — three patrollers can sit in slots 1, 3 and 5 of a five-slot shift — so dropping
that shift to three slots used to leave the one in slot 5 **still stored and no longer drawn**: off
the calendar, out of every count, still on the row, with nothing to say they had gone.
`Assignments.packPatrollersIntoCount()` moves everybody into the first `count` slots in the order
they were already in, **zeroes every slot after that**, and returns how many did not fit.
`saveShiftInfo` calls it on every row it writes, so a packed row is an invariant rather than
something one path remembers to do; a negative id (a missed shift) is somebody, not an empty slot,
and travels with the rest.

**The browser warns only when patrollers really will be lost** — `confirmShiftCounts` on the Save
Shift Changes button, comparing each row's chosen count against `data-filled`, which the count
dropdown carries from `getFilledSlotCount()`. That counts **every** slot rather than the first
`count` of them, so somebody stranded by an earlier reduction is included. Warning on every
reduction would cry wolf on the case that loses nobody, and a director told "you may lose someone"
every time stops reading it. The check is on the button, not the form's `onsubmit`, because
`confirmActionDialog` replays the click and `saveShiftBtn` is dispatched on its name.

Replacing a day wholesale is the other destructive path: the shift-format dropdown on DayShifts
confirms how many patrollers will be removed, then POSTs `replaceShiftFormat` (a POST, not a URL, so
a refresh cannot repeat it). `processReplaceShiftFormat` deletes the day's rows — patrollers live on
them — and applies the chosen template. A name matching no template leaves the day empty, reverting
it to the day-of-week template. There is deliberately **no bulk replace** across a date range.

**"Apply this &lt;weekday&gt; template including assigned patrollers"** on DayShifts is the same
wholesale replace, plus the people pencilled into that weekday's template —
`processReplaceShiftFormat` takes a `withTemplatePatrollers` flag for it. The dropdown beside it
passes `false` and so still lays a day out empty, which is the behaviour it always had. The button
is drawn only when the weekday template actually carries patrollers, the same rule
EditShiftTemplates applies to its own Apply button.

**Its confirmation text goes through `data-*` attributes**, not `th:onclick`: Thymeleaf refuses to
interpolate a String into an event handler at all, and says so at template-parse time. The page
already used `th:data-url` + `this.dataset.url` for the same reason.

### Shift Labels: Start is a Label plus a Time

A shift row carries a **Label** — what the calendar shows (`TL`, `dir`, `mentee`) — and a
**Start Time**, the real start. `getDisplayStartString()` on both `ShiftDefinitions` and
`Assignments` is the rule: label when there is one, start time otherwise. The point of splitting
them is a personal-calendar sync, which needs a machine-readable start; the field had been free
text for twenty years and directors typed roles into it.

**Both tables carry a `Label` column** (`shiftdefinitions`, `assignments`). `ShiftLabelMigration`
adds it and sorts the old values: a StartTime that parses as a time stays, rewritten canonically; a
StartTime that does not was always a label and moves.

**Run it from Directors → Site Wide Configuration**, the "Add shift Labels in every resort" button,
which walks every resort in `PatrolData.resortMap`. It rewrites rows in two tables and there is no
undo, so take a `mysqldump` of `shiftdefinitions` and `assignments` before the first run. The
resort list is behind `SiteWideConfigurationController.shiftLabelResorts()` rather than inlined,
because it was held to `Sample` alone while being proved on the live site — that is the one place to
narrow it again. `ShiftLabelMigration.main` does one resort from the command line. **Until a resort has been through it, the shift editors and `DailyReminder`
cannot read its shifts** — `Assignments.read` expects the column, and without it the read fails.

**It is idempotent by looking at the data, not at whether the column exists.** That matters: the
`ALTER` and the row moves cannot share a transaction, because MySQL commits DDL on the spot, so a
run that died between them leaves a resort with the column and its rows still in the old shape — and
a column-existence check would skip that resort forever. Judging on the data makes a second press
write nothing ("nothing to do") *and* finishes an interrupted run. `ShiftLabelSiteWideJobTest` builds
a throwaway database in the pre-migration shape for both cases, because Brighton and Sample are
already migrated and would let a `migrateResort` that did nothing at all pass every other test.

**`ShiftTime` is the single answer to "is this a time?"**, used by the migration, by both
controllers through `StartAndLabel`, and mirrored in `static/js/shiftTime.js` for the browser.
It accepts what people actually type — `8:00`, `08:00`, `0800`, `800`, `8`, `8am`, `8:00am`,
`4:00 pm`, `1900`, `4:30P` — and `normalize` answers in canonical `H:MM`.

**That answer is not what gets stored.** The editors keep a Start or End Time **exactly as the
director typed it**: `8am` stays `8am`, `4pm` stays `4pm`, and only surrounding space is taken off.
It is their own shorthand and the calendar shows it straight back to them, so rewriting it to
`16:00` was tidiness at their expense. The rule is that a value must **read** as a time, not that it
be written one particular way. `normalize` is still how anything needing a machine-readable time
gets one — `CalendarFeedService.timeOf` does exactly that at read time.

**What must not be left to the stored text is whether two rows are the same shift.**
`ShiftTime.shiftKey(start, end)` normalizes both ends before joining them, and that is what the
duplicate check groups by — otherwise one long shift entered as `8am` on one row and `08:00` on the
next is two shifts, the "nobody twice on the same shift" check looks in two buckets, and a double
booking goes unnoticed. A value that is *not* a time keys as itself (trimmed), because unmigrated
End values like `end time` still have to group with each other.

It is reached through **`Assignments.getShiftKey()` and `ShiftDefinitions.getShiftKey()`** so the
controllers and the templates cannot drift: `day-shifts.html` renders `data-shift` from the store
object directly, while `edit-shift-templates.html` gets it via `TemplateSlot.time`. Both are the
same key. `ShiftTimeTest` pins the grouping, in both directions.

**The match is anchored and whole-string.** `PMExtra` is a real label in Brighton's data; anything
that searches for "pm" inside the string turns it into noon and loses a label. `ShiftTimeTest` is
built from values taken out of the real columns for exactly this reason.

**The `m` of the meridiem is optional**, so `4:30P` reads as 4:30pm. It was mandatory at first, and
that was wrong in a way that cost real data: `4:30P` and `8:00 a` are one resort's house style, and
while the rule rejected them the Label migration read them as labels — moving them out of StartTime
and **blanking the start**. 17 shift templates and 2,759 scheduled rows ended up with no start time
at all. `ShiftTimeCleanup` puts them back. A bare `P` only means anything directly after a number,
so no label acquires a meaning from this.

**Widening it in Java without widening `static/js/shiftTime.js` leaves the browser refusing what
the server accepts**, and the message blames the value rather than the mismatch. There is no shared
source — one runs in a JVM, the other in a browser — so
`ShiftTimeTest.theBrowserCopyCarriesTheSameMeridiemRule` reads both files and compares the rule with
the backslashes stripped. **Change one copy and you must change the other.**

Things worth knowing:

- **`MonthCalendarService` renders the start string in two places, not one** — `:357` for a day
  drawn from its day-of-week template and `:378` for a day with saved assignments. Both now call
  `getDisplayStartString()`. Change one and not the other and the same calendar shows labels on
  empty days and times on filled ones. The goldens will not tell you: their fixture dates carry no
  labels, which is why `ShiftLabelOnCalendarTest` pins both paths directly.
- **`shiftKey` keys on the time, never the label.** A shift needing more than 10 patrollers is
  several rows sharing a time, and those rows need not share a label — keying on the label would
  split one real shift into two and let somebody be booked twice on it.
- **The browser's duplicate check groups by `data-shift="${slot.time}"`, which the *server*
  renders** from `shiftKey`. It never reads the Start input, so the split did not disturb it.
- **Both assign grids show the label but do not group by it.** The visible cell is
  `describeLabelAndTimes()` — `(dir) 8:00 - 16:30` — while `data-shift` stays keyed on the times
  alone. They are two separate values on purpose: `TemplateSlot` carries `time` and `displayTime`,
  and day-shifts.html builds both in its `th:with`. Merging them would split one long shift's rows
  into separate shifts whenever their labels differed, and let somebody be booked on both.
  `NoDuplicateOnAShiftTest` asserts no `data-shift` on either page ever contains a bracket.
- **`ShiftTimeCleanup` still canonicalizes**, and that is now the one thing that will rewrite a
  director's `8am` to `8:00`. It is opt-in and named "Tidy shift template times", so pressing it is
  asking for exactly that — but it is worth knowing it undoes the typing the editors now preserve.
  Templates only; scheduled days go through `cleanScheduledDays`.
- **A non-time typed into Start moves to the Label** (`StartAndLabel`) rather than being rejected,
  which is the same rule the migration applied to twenty years of them. The browser says so first;
  the server is the backstop for a form posted around it.
- **A row must carry a Label or a Start Time; a save with neither is refused.** A slot renders as
  its label or, failing that, its start time, so a row with neither is a blank line on the calendar
  for a shift somebody is meant to work. Both editors check it — DayShifts before it acts on any
  button, EditShiftTemplates before its write loop starts, since that loop writes as it goes and a
  row rejected halfway would leave the earlier ones saved. **Delete is exempt**: it is how a
  director gets rid of the offending row, and blocking it would be a trap. This is also why a new
  DayShifts row is seeded `8:00`/`16:00` rather than blank — a row born unsaveable is the same
  trap. **A row whose patroller count is 0 is also exempt on DayShifts**: nobody works it, so it
  cannot leave a blank line for somebody to turn up to, and a director laying a day out needs to be
  able to leave such a row empty. EditShiftTemplates does not carry that exemption — its rows are
  what future days are built from. `ShiftNeedsLabelOrStartTest` pins all of it, including the
  delete exemption.
- **EndTime keeps its history, but an edit has to be a time** (`EndTimeEdit`). It was never
  migrated: Brighton has 373 assignment rows whose End is not a time (`end time`, `5:30-9`, the
  literal `empty`) and there is no End label to move them into, so emptying or rejecting them would
  be loss for nothing. The rule is therefore about **editing**, not storing — a value the director
  did not touch survives whatever it says, a value they did touch is normalized like a Start Time,
  and a value they changed to another non-time is refused with the old one kept. **Whether it
  changed is decided against the stored row, never a hidden field**, so nothing posted can talk the
  server into accepting a new non-time. The browser compares against `defaultValue`, which is what
  the server rendered into the box. Clearing it is allowed — End is not required.
- **The store classes' INSERTs are named-column now.** Both were positional `VALUES(...)`, which a
  new column silently breaks with an error that names the count rather than the cause.
- **There is no `patrolCalendarPreLabelBackup` database.** An earlier note here said there was; it
  does not exist on the server. What does exist is the **three-hourly full `mysqldump` to S3**
  (`/root/backupMySqlToS3`, `s3://sgled57/backups/calendar/<private-ip>/<year>/<month>/`), which is
  what the pre-migration state was actually recovered from — restore one into a scratch database
  and join against it. Note the **private IP is part of the path and changes when the instance is
  replaced**, so a listing sorted by name puts the older machine's backups last and looks alarmingly
  stale; sort by date.

The red notice announcing this is still on both pages, from `fragments/notices.html` with its
example image at `static/images/labelSplitExample.png`. **Delete the fragment and both
`th:replace` lines once directors have seen it** — a notice that outlives its change is worse than
no notice.

### Tidying Shift Template Times

`ShiftTimeCleanup` finishes what `ShiftLabelMigration` started, on **`shiftdefinitions` only**. Run
it from Directors → Site Wide Configuration ("Tidy shift template times in every resort"), or
`ShiftTimeCleanup.main` for one resort from the command line. Three things it fixes, all of them
the computer's to decide:

- **a time sitting in the Label** with StartTime blank — the meridiem bug above. The Start is
  filled in from it (`4:00P` → `16:00`) and **the Label is left alone**: it is what
  `getDisplayStartString` draws on the calendar, so clearing it would change what directors see on
  days they never touched, to no purpose — what was missing was the machine-readable start, not the
  caption. The row ends up carrying both, which is what the Label/Start split was for
- **a time written into one end of a Label** — `Vol 10:00AM`, `Pd 1:00PM`, `HC 8:00`, `Vol 1530`,
  and `8:30 Paid` / `9:15 Ski` with the time on the front. Only the **first or last whitespace-
  separated word** is considered, never the middle, and only when the word carries its own evidence
  of being a clock time: a colon, a meridiem attached to a digit, or the 3-4 digit packed form.
  **A bare one or two digit number never qualifies** — the real labels include `Train 2`, `Avy 1`,
  `Mentee 9` and `S&T 9`, which are course and team numbers, and reading those as times would
  schedule people at 2am while the label still said "Train 2", so nothing on the page would look
  wrong. `deliberateTime` is deliberately stricter than `ShiftTime.normalize`, which reads a bare
  number as 24-hour because that is right for a Start field and disastrous inside a caption
- **a StartTime that is a time but not canonical**
- **an EndTime that is a time but not canonical** — `1600`, `4:30pm`, `2100`. End was never
  migrated, so this is most of the work

**Templates only, deliberately.** `assignments` carries the same faults through twenty years of
history — 2,759 mis-sorted labels, thousands of untidy ends — and rewriting a shift somebody worked
in 2019 changes a record for nobody's benefit. Templates are what build the seasons ahead, so
fixing them is what stops it recurring. Idempotent on the data, like its sibling: a second press
reports "nothing to do".

**What it will not touch is the point.** An EndTime that is not a time (`Close`, `end time`,
`5:30-9`) and a blank EndTime are left exactly as they are — there is no End label to move them
into and no way to guess the hour. Those go to `service/ShiftTimeAudit`, which
`EditShiftTemplatesController` calls to fill the panel below the grey frame on Edit Shift Templates.

**The panel's value is in what it leaves out.** Of 884 template rows site-wide 231 were wrong, and
the cleanup fixes 201 of them unattended; a report listing all 231 — never mind all 884 — would
bury the ~30 that genuinely need a director's answer. `ShiftTimeAuditTest.aRowThatCanBeReadIsNotListed`
is the assertion that keeps it that way, and `ShiftTimeAuditPanelTest` renders the page, because a
Thymeleaf expression that throws takes down the very page directors fix shifts on.

Two things that bit while building it, both now pinned:

- **A `page.contains(templateName)` assertion is vacuous here** — the name is in the selector
  drop-down as well, so it passes whether or not the panel mentions it. `ShiftTimeAuditPanelTest`
  slices out the `<details>` element first. The test that matters is the one asserting a row is
  *absent*, and that is exactly the one this made meaningless.
- **A label-only shift** (`mentee`, no times) is listed, and is the one entry that may be correct as
  it stands — so it is worded as "cannot sync" rather than as an error.

### Personal Calendar Sync Settings

`WebSitePreferences` carries a bordered group, **Enable PatrolCalendar sync to personal calendars
(Google/Apple/Outlook)**, holding everything a shift needs before it can leave this site and land
correctly in somebody's own calendar. `PersonalCalendarSyncPanelTest` covers it.

- **Enabled** — `directorsettings.calendarSyncEnabled`, off by default and off for a resort whose
  column the site-wide job has not reached. Switching a resort's patrollers on is its director's
  decision, not a side effect of a migration, so off is where the default falls.

  **Every real resort may now switch it on.** `PatrolData.maySwitchOnCalendarSync` is the one
  rule, and it is now "a resort in `resortMap` that is not a demo resort" — the `CALENDAR_SYNC_RESORTS`
  allowlist that held it to Brighton while the feed was proved has done its job and gone. Being
  allowed to switch it on is not the same as it being on: the column defaults to `0` everywhere and
  each resort's director decides.

  **Demo resorts never qualify**, and that is permanent rather than a stage of the rollout — their
  members are invented, the same reason `isDemoResort` keeps them out of every mailing. A resort
  commented out of `resortMap` is refused too: it has no database to read shifts from.

  **The prerequisite is the `calendarSyncEnabled` column**, which the site-wide time-zone job adds
  (created switched off). Without it `DirectorSettings.write` silently drops the field from the
  UPDATE, so a director would tick the box and the tick would not stick. Verified on production:
  **44 of 44 resorts in the map have it**, which is every resort this rule now admits; the ten
  databases without it belong to resorts commented out of the map.

  It is **drawn rather than hidden**, with a line saying it is coming and that tidying shifts now is
  still worth doing — a control that vanishes teaches a director nothing.

  **The save checks the same rule again**, because a disabled checkbox simply is not posted, which
  stops nobody from posting one. And a resort that may not change it has its stored value **left
  alone rather than set to false**: an unposted checkbox binds as `false`, so writing that would let
  every ordinary preferences save at 43 resorts silently clear a setting they cannot even see.
  `aSwitchPostedByHandIsIgnored` and `anOrdinarySaveDoesNotClearTheStoredSwitch` pin both halves —
  the second sets the stored value to true *underneath* the controller first, because with it left
  false it would pass whatever the save did.
- **Resort time zone**, moved here from its own section — it belongs with the thing it exists for.
- **A readiness line** from `ShiftTimeAudit.readiness`: "23 of 29 shift template rows can be
  synced — 6 still need a start or end time you can read", linking to the audit panel.
- **Tidy my shift template start and end times** — `ShiftTimeCleanup.cleanResort` for this one
  resort, so a director fixes their own without the site owner doing it.
- **Tidy this season's scheduled shifts** — `ShiftTimeCleanup.cleanScheduledDays`, **from today
  forward only**. This is the half a feed actually reads: templates build future days, but the days
  already on the calendar are what a patroller subscribes to. The cut is made in SQL on the date
  prefix of the key (`yyyy-mm-dd_<n>`, so text comparison is correct), and *today* is taken in the
  **resort's** zone, not the server's — a resort three hours behind must not have tonight's shift
  treated as past because the box has rolled over.

**The column is deliberately not called `syncEnabled`.** That name is already taken, on Brighton and
KellyCanyon, by a dead feature: `html/screenshots8/sync.php` used it to switch the
locker-room-to-web sync on and off, and now decides that from a file with the database code
commented out. The column and its values outlived the feature — **KellyCanyon's still reads `1`** —
so adopting the name would have switched personal calendar sync on there the day it shipped, chosen
by nobody. Nothing in `src/main` referenced it, which is exactly why a `grep` of this repo alone
would have said the name was free.

**A column named in `DirectorSettings.getUpdateQueryString` had better exist.** The UPDATE names
every column it writes, so adding one before the site-wide job has run breaks *the whole save* —
a director loses every preference change, not merely the new setting. `write` therefore asks
whether `calendarSyncEnabled` is there and leaves it out when it is not, and `hasColumn` answers
**true on any error** so a failed metadata call cannot be the thing that stops a save. `timeZone` is
not guarded this way because it is already on all 44 resorts; **anything added after it should be.**
This is not hypothetical - it broke `theTimeZoneIsSavedAndComesBack` the moment the column joined
the statement.

Both buttons sit inside the preferences form and reach their handlers with **`formaction`**, because
HTML has no nested forms and the panel has to sit beside the time zone it belongs with. The handlers
ignore the form's fields — neither saves a preference. The page also had to start loading
`dialogs.js`: without it `confirmActionDialog` is undefined, the `onclick` returns `undefined`, and
the form **submits with no confirmation at all**.

### Patroller Classifications

The roster stores a three-letter **code** — `BAS`, `SR`, `SRA`, `ALM`, `PRO`, `AUX`, `TRA`, `CAN`,
`NSK`, `OTH` — and that is what twenty years of rows hold, what every query parameter says
(`ListPatrollers?...&PRO=1`), and what `okToDisplay` filters on. **The codes never change.** What
changed is the **name** each one is shown under:

| Code | Name |
|------|------|
| BAS | Alpine |
| SR | Senior Alpine |
| SRA | Senior Patroller |
| AUX | Patroller |
| PRO | Paid |
| NSK | Not Skiing (new; the others were renamed) |

**Adding one means four lists, not one.** `Roster.lookupClassification` gives it a name, and then
`RosterQueryService.CLASSIFICATION_CODES` lets ListPatrollers filter on it,
`MemberListController.ALL_CLASSIFICATIONS` lets the Patrollers tab's full roster show it, and
`EmailFormRequest` needs a component of that name or the "e-mail THESE patrollers" URL drops it and
the mail goes to a different set of people than the page listed. Plus the checkbox on
`setup-list-patrollers.html` and the option on `update-info.html`. `NSK` is the worked example.

**`NSK` is deliberately not in `MemberListController.LISTED_CLASSIFICATIONS`** — the trimmed roster
exists to leave out people who are not patrolling, which is what Not Skiing says. It shows on the
full roster, and on ListPatrollers when its box is ticked.

`Roster.lookupClassification` is the one table, and everything reads it: `getFullClassification()`
for a member's own name, `getClassificationName(code)` and `getClassificationCode(label)` for the
two directions. **Add a classification there and nowhere else** — the nsp.org import resolves
against the same table, so a name added or renamed in one place cannot leave the import matching
the old word while the pages show the new one. `ClassificationNamesTest` pins the round trip.

**An unmapped code shows as itself rather than as a blank.** `INA` is on real rosters and is
deliberately not in the table; a blank cell where a member plainly has a classification reads as a
bug, and `getFullClassification()` used to return null for exactly that case.

**ListPatrollers, the Patrollers tab and the Excel download show the name, not the code** — the
download because it is documented to hold exactly what the page displayed. Note **"Sort by
Classification" still orders by the stored code** (`ORDER BY ClassificationCode` in SQL), so
members of one classification still group together, but the groups run in code order rather than
alphabetically by name.

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

**The Patrollers tab's "Download to Excel" button has the same problem and the same answer.**
`MemberListController.selectionQuery` builds the query string once and both buttons append it, so
the download and the email cannot select different people. Add a filter to one and you have added
it to both.

**`DownloadRoster` is open to any signed-in member**, not just directors. The Patrollers tab already
shows every member's cell number and email on screen and now offers the download beside them, so the
spreadsheet is the same data in a portable form rather than new access — but it is a deliberate
choice, not an oversight, and narrowing it again means changing the controller *and* hiding the
button.

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

### Importing a Roster from nsp.org

`ImportPatrollersController` (`/ImportPatrollers`, from Directors → Patroller Maintenance) adds
patrollers from the `ResultsGrid_ExportData.csv` a patrol representative downloads from nsp.org.
Director-only.

**It only ever inserts.** A row whose ID is already on the roster is listed as skipped and that
member is left untouched, so the same file can be run twice and anything a director edited by hand
survives. Rows are skipped, with the reason shown, when the ID is missing or not numeric, the
`Member Type` is not `Patroller`, the ID is already on the roster, or the ID repeats inside the
file. A new member is created **Full Time / not a director**, with `Instructor` set to `"0"` — a
fresh `Roster` initialises its fields to `" "`, which MySQL rejects for that integer column.

**The classification comes out of the file**, from nsp.org's `Primary Initial Class` column, which
is why the classifications were renamed to the words nsp.org uses: `Alpine`, `Senior Alpine`,
`Patroller`, `Senior Patroller`, `Paid`. The resolution is `Roster.getClassificationCode`, matching
on the display names (case and surrounding space ignored, and a bare code accepted as itself), so
there is no second list to keep in step — rename a classification and the import follows.

**Anything it cannot resolve becomes `OTH` (Other), and says so.**
`NspRosterImport.DEFAULT_CLASSIFICATION` is that fallback. `Other` is chosen because it records what
actually happened — this class was not recognised — where any real classification would be a guess,
and afterwards a guess cannot be told from a class the file genuinely gave. It also sits outside the
trimmed roster and outside the ListPatrollers defaults, so an unrecognised member is not quietly
counted among the patrollers a listing is about. Every such row is
marked in italics on the preview *before* anything is written, carries the raw nsp.org wording in
its tooltip, and is named one per line in the log at import time — a bare count of "8 defaulted"
tells nobody which eight or to what.

**Two steps, and the second re-reads the file.** The preview writes nothing; pressing Import posts
the file again and the controller parses it and re-checks the roster from scratch. Nothing the
browser sends back decides who is inserted — a posted list of ids would be a way to add anybody.

**`NspRosterImport` parses it properly**, not by splitting on commas: the export quotes fields
containing commas (its Full Address does), doubles quotes inside them (its Edit column is HTML),
and starts with a byte order mark. Phone numbers arrive as `(847) 372-5936` and are stored as
`847-372-5936`; anything that is not ten digits is dropped rather than guessed at, since a
half-recognised number cannot be told from a real one later.

### The New Features Page

`NewFeaturesController` (`/NewFeatures`) is a director-only page describing what has been added
lately, in a director's words rather than a developer's. Reached from a **New Features** button in
the members bar, to the right of Logout and set apart from it.

**The page is static prose** — `new-features.html` — so adding a feature is an edit to that template
and nothing else. Newest at the top. The contents list is hand-written on purpose: generating it
would tie each entry's wording to a screen name, and the point is to describe what a director can
now *do*. **Bug fixes are deliberately excluded**; this answers "what can I do that I could not
before", which is a different question from "what was broken".

`NewFeaturesPageTest` does not test the prose, but does test the two things that rot quietly: the
access gate (a member gets 403, a visitor is redirected, the button is drawn for directors only and
sits after Logout), and that **every contents entry points at a section that exists and every
section is listed** — an added section nobody can reach from the top is exactly what no other test
would notice.

**`HeaderViewerService.isDirector` is how the header knows.** Called straight from the template like
`${@menuButtonService.forHeader(...)}` beside it, for the same reason: every page draws this header,
so threading a flag through twenty controllers means the twenty-first forgets. It reads the
**session**, never the `ID` parameter, and never throws — a header that cannot reach the database
draws no button rather than taking every page down. It costs one short query per page, and the page
itself checks again before doing any work.

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

### Emailing Every Resort's Directors

"Email all directors, every resort" on SiteWideConfiguration opens the ordinary EmailForm with its
**To:** list built by `SiteDirectorsService` instead of this resort's roster — every member flagged
as a director in every resort in `PatrolData.resortMap`, for announcing a feature or a problem.
`Sample` is skipped: its people are invented, and `EmailService` refuses to send from it anyway.
Someone who directs two resorts is listed once (matched on email address, kept under whichever
resort sorts first), because telling one person twice about one outage is just noise.

**This is the only recipient list on the site that spans resorts, and that breaks an assumption
everything else makes: that a patroller id names somebody.** Ids are unique only *within* a resort
— Brighton has a 192443 and so might Afton — so `EmailService.resolveRecipients`, which looks every
selected id up in the one resort the form was posted from, would not merely miss a cross-resort
director but could **quietly resolve their id to a different person and mail them instead**.
`EmailRecipient` therefore carries its `resort`, and `getSelectionKey()` is what the picker posts:
a bare id as before for a single-resort list, `Resort:id` for this one. Anything reading those
posted values must use the key, not `getId()` — `getId()` is still the plain id because `$id$` in a
message body is the member's own number.

The send takes the pre-resolved list through `sendEmailsAsync(form, patrolData, log, recipients)`,
and **the controller re-derives that list rather than trusting the request** — what comes back from
the browser may only *narrow* it. An id that is not a director's matches nothing and is dropped;
nothing typed into the form can add an address. Mailing addresses that arrived in a request is the
definition of an open relay, and this page is the one place where the recipients are not already
bounded by a single resort's roster.

Gated on `SiteWideConfigurationController.isSiteOwner` — the same two literals, checked against the
**session**, never the `ID` parameter — on the GET *and* the POST, since 47 other resorts' director
addresses are nobody else's to read or use. Non-owners get a 403 rather than a redirect.

Two things that would break quietly and so are pinned by `SiteDirectorsEmailTest`:

- **A rejected form must redisplay the same audience.** The filters behind an ordinary recipient
  list are not posted back, so the error path rebuilds it as `EmailFormRequest.everybody()` — which
  for this audience would silently swap in Brighton's whole roster while the owner fixed a typo in
  the subject. The audience is a posted field for exactly this reason, and `UploadTooLargeAdvice`
  carries it through the too-large-attachment bounce for the same one.
- **Nothing in that test posts a valid message.** A successful POST hands the batch to the real
  `EmailService` and mails every director at every resort for real. The only POSTs there are ones
  that stop first: a 403, and a form that fails validation.

**A site-wide mailing goes out as `SiteIdentity.FROM_EMAIL`, not the resort's address.** Every
other send uses the resort's `directorsVerifiedEmail` for SPF/DKIM alignment, and that is still the
rule — but the site's own address *is* the aligned one here: `hostEmailUser` in
`/etc/patrolCalendar/credentials.properties` is that same mailbox, so the SMTP session authenticates
as exactly the address being stamped. It also puts replies in the administrator's inbox, which is
the reason for sending one.

**The footer omits the "from `<resort>`'s online scheduling web site" line.** The batch is composed
against whichever resort the owner happened to be on, so that line would tell a director at 46 other
patrols that news about the whole site came from Brighton's. `Batch` carries a `siteWide` flag for
this, set by the pre-resolved-recipients overload of `sendEmailsAsync` — **that overload being the
site-wide path is an assumption**, true because it has exactly one caller; a second caller means the
flag has to become a parameter. Note the `From` address is still the resort's verified sender, which
is an SPF/DKIM constraint rather than a cosmetic one.

**The audience is submitted exactly once, in the form action's query string.** It was briefly also
a hidden `th:field`, and that shipped: **Spring joins duplicate parameter values with a comma**, so
the controller received `"siteDirectors,siteDirectors"`, failed the equality check, fell through to
the ordinary path, and tried to resolve `Brighton:192443` against Brighton's roster — `0 of 0
emails sent`, while the confirmation page still reported a queued batch. The query string is the
copy that survives a multipart body Tomcat refused to parse, so that is the one that stays;
anything else this form needs on both paths belongs there too, **never as a second copy**.

Two guards came out of that, both pinned by `SiteDirectorsSendTest`:

- **An audience that is present but unrecognised is a 400, not a fallback.** Blank is normal — every
  other page sends none — but a value that arrives and is not understood means the request is not
  what the page thought it was, and guessing means assembling the wrong recipient list in silence.
  Here it merely mailed nobody; with plain ids instead of `Resort:id` keys it would have mailed
  Brighton.
- **The overload actually chosen is asserted, with `EmailService` mocked.** That is the only way to
  test a valid POST on this page at all — the real service would mail every director at every
  resort — and asserting the *page renders* was exactly what let this through.

Note the recipient count feeds straight into the Tomcat part limits described under **Email** — one
multipart part per selected recipient, against `server.tomcat.max-part-count=2000`. A few hundred
directors is comfortable; a list that ever approaches that number would come back as a bare 413.

### Email

`EmailService` sends via Spring's `JavaMailSender` (configured in `AsyncConfig` using `smtp.host` from `application.properties`). Director-triggered bulk emails are `@Async` on the `emailTaskExecutor` thread pool. Password reset emails use one-time tokens stored in `{resort}_password_reset_tokens` (24-byte random, 15-minute expiry, single-use). The lifetime lives in `PasswordResetToken.LIFETIME_MINUTES` and is quoted to the member in the email — change it in that one place.

**The footer no longer apologises for the From address.** A branch used to tell any resort without
a verified address *"Please Don't respond to this email... I am working on setting this 'From'
address to be specific to your patrol."* Both halves were obsolete: resorts that wanted their own
address have one, and the instruction was wrong regardless — every message whose sender has a usable
address carries a `Reply-To` pointing at them, so replying worked the whole time. It was telling 31
of the 43 resorts not to do the thing that works. The remaining branch, for a sender with **no**
usable address, is still correct: there is no `Reply-To` on those, so a reply really does go nowhere
useful.

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

### The Site's Own Identity

`utils/SiteIdentity` holds the domain this site is served from and the addresses that speak for it.
They used to be four hand-written copies of `steve@gledhills.com` — the from address, the admin
address, the backdoor account's email, the footer's bug-report link — which is how a rename leaves
half a site pointing at the old name.

Each address is a separate constant rather than one shared string, because they do not have to move
together: **`FROM_EMAIL` is the deliverability-critical one** — it has to be SPF/DKIM aligned with
whatever account actually sends the batch, or a bulk send lands in spam, and the failure summary
that would tell you goes out over that same broken path. `SUPPORT_EMAIL` is only ever somewhere a
person replies to. `HOSTED_CONTENT_BASE` is separate again: Brighton's training tracker and donation
PDF are static content that can stay where its links point while the application moves.

The constants are static so `DailyReminder`, which runs with no Spring context, can use them;
`@Component("site")` and the instance getters are for templates (`${@site.supportEmail}`).

**`SiteIdentityTest` scans `src/main` for anyone writing the domain out by hand again.** Comments
are ignored, and so are the `resortMap.put` lines — there the domain stands in as the website of a
resort that has none of its own, which is resort data rather than site identity.

**The site answers on a second domain, `patrolcalendar.com`.** Both names point at the same
application, and a small line in the header, above both menu bars, says which one you arrived on:
*"Welcome to PatrolCalendar.com"* on the new name, *"Please update URL to use PatrolCalendar.com
(same site)"* on `gledhills.com`.

**The nudge is its own question, not the negation of the greeting.** `isPrimaryDomainHost()` sits
beside `isPatrolCalendarHost()`, and a host that is neither — `localhost` above all — gets
**nothing**. That is what keeps this out of every other render test and out of the goldens, and it
is right anyway: telling a developer on localhost to change their URL is noise.

It is deliberately **not** a second identity: nothing is sent *from* it and no link is built with it,
so `DOMAIN` and every address on it are untouched — this is one greeting, not a rename.
`isPatrolCalendarHost()` reads the host off the request in flight via `RequestContextHolder` rather
than taking a parameter, because **Thymeleaf 3.1 removed `#request` from the expression context** and
because every page draws this header — threading a flag through twenty controllers means the
twenty-first forgets, the same reasoning as `${@menuButtonService.forHeader(...)}` beside it. With no
request in flight (`DailyReminder`, a unit test) it answers false and draws nothing.

The match is the domain itself plus its subdomains, **not an `endsWith`** — that would greet
`notpatrolcalendar.com` as if it were us. `SiteIdentityTest`'s hand-written-domain scan now covers
both names, and `SecondDomainWelcomeTest` pins all of it by rendering with a real host (MockMvc
defaults to `localhost`, which is why every other render test is unaffected — set the host by asking
for a full URI rather than a path).

Two things depend on the deployment rather than the code: nginx must pass the original `Host`
through (`server.forward-headers-strategy=native` is already set, so `X-Forwarded-*` is honoured),
and the greeting's HTML **comment** ships on both domains — which is what made
`MonthCalendarGoldenTest` need re-recording even though no visible output changed on `localhost`.

### Thymeleaf Templates

Located in `src/main/resources/templates/`. Shared page chrome is in `templates/fragments/`. Static assets are in `src/main/resources/static/`.

**Dialogs are `static/js/dialogs.js`, not the browser's `alert()` and `confirm()`.**
`showMessageDialog(text, whenClosed, title)` tells; `askQuestionDialog(message, title, onYes, onNo)`
and `confirmActionDialog(button, message, title)` ask. They draw a native `<dialog>` centred by
`showModal()`, and set their text with `textContent` — these messages quote back what a director
typed, and a shift label is somewhere a person can put a tag.

**`confirm()` answers synchronously and a `<dialog>` does not**, so a check written as
`onclick="return confirm(...)"` cannot simply be swapped. `confirmActionDialog` returns false and
opens the box; answering OK marks the element and **clicks it again**, which re-runs the same
onclick, finds the mark and lets the action through. Replaying through the element's own click is
what keeps a submit button's name and value in the post — `form.submit()` drops them, and several of
these actions are dispatched on exactly that name. That is also why a check on a form's `onsubmit`
moves to its submit button.

`shiftTime.js` builds its validation messages on top of `dialogs.js` and **needs it loaded first**;
pages with nothing to do with shifts (PurgeAssignments, Site Wide Configuration) load `dialogs.js`
alone.

**Scripts and stylesheets are served under a content hash** — `/js/shiftTime-<hash>.js` — so an
edited file gets a new URL and no browser can answer from a stale copy. That needs **both** halves:
`spring.web.resources.chain.strategy.content` in `application.properties` serves the hashed URL, and
`config/StaticAssetConfig` registers the `ResourceUrlEncodingFilter` that rewrites `@{/js/...}` links
to it. **Boot does not register that filter on its own**, so the property alone is set, serves
hashed URLs, and changes nothing — every page goes on asking for the unhashed name.

This is here because it bit: `shiftTime.js` gained the End Time check while browsers held the copy
from the Start Time work, so `onblur="checkEndTime(this)"` called a function that did not exist. The
failure is silent, the submit check let the bad value through, and the first thing to object was the
server at save time — which reads as "the validation is in the wrong place" rather than "your
browser has an old file". `StaticAssetVersioningTest` pins both halves.

### Resort Time Zones

Every resort has one, because a shift time means nothing outside this site without it — a synced
calendar above all. Two layers:

- **`ResortTimeZones`** holds the built-in default per resort, worked out from each resort's name
  and the website in `resortMap`, and confirmed with the site owner. Only the four continental zone
  names are used: `America/Boise` keeps the same clock as `America/Denver`, so choosing the broader
  name cannot be wrong by an hour, and the only mistake that matters is the wrong zone entirely.
- **`directorsettings.timeZone`** is the resort's own answer, set on **Web Site Preferences**, and
  it wins. Blank means unanswered.

**`ResortTimeZones.inForce(resort, settings)` is the one method to call** — the director's answer
when there is one, the table otherwise, and it never throws on a stored value that is not a zone.
`DirectorSettings.read` tolerates the column being absent, so a resort the job has not reached
still loads its settings; every page in the app reads them.

**Adding the column needs `sql_mode` relaxed for that one statement.** `directorsettings` carries
`lastVoucherHistoryUpdate date NOT NULL DEFAULT '0000-00-00'` from twenty years ago, and an `ALTER`
makes the server revalidate the whole table, which `NO_ZERO_DATE` then rejects — with an error
naming that old column and saying nothing about the one being added. The site-wide job relaxes the
mode, alters, and restores it; the bad default is deliberately left alone.

**Applied to all 44 resorts in `resortMap` on localhost and production** (19 Mountain, 16 Central,
7 Eastern, 2 Pacific). Ten more databases exist for resorts commented out of the map — the job does
not touch them, and neither did the manual run, so the two stay in step.

### The Sample Demo and its Nightly Restore

`Sample` is the resort anyone is invited to experiment in, so it fills up with other people's trial
shifts and half-finished edits. `SampleBackup` is the pristine copy it is restored from at **2am**
by `restoreSampleResort.sh` (in `/etc/crontab` on the server, beside `doReminder.sh`). The direction
never reverses — nothing writes to `SampleBackup`, so an experiment in Sample cannot reach the
master. Re-baseline it by dumping Sample over it by hand; the recipe is at the foot of the script.

**`SampleBackup` is in `PatrolData.resortMap`, and that is the whole point of it being there.** The
restore copies the master's *table structure* as well as its rows, so a migration applied to Sample
alone would be undone overnight. Being in the map means the site-wide jobs on **Site Wide
Configuration** walk it like every other resort and keep its schema in step. It is not a resort
anyone is meant to browse.

**A demo resort will not sync shifts to a personal calendar either**, when that is built — the
same reason it is never emailed, and the same predicate decides it.

**`PatrolData.isDemoResort` is the one test for "these members are invented".** It covers both
Sample and SampleBackup, and **six** places ask it: `DailyReminder` (skips sending), the two email
paths in `EmailFormController` and `ProcessChangeShiftAssignmentsController`, `SiteDirectorsService`
(keeps them out of the all-directors list), and `MemberLoginController` (refuses password changes).
Every one of those was its own `"Sample".equals(...)` — which is five places to forget the moment a
second demo resort appears, and two of them would have started emailing made-up addresses nightly.
`DemoResortTest` scans `src/main` and fails on any new comparison against the name.

The server runs **MariaDB 10.5**, so `mysqldump` has no `--set-gtid-purged`; passing it aborts the
dump. The script also writes the password to a `chmod 600` defaults file rather than a `-p` argument,
which would be visible in `ps` to every user on the box.

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

**Validate the resort before building `PatrolData`.** `PatrolData` cannot make a JDBC url for a
resort that is not in `resortMap` and calls `Thread.dumpStack()` when asked to — fifty lines of
stack trace in the log for a mistyped query string. Every page does this first:

```java
if (!PatrolData.isValidResort(resort)) {
    response.sendError(400, "Resort not found: " + resort);
    return null;
}
```

This is not hypothetical: **several resorts are commented out of `resortMap`** rather than deleted,
and their links still exist in bookmarks and on other people's pages. A visitor with an old
`Directors?resort=DevilsHead` link is what found the two controllers that skipped the check.
`UnknownResortIsRejectedTest` pins it, and asserts up front that its test resort really is absent —
re-enabling that resort would otherwise make the test quietly meaningless.

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
