# Patrol Calendar




sudo systemctl status mariadb
sudo systemctl restart springbootapp.service
sudo nginx -t && sudo systemctl reload nginx
sudo systemctl start php-fpm


The deploy steps are unchanged: (Update(src/main/resources/springbootapp.service) << verify

sudo cp springbootapp.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl restart springbootapp.service
systemd-analyze verify /etc/systemd/system/springbootapp.service
<<<>>>>

Clean version for t3 → t3 (no ENA step needed).

1. Before you stop it

Check for an Elastic IP — this is the step that bites people. Select the instance → Networking tab → look for "Elastic IP addresses". 
If it's blank, the public IPv4 is auto-assigned and stopping the instance releases it permanently; 
you'll restart on a different address and anything pointing at the old one breaks. Allocate and associate an EIP first, or be ready to update DNS.

Confirm services start at boot, or the box comes back without them:

systemctl is-enabled springbootapp.service mysqld

Any disabled → sudo systemctl enable <name> now.

Take a rollback point: Actions → Image and templates → Create image.

2. Resize

1. EC2 console → Instances → select your instance
2. Instance state → Stop instance → confirm; wait for state Stopped (30–60 s — the next menu stays greyed out until it is)
3. Actions → Instance settings → Change instance type
4. Choose t3.small → Apply
5. Instance state → Start instance
6. Wait for Running with 2/2 checks passed

Downtime is typically 2–4 minutes. Billing switches to the t3.small rate on start.

3. Verify

free -m                                     # ~2 GB now
systemctl status springbootapp.service mysqld
curl -sI http://127.0.0.1:8080/calendar-1/  # app answering

4. Do this immediately after — don't skip it

At 2 GB, your app JVM will jump from 230 MB to ~480 MB of heap and switch from SerialGC to G1GC on its own, since nothing pins either. G1 also adds native memory overhead you don't need for this workload. Check what you actually got:

java -XX:+PrintFlagsFinal -version | grep -E "MaxHeapSize|UseG1GC|UseSerialGC"

Then pin the app's service unit to -Xmx512m -XX:+UseSerialGC, the same way we just pinned doReminder.sh. Otherwise you've bought 1 GB of RAM and let the JVM spend most of it on heap it won't use and a collector you don't need — leaving you with less real headroom than the upgrade suggests.

Add swap while you're in there, on either instance size:

sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

Also remember to copy the updated doReminder.sh next to calendar-1.jar if your cron runs an extracted copy — rebuilding the jar won't refresh it.


National Ski Patrol Online Scheduling System - A web-based application for managing ski patrol shift scheduling and assignments across multiple ski patrol organizations.

## Overview

This application provides ski patrols with a centralized platform to:
- Manage patrol member rosters
- Define shift templates and schedules
- Allow members to view and sign up for shifts
- Track assignments and patrol coverage
- Send automated reminder emails
- Provide director-level administrative tools

The system is multi-tenant, supporting multiple ski patrol organizations with separate rosters, schedules, and configurations.

## Technology Stack

- **Backend**: Java 25 with Spring Boot 4.0.6
- **Web Framework**: Spring MVC with Thymeleaf templates
- **Build Tool**: Maven
- **Embedded Server**: Tomcat 11 (via Spring Boot)
- **Database**: MySQL 8.0
- **Email**: AWS SES (Simple Email Service) via JavaMail
- **Testing**: JUnit 4, Selenium with HtmlUnit

## Prerequisites

- Java JDK 25
- Maven 3.x
- MySQL 8.0 (can run via Docker)
- AWS account with SES configured (for email functionality)

## Setup

### 1. Clone the Repository

```bash
git clone https://github.com/gledhillsr/patrolCalendar1.git
cd patrolCalendar1
git checkout master
```

### 2. Configure Database

#### Option A: Using Docker (Recommended for Development)

```bash
# Pull and run MySQL container
# DON"T simple copy/paste this, insert the password in 
docker run --name patrol-calendar-mysql \
  -e MYSQL_ROOT_PASSWORD=yourpassword \
  -e MYSQL_DATABASE=Brighton \
  -p 3306:3306 \
  -d mysql:8.0

# Start a mysql CLI inside of docker container
docker exec -it patrol-calendar-mysql mysql -u root -p
#then run commands like (unit tests call Sample)
CREATE DATABASE Brighton;
CREATE DATABASE Sample;
SHOW DATABASES;

# Import directly without copying *** put in real password from properties file
docker exec -i patrol-calendar-mysql mysql -uroot -pyourpassword Brighton < ~/Downloads/Brighton.sql
docker exec -i patrol-calendar-mysql mysql -uroot -pyourpassword Sample < ~/Downloads/Sample.sql
```

#### Option B: Local MySQL Installation

Ensure MySQL 8.0 is installed and running, then create the database:

```bash
#mysql -uroot -p
#CREATE DATABASE calendar;
```

#### Configuration

Update the MySQL address in `src/main/java/org/nsponline/calendar/utils/PatrolData.java`:

```java
private final static String MYSQL_ADDRESS = "127.0.0.1";
```

### 3. Build the Project

```bash
mvn clean package
```

This will create `target/calendar-1.jar` (Spring Boot executable JAR).

### 4. Run Tests

```bash
# Run unit tests
mvn test

# Run integration tests (starts embedded Jetty on port 7070)
mvn clean package -Dintegration.test
```

## Running the Application

### Development Mode

```bash
# Using Maven plugin
mvn spring-boot:run

# Or run the JAR directly
java -jar target/calendar-1.jar
```

Access the application at `http://localhost:8080/calendar-1/MonthCalendar?resort=Sample`

### Production Deployment

```bash
# Build the JAR
mvn clean package

# Run with production settings
java -jar target/calendar-1.jar

# Or as a background service
nohup java -jar target/calendar-1.jar > app.log 2>&1 &
```

The application runs on port 8080 by default. Access at `http://your-server:8080/calendar-1/`

### Daily Reminder Emails

Configure a cron job to send daily reminder emails:

```bash
# Edit crontab
crontab -e

# Add this line (runs daily at 6 AM)
0 6 * * * /path/to/calendar/src/main/resources/doReminder.sh
```

Note: If you update dependency versions in `pom.xml`, update the classpath in `doReminder.sh` accordingly.

## Project Structure

```
patrolCalendar1/
├── src/
│   ├── main/
│   │   ├── java/org/nsponline/calendar/
│   │   │   ├── CalendarApplication.java # Spring Boot entry point
│   │   │   ├── controller/       # Spring MVC controllers
│   │   │   │   ├── ListPatrollersController.java
│   │   │   │   ├── MemberLoginController.java
│   │   │   │   ├── VolunteerFormController.java
│   │   │   │   ├── DirectorsController.java
│   │   │   │   ├── MonthCalendarController.java
│   │   │   │   └── ServletAdapter.java
│   │   │   ├── store/            # Data models & database access
│   │   │   │   ├── Roster.java
│   │   │   │   ├── Assignments.java
│   │   │   │   ├── ShiftDefinitions.java
│   │   │   │   ├── PasswordResetToken.java
│   │   │   │   └── DirectorSettings.java
│   │   │   ├── utils/            # Utilities and helpers
│   │   │   │   ├── PatrolData.java # Database & resort config
│   │   │   │   ├── SessionData.java
│   │   │   │   ├── MailMan.java
│   │   │   │   └── ...
│   │   │   └── DailyReminder.java # Email reminder job
│   │   └── resources/
│   │       ├── templates/        # Thymeleaf templates
│   │       │   ├── list-patrollers.html
│   │       │   ├── member-login.html
│   │       │   ├── reset-password.html
│   │       │   ├── volunteer-form-success.html
│   │       │   ├── directors-menu.html
│   │       │   ├── month-calendar.html
│   │       │   └── fragments/    # Reusable fragments
│   │       ├── application.properties # Spring Boot config
│   │       └── doReminder.sh     # Cron job script
│   └── test/
│       └── java/                 # Unit and integration tests
├── docs/
│   ├── migrations/               # Migration checkpoints
│   └── superpowers/              # Design specs and plans
├── sql                           # Database schema file
├── pom.xml                       # Maven configuration
├── CLAUDE.md                     # AI assistant guidance
├── MIGRATION-STATUS.md           # Spring Boot migration tracking
├── LICENSE                       # Apache License 2.0
└── README.md                     # This file
```

## Usage

### Accessing the Application

Navigate to the volunteer form for a specific resort:
```
http://localhost:8080/calendar-1/VolunteerForm?resort=<ResortName>
```

Valid resort names are configured in `PatrolData.resortMap`.

### Key Endpoints

**Spring Boot Controllers (Migrated):**
- `/ListPatrollers` - Patrol member roster display (Spring MVC + Thymeleaf)
- `/MemberLogin` - Member login page with password reset (Spring MVC + Thymeleaf)
- `/VolunteerForm` - Public volunteer signup form (Spring MVC + Thymeleaf)
- `/MonthCalendar` - Monthly shift calendar (Spring MVC + Thymeleaf)
- `/Directors` - Director administration panel (Spring MVC + Thymeleaf)

- `/DownloadRoster` - Roster listing as an .xlsx workbook, directors only (Apache POI)

Every served endpoint is a Spring Boot controller; see `CLAUDE.md` for the full list. There is no
JSON/REST API — the legacy `ApiResources` servlets (`/login`, `/user/assignments`,
`/patrol/assignments`, `/resort/list`) were never served under Spring Boot and have been removed.

## Adding a New Resort

To add a new ski patrol to the system:

1. Open `src/main/java/org/nsponline/calendar/utils/PatrolData.java`
2. Add a new entry to the `resortMap` in the static initializer block:
   ```java
   resortMap.put("ResortName", new ResortData(
       "ResortName",           // URL parameter
       "Resort Display Name",  // Full name
       null,                   // Optional alternate name
       "http://website.com",   // Resort website
       "/images/logo.jpg",     // Logo path
       80,                     // Image height
       90                      // Image width
   ));
   ```
3. Set up the database tables for the new resort
4. Rebuild and redeploy

## Spring Boot Migration

The migration from Servlet API 2.5 to Spring Boot 4 is complete - no legacy servlets remain:

### Completed Migrations

- **ListPatrollersController** - Patrol member roster with filtering and sorting
- **MemberLoginController** - Login, password reset flow with secure token system
- **VolunteerFormController** - Volunteer application submission
- **DirectorsController** - Director administration menu
- **MonthCalendarController** - Monthly shift calendar display

See `MIGRATION-STATUS.md` for detailed migration progress.

### Migration Approach

Each servlet migration follows this pattern:
1. Create Spring MVC `@Controller` in `controller/` package
2. Extract logic from legacy servlet (minimal refactor)
3. Create Thymeleaf template in `templates/`
4. Use `ServletAdapter` for jakarta→javax conversion with legacy utilities
5. Test coexistence with legacy servlets
6. Verify identical functionality

### Legacy Code Compatibility

- **ServletAdapter** - Bridges jakarta.servlet (Spring Boot) to javax.servlet (legacy code)
- Legacy servlets and utilities remain unchanged
- Gradual migration allows incremental testing and deployment

## Development

### Running a Single Test

```bash
mvn test -Dtest=SimpleTest#testMethod
```

### Debugging

For local debugging:
1. Run `mvn spring-boot:run` or start `CalendarApplication` from your IDE
2. Set breakpoints in controller or servlet classes
3. Access endpoints at `http://localhost:8080/calendar-1/`

For integration tests:
1. Run Maven with the `integration.test` property
2. Attach debugger to port 7070
3. Set breakpoints in test classes

# Check status on server
sudo systemctl status springbootapp.service
# View logs on server
sudo journalctl -u springbootapp.service -f

## Security Features

The Spring Boot migration introduced enhanced security:

- **Password Reset Tokens**: Cryptographically random 24-byte tokens (URL-safe Base64)
- **Token Expiration**: 1-hour validity with automatic cleanup
- **One-time Use**: Tokens invalidated immediately after password reset
- **Secure Storage**: Tokens stored with expiration timestamps in `{resort}_password_reset_tokens` table
- **Email Delivery**: AWS SES integration via JavaMail API

See `docs/migrations/password_reset_tokens.sql` for the database schema.

## Multi-Tenant Architecture

Each ski patrol organization operates independently:

- **Database Isolation**: Separate tables per resort using name prefix (e.g., `Sample_roster`, `Sample_assignments`)
- **Resort Parameter**: All requests require `?resort=ResortName` to identify the organization
- **Configuration**: Resort metadata stored in `PatrolData.resortMap`
- **Session Management**: Separate sessions per resort

## License

Apache License 2.0 - See LICENSE file for details

## Support

For questions or issues, please open an issue on [GitHub](https://github.com/gledhillsr/patrolCalendar1/issues)
