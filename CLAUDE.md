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
org.nsponline.calendar/
  CalendarApplication.java       Spring Boot entry point
  controller/                    Spring MVC controllers (migrated, active endpoints)
  service/                       Business logic (EmailService, ShiftAssignmentService, etc.)
  dto/                           Form/request/response data objects for controllers
  config/                        Spring config (AsyncConfig: email thread pool + SMTP bean)
  store/                         Data access objects (Roster, Assignments, ShiftDefinitions,
                                 NspSession, DirectorSettings, PasswordResetToken)
  utils/                         Shared utilities (PatrolData, SessionData, Logger, MailMan)
  resources/                     LEGACY servlet classes — migration source; not registered
                                 anywhere, not currently served; migrate each to controller/
```

### Migration Status

**Migrated to Spring Boot controllers** (in `controller/`):
`/ChangeShiftAssignments`, `/Directors`, `/DayShifts`, `/EditShiftTemplates`, `/EmailForm`,
`/ListAssignments`, `/ListPatrollers`, `/MemberList`, `/MemberLogin`, `/MemberLogout`,
`/MonthCalendar`, `/ProcessChangeShiftAssignments`, `/SetupListPatrollers`, `/SubList`,
`/UpdateInfo`, `/VolunteerForm`, `/WebSitePreferences`

**Still need migration** (in `resources/`, currently not served):
- `ApiResources.java` — REST JSON endpoints requiring `Authorization` header
- `InnerDownloadRoster.java`
- `InnerPurgeAssignments.java`
- `InnerWebResource.java` / `WebResource.java`
- `LoginHelp.java`
- `RefresherTraining.java`

`ResourceBase.java` is the legacy base class that the unmigrated servlets extend — it won't be needed once migration is complete.

### javax/jakarta Bridge

Legacy `store/` and `utils/` classes use the old `javax.servlet` API. Spring Boot 4 uses `jakarta.servlet`. `ServletAdapter` in `controller/` wraps Jakarta request/response/session objects to implement the Javax interfaces, allowing controllers to call into legacy store and utility code without rewriting it.

Pattern used in every controller:
```java
javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
SessionData sessionData = new SessionData(javaxRequest, null, LOG);
PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
```

### Authentication & Session Flow

- **Web sessions**: Cookie-based; `SessionData` reads the session cookie and validates via `NspSession.read(connection, sessionId)`
- **API sessions**: Token in `Authorization` header
- Invalid sessions redirect to `MemberLogin?resort=<name>` for web pages, or return 401 for API
- Director-only pages check `Roster.isDirector` after loading the logged-in user's record

### Data Layer

Store classes manage SQL directly (no ORM). Each method receives a `PatrolData` (holds the live `Connection`) and uses the resort name as a table prefix. `PatrolData` connects to MySQL at `MYSQL_ADDRESS` (default `127.0.0.1`).

### Email

`EmailService` sends via Spring's `JavaMailSender` (configured in `AsyncConfig` using `smtp.host` from `application.properties`). Director-triggered bulk emails are `@Async` on the `emailTaskExecutor` thread pool. Password reset emails use one-time tokens stored in `{resort}_password_reset_tokens` (24-byte random, 1-hour expiry, single-use).

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
- **`doReminder.sh`**: Standalone cron script for daily reminders; its classpath must be updated manually when dependency versions change in `pom.xml` (the properties section has a reminder comment)
