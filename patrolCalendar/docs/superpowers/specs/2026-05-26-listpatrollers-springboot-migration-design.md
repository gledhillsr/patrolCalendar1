# Design: Migrate OuterListPatrollers to Spring Boot + Thymeleaf

**Date:** 2026-05-26  
**Scope:** First servlet-to-controller migration establishing the pattern for modernizing the NSP Calendar application

## Overview

Migrate the OuterListPatrollers servlet to a Spring Boot controller with Thymeleaf templating while maintaining backward compatibility with the remaining 40+ servlets. This is a minimal refactor approach that prioritizes working code and establishes a repeatable migration pattern.

## Goals

1. **Modernize to Java 25 and Spring Boot 3.4** - Move from Java 7/Servlet 2.5 to modern stack
2. **Separate presentation from logic** - Replace inline HTML generation with Thymeleaf templates
3. **Maintain functionality** - Keep all existing features working identically
4. **Enable incremental migration** - Allow servlets and controllers to coexist during transition
5. **Establish migration pattern** - Create a template for migrating the other servlets

## Non-Goals (Deferred to Later)

- Refactoring the business logic or data access patterns
- Implementing Spring Security (keep existing auth for now)
- Creating DTOs or service layer abstractions
- Redesigning the multi-tenant architecture
- Simplifying the 40+ boolean display flags

## Architecture

### Current State

```
OuterListPatrollers (servlet)
├── Extends ResourceBase
├── Reads ~40 query parameters manually
├── Calls initBaseAndAskForValidCredentials() for auth
├── Uses PatrolData/Roster/Assignments for data access
├── Generates HTML by writing to PrintWriter
└── 800 lines of mixed concerns
```

### Target State

```
ListPatrollersController (Spring MVC)
├── @Controller with @GetMapping
├── Reads ~40 query parameters manually (same as before)
├── Adapts auth logic inline (no Spring Security yet)
├── Uses existing PatrolData/Roster/Assignments (no changes)
├── Populates Spring Model for Thymeleaf
└── Returns template name for rendering
```

## Detailed Design

### 1. Project Structure

**New packages and files:**

```
src/main/java/org/nsponline/calendar/
├── CalendarApplication.java                    [NEW] Spring Boot main
├── config/
│   └── LegacyServletConfig.java               [NEW] Register unmigrated servlets
├── controller/
│   └── ListPatrollersController.java          [NEW] Replaces servlet
├── resources/                                  [EXISTING] Keep for unmigrated servlets
│   ├── OuterListPatrollers.java               [DEPRECATE] Remove after verification
│   ├── OuterVolunteerForm.java                [KEEP] Still a servlet
│   ├── InnerChangeShiftAssignments.java       [KEEP] Still a servlet
│   └── ResourceBase.java                      [KEEP] Used by remaining servlets
├── store/                                      [KEEP] No changes
└── utils/                                      [KEEP] No changes

src/main/resources/
├── templates/
│   ├── fragments/
│   │   ├── header.html                        [NEW] Common header
│   │   └── footer.html                        [NEW] Common footer
│   └── list-patrollers.html                   [NEW] Main template
├── static/
│   └── css/
│       └── styles.css                         [NEW] If needed
└── application.properties                      [NEW] Spring Boot config

src/main/webapp/                                [KEEP] For unmigrated servlets
└── WEB-INF/
    └── web.xml                                 [KEEP] Until all migrated
```

### 2. Spring Boot Configuration

**pom.xml updates:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    
    <!-- Add Spring Boot parent -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.0</version>
    </parent>
    
    <groupId>org.nsponline</groupId>
    <artifactId>calendar</artifactId>
    <version>1</version>
    <packaging>jar</packaging>
    
    <properties>
        <java.version>25</java.version>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    
    <dependencies>
        <!-- Spring Boot starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-thymeleaf</artifactId>
        </dependency>
        
        <!-- Keep all existing dependencies -->
        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
            <version>${mysql-connector.version}</version>
        </dependency>
        <!-- ... AWS SES, Jackson, Commons, etc. stay the same ... -->
        
        <!-- Add Tomcat embed jasper for JSP support (legacy servlets) -->
        <dependency>
            <groupId>org.apache.tomcat.embed</groupId>
            <artifactId>tomcat-embed-jasper</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**Why:** Spring Boot 3.4.x supports Java 25. Packaging changes from WAR to JAR with embedded Tomcat. Keep existing dependencies to avoid breaking other servlets.

---

**application.properties:**

```properties
# Server configuration
server.port=8080
server.servlet.context-path=/calendar-1

# Thymeleaf configuration
spring.thymeleaf.cache=false
spring.thymeleaf.mode=HTML
spring.thymeleaf.encoding=UTF-8
spring.thymeleaf.prefix=classpath:/templates/
spring.thymeleaf.suffix=.html

# Logging
logging.level.org.nsponline.calendar=INFO

# No Spring Data/JPA configuration - using existing JDBC approach
```

**Why:** Context path `/calendar-1` matches existing deployment. Thymeleaf caching disabled for development. No database auto-configuration since PatrolData manages connections manually.

---

**CalendarApplication.java:**

```java
package org.nsponline.calendar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class CalendarApplication {
    public static void main(String[] args) {
        SpringApplication.run(CalendarApplication.class, args);
    }
}
```

**Why:** Exclude DataSource auto-configuration since we're using PatrolData's manual connection management. This prevents Spring from trying to configure a datasource we don't need.

### 3. Controller Implementation

**ListPatrollersController.java:**

```java
package org.nsponline.calendar.controller;

import org.nsponline.calendar.store.*;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

@Controller
public class ListPatrollersController {
    private static final boolean DEBUG = false;
    
    @GetMapping("/ListPatrollers")
    public String listPatrollers(
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {
        
        // Initialize logger
        Logger LOG = new Logger(request, "ListPatrollersController");
        
        // 1. Validate resort parameter and initialize base data
        String resort = request.getParameter("resort");
        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }
        
        // 2. Check credentials (adapted from initBaseAndAskForValidCredentials)
        SessionData sessionData = new SessionData(request, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
        
        ValidateCredentialsRedirectIfNeeded credentials = 
            new ValidateCredentialsRedirectIfNeeded(
                sessionData, request, response, 
                "ListPatrollersController", patrolData, LOG);
        
        if (credentials.hasInvalidCredentials()) {
            return "redirect:/MemberLogin?resort=" + resort;
        }
        
        String userId = sessionData.getLoggedInUserId();
        
        // 3. Read all query parameters
        Map<String, Boolean> displayFlags = readDisplayFlags(request);
        Map<String, Integer> dateRange = readDateRange(request);
        Map<String, String> sortOptions = readSortOptions(request);
        List<String> classificationsToDisplay = readClassifications(request);
        int commitmentToDisplay = readCommitmentFlags(request);
        int instructorFlags = readInstructorFlags(request);
        boolean listAll = request.getParameter("ALL") != null;
        boolean listDirector = request.getParameter("ListDirector") != null;
        int textFontSize = parseIntParam(request.getParameter("FontSize"), 14);
        boolean firstNameFirst = !"LAST".equals(request.getParameter("NAME"));
        
        // 4. Load data
        DirectorSettings directorSettings = patrolData.readDirectorSettings();
        Roster editor = patrolData.getMemberByID(userId);
        boolean isDirector = editor != null && editor.isDirector();
        
        // Configure which columns to display
        configureRosterColumns(displayFlags, firstNameFirst);
        
        // Read members and assignments
        String sortString = buildSortString(sortOptions, firstNameFirst);
        List<Roster> members = loadMembers(
            patrolData, sortString, classificationsToDisplay,
            commitmentToDisplay, listAll, listDirector, instructorFlags);
        
        if (displayFlags.get("showNightCnt") || displayFlags.get("showDayCnt") || 
            displayFlags.get("showSwingCnt") || displayFlags.get("showTrainingCnt") ||
            displayFlags.get("showOtherCnt") || displayFlags.get("showHolidayCnt")) {
            loadAssignments(patrolData, members, displayFlags, dateRange,
                classificationsToDisplay, commitmentToDisplay, 
                listAll, listDirector, instructorFlags);
        }
        
        // Sort by shift count if requested
        if ("shiftCnt".equals(sortOptions.get("sort1"))) {
            members = sortByShiftCount(members, displayFlags, dateRange);
        }
        
        patrolData.close();
        
        // 5. Prepare model for template
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("userId", userId);
        model.addAttribute("isDirector", isDirector);
        model.addAttribute("emailAllAllowed", 
            directorSettings != null && directorSettings.getEmailAll());
        model.addAttribute("members", members);
        model.addAttribute("patrollersListed", members.size());
        model.addAttribute("currentDate", new Date());
        model.addAttribute("textFontSize", textFontSize);
        
        // Add all display flags to model
        model.addAllAttributes(displayFlags);
        
        // Build email form URL
        String emailFormUrl = buildEmailFormUrl(resort, userId, displayFlags,
            classificationsToDisplay, commitmentToDisplay, listAll, 
            listDirector, instructorFlags, dateRange);
        model.addAttribute("emailFormUrl", emailFormUrl);
        
        return "list-patrollers";
    }
    
    // Helper methods (extracted from servlet logic)
    
    private Map<String, Boolean> readDisplayFlags(HttpServletRequest request) {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put("showClass", request.getParameter("CLASS") != null);
        flags.put("showID", request.getParameter("SHOW_ID") != null);
        flags.put("showBlank", request.getParameter("SHOW_BLANK") != null);
        flags.put("showBlankWide", request.getParameter("SHOW_BLANK2") != null);
        flags.put("showSpouse", request.getParameter("SPOUSE") != null);
        flags.put("showAddr", request.getParameter("ADDR") != null);
        flags.put("showCity", request.getParameter("CITY") != null);
        flags.put("showState", request.getParameter("STATE") != null);
        flags.put("showZip", request.getParameter("ZIP") != null);
        flags.put("showHome", request.getParameter("HOME") != null);
        flags.put("showWork", request.getParameter("WORK") != null);
        flags.put("showCell", request.getParameter("CELL") != null);
        flags.put("showPager", request.getParameter("PAGER") != null);
        flags.put("showEmail", request.getParameter("EMAIL") != null);
        flags.put("showEmergency", request.getParameter("EMERGENCY") != null);
        flags.put("showSubstitute", request.getParameter("SUBSTITUTE") != null);
        flags.put("showCommit", request.getParameter("COMMIT") != null);
        flags.put("showInstructor", request.getParameter("INSTRUCTOR") != null);
        flags.put("showDirector", request.getParameter("DIRECTOR") != null);
        flags.put("showLastUpdated", request.getParameter("LAST_UPDATED") != null);
        flags.put("showComments", request.getParameter("COMMENTS") != null);
        flags.put("showCreditDate", request.getParameter("LAST_CREDIT_UPDATE") != null);
        flags.put("showNightCnt", request.getParameter("NIGHT_CNT") != null);
        flags.put("showDayCnt", request.getParameter("DAY_CNT") != null);
        flags.put("showSwingCnt", request.getParameter("SWING_CNT") != null);
        flags.put("showTrainingCnt", request.getParameter("TRAINING_CNT") != null);
        flags.put("showOtherCnt", request.getParameter("OTHER_CNT") != null);
        flags.put("showHolidayCnt", request.getParameter("HOLIDAY_CNT") != null);
        flags.put("showNightList", request.getParameter("NIGHT_DETAILS") != null);
        flags.put("showSwingList", request.getParameter("SWING_DETAILS") != null);
        flags.put("showDayList", request.getParameter("DAY_DETAILS") != null);
        flags.put("showTrainingList", request.getParameter("TRAINING_DETAILS") != null);
        flags.put("showOtherList", request.getParameter("OTHER_DETAILS") != null);
        flags.put("showHolidayList", request.getParameter("HOLIDAY_DETAILS") != null);
        flags.put("showTeamLead", request.getParameter("TEAM_LEAD") != null);
        flags.put("showMentoring", request.getParameter("MENTORING") != null);
        flags.put("showCreditsEarned", request.getParameter("CREDITS_EARNED") != null);
        flags.put("showCanEarnCredits", request.getParameter("CAN_EARN_CREDITS") != null);
        return flags;
    }
    
    private Map<String, Integer> readDateRange(HttpServletRequest request) {
        Map<String, Integer> range = new HashMap<>();
        range.put("startDay", parseIntParam(request.getParameter("StartDay"), 0));
        range.put("startMonth", parseIntParam(request.getParameter("StartMonth"), 0));
        range.put("startYear", parseIntParam(request.getParameter("StartYear"), 0));
        range.put("endDay", parseIntParam(request.getParameter("EndDay"), 0));
        range.put("endMonth", parseIntParam(request.getParameter("EndMonth"), 0));
        range.put("endYear", parseIntParam(request.getParameter("EndYear"), 0));
        range.put("minDays", parseIntParam(request.getParameter("MinDays"), 0));
        range.put("useMinDays", request.getParameter("MIN_DAYS") != null ? 1 : 0);
        return range;
    }
    
    private Map<String, String> readSortOptions(HttpServletRequest request) {
        Map<String, String> sort = new HashMap<>();
        sort.put("sort1", request.getParameter("FirstSort"));
        sort.put("sort2", request.getParameter("SecondSort"));
        sort.put("sort3", request.getParameter("ThirdSort"));
        return sort;
    }
    
    private List<String> readClassifications(HttpServletRequest request) {
        String[] incList = {"BAS", "INA", "SR", "SRA", "ALM", "PRO", "AUX", "TRA", "CAN", "OTH"};
        List<String> classifications = new ArrayList<>();
        for (String classification : incList) {
            if (request.getParameter(classification) != null) {
                classifications.add(classification);
            }
        }
        return classifications;
    }
    
    private int readCommitmentFlags(HttpServletRequest request) {
        int commitment = 0;
        if (request.getParameter("FullTime") != null) commitment += 4;
        if (request.getParameter("PartTime") != null) commitment += 2;
        if (request.getParameter("Inactive") != null) commitment += 1;
        return commitment;
    }
    
    private int readInstructorFlags(HttpServletRequest request) {
        int flags = 0;
        if (request.getParameter("OEC") != null) flags += 1;
        if (request.getParameter("CPR") != null) flags += 2;
        if (request.getParameter("Ski") != null) flags += 4;
        if (request.getParameter("Toboggan") != null) flags += 8;
        return flags;
    }
    
    private void configureRosterColumns(Map<String, Boolean> flags, boolean firstNameFirst) {
        Roster.addColumn(-1);
        Roster.addColumn(firstNameFirst ? Roster.FIRST : Roster.LAST);
        if (flags.get("showBlank")) Roster.addColumn(Roster.BLANK);
        if (flags.get("showBlankWide")) Roster.addColumn(Roster.BLANK_WIDE);
        if (flags.get("showClass")) Roster.addColumn(Roster.CLASSIFICATION);
        if (flags.get("showID")) Roster.addColumn(Roster.ID_NUM);
        // ... configure all other columns based on flags ...
    }
    
    private String buildSortString(Map<String, String> sort, boolean firstNameFirst) {
        // Replicate getSortString() logic from servlet
        String sort1 = sort.get("sort1");
        String sortString;
        
        if (sort1 == null || sort1.equals("Name") || sort1.equals("shiftCnt")) {
            sortString = firstNameFirst ? "FirstName,LastName" : "LastName,FirstName";
        } else if (sort1.equals("Class")) {
            sortString = "ClassificationCode";
        } else if (sort1.equals("Comm")) {
            sortString = "Commitment";
        } else if (sort1.equals("Updt")) {
            sortString = "lastUpdated";
        } else {
            sortString = "FirstName,LastName";
        }
        
        // Add secondary and tertiary sorts...
        // (same logic as servlet)
        
        return sortString;
    }
    
    private List<Roster> loadMembers(
            PatrolData patrol, String sortString,
            List<String> classifications, int commitment,
            boolean listAll, boolean listDirector, int instructorFlags) {
        
        List<Roster> members = new ArrayList<>();
        ResultSet rosterResults = patrol.resetRoster(sortString);
        Roster member;
        
        while ((member = patrol.nextMember("&nbsp;", rosterResults)) != null) {
            if (member.okToDisplay(false, false, listAll, 
                    classifications, commitment, listDirector, instructorFlags, 0)) {
                members.add(member);
            }
        }
        
        return members;
    }
    
    private void loadAssignments(
            PatrolData patrol, List<Roster> members,
            Map<String, Boolean> flags, Map<String, Integer> dateRange,
            List<String> classifications, int commitment,
            boolean listAll, boolean listDirector, int instructorFlags) {
        
        // Replicate readAssignments() logic from servlet
        // Build member hash, iterate assignments, populate counts
        // (same logic as servlet's readAssignments method)
    }
    
    private List<Roster> sortByShiftCount(
            List<Roster> members, Map<String, Boolean> flags,
            Map<String, Integer> dateRange) {
        
        // Replicate shift count sorting logic using TreeMap
        // (same logic as servlet's printBody when sort1="shiftCnt")
        return members; // sorted
    }
    
    private String buildEmailFormUrl(
            String resort, String userId, Map<String, Boolean> flags,
            List<String> classifications, int commitment,
            boolean listAll, boolean listDirector, int instructorFlags,
            Map<String, Integer> dateRange) {
        
        // Build URL with all query parameters
        // (same logic as servlet's showButtonsAtTop)
        return "EmailForm?resort=" + resort + "&ID=" + userId + "...";
    }
    
    private int parseIntParam(String param, int defaultValue) {
        try {
            return (param != null) ? Integer.parseInt(param) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
```

**Why:** Direct translation of servlet logic into controller methods. All the business logic stays the same - parameter reading, data loading, filtering, sorting. The only difference is returning a template name instead of writing to PrintWriter.

### 4. Thymeleaf Template

**templates/list-patrollers.html:**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title th:text="'Members of the ' + ${resortFullName} + ' Ski Patrol'">Patrol Members</title>
    <style type="text/css">
        body {
            font-size: 10pt;
            color: #000000;
            background-color: #ffffff;
        }
        table.list {
            border-width: 1px;
            border-color: #000000;
            border-style: solid;
            border-collapse: collapse;
            border-spacing: 0;
        }
        table.list th {
            font-size: [[${textFontSize}]]pt;
            font-weight: bold;
            color: #000000;
            background-color: #ffffff;
            border-width: 1px;
            border-color: #000000;
            border-style: solid;
            padding: 2px;
        }
        table.list td {
            font-size: [[${textFontSize}]]pt;
            color: #000000;
            background-color: #ffffff;
            border-width: 1px;
            border-color: #000000;
            border-style: solid;
            padding: 1px;
        }
    </style>
    <script>
        function printWindow() {
            var bV = parseInt(navigator.appVersion);
            if (bV >= 4) window.print();
        }
    </script>
</head>
<body>
    <!-- Common header will be extracted to fragment later -->
    <div th:replace="~{fragments/header :: header(${resort})}"></div>
    
    <p><center><h2 th:text="'Members of the ' + ${resortFullName} + ' Ski Patrol'">Members</h2></center></p>
    
    <!-- Email/Download buttons -->
    <div th:if="${isDirector or emailAllAllowed}">
        <p><font size="3"><bold>
            <input type="button" 
                   value="e-mail THESE patrollers" 
                   th:onclick="'window.location=\'' + ${emailFormUrl} + '\''"/>
            &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
            
            <!-- Download button (conditional) -->
            <input th:if="${isDirector and userId == '192443'}"
                   type="button"
                   value="Steve, Download table (under construction)"
                   th:onclick="'window.location=\'' + ${downloadUrl} + '\''"/>
        </bold></font></p>
    </div>
    
    <!-- Main member table -->
    <table class="list" 
           style="font-size: 10pt; face='Verdana, Arial, Helvetica'" 
           border="1" 
           width="99%" 
           bordercolordark="#003366" 
           bordercolorlight="#C0C0C0">
        <tr>
            <!-- Dynamic column headers based on display flags -->
            <th>Name</th>
            <th th:if="${showBlank}">&nbsp;</th>
            <th th:if="${showBlankWide}">&nbsp;&nbsp;&nbsp;</th>
            <th th:if="${showClass}">Class</th>
            <th th:if="${showID}">ID</th>
            <th th:if="${showSpouse}">Spouse</th>
            <th th:if="${showAddr}">Address</th>
            <th th:if="${showCity}">City</th>
            <th th:if="${showState}">State</th>
            <th th:if="${showZip}">Zip</th>
            <th th:if="${showHome}">Home</th>
            <th th:if="${showWork}">Work</th>
            <th th:if="${showCell}">Cell</th>
            <th th:if="${showPager}">Pager</th>
            <th th:if="${showEmail}">Email</th>
            <th th:if="${showEmergency}">Call Up</th>
            <th th:if="${showSubstitute}">Sub</th>
            <th th:if="${showCommit}">Commitment</th>
            <th th:if="${showInstructor}">Instructor</th>
            <th th:if="${showDirector}">Director</th>
            <th th:if="${showLastUpdated}">Last Updated</th>
            <th th:if="${showComments}">Private Comments</th>
            <th th:if="${showCanEarnCredits}">Can Earn Credits</th>
            <th th:if="${showCreditsEarned}">Credits Available</th>
            <th th:if="${showCreditDate}">Last Credit Update</th>
            <th th:if="${showTeamLead}">Team Lead</th>
            <th th:if="${showMentoring}">Mentoring</th>
            <th th:if="${showDayCnt}">Day Shifts</th>
            <th th:if="${showDayList}">Day Shift Details</th>
            <th th:if="${showSwingCnt}">Swing Shifts</th>
            <th th:if="${showSwingList}">Swing Shift Details</th>
            <th th:if="${showNightCnt}">Night Shifts</th>
            <th th:if="${showNightList}">Night Shift Details</th>
            <th th:if="${showTrainingCnt}">Training Shifts</th>
            <th th:if="${showTrainingList}">Training Shift Details</th>
            <th th:if="${showOtherCnt}">Other Shifts</th>
            <th th:if="${showOtherList}">Other Shift Details</th>
            <th th:if="${showHolidayCnt}">Holiday Shifts</th>
            <th th:if="${showHolidayList}">Holiday Shift Details</th>
        </tr>
        
        <!-- Loop through members -->
        <tr th:each="member : ${members}">
            <td th:text="${member.getDisplayName()}">Name</td>
            <td th:if="${showBlank}">&nbsp;</td>
            <td th:if="${showBlankWide}">&nbsp;&nbsp;&nbsp;</td>
            <td th:if="${showClass}" th:text="${member.getClassification()}">Class</td>
            <td th:if="${showID}" th:text="${member.getID()}">ID</td>
            <td th:if="${showSpouse}" th:text="${member.getSpouse()}">Spouse</td>
            <td th:if="${showAddr}" th:text="${member.getAddress()}">Address</td>
            <td th:if="${showCity}" th:text="${member.getCity()}">City</td>
            <td th:if="${showState}" th:text="${member.getState()}">State</td>
            <td th:if="${showZip}" th:text="${member.getZipCode()}">Zip</td>
            <td th:if="${showHome}" th:text="${member.getHomePhone()}">Home</td>
            <td th:if="${showWork}" th:text="${member.getWorkPhone()}">Work</td>
            <td th:if="${showCell}" th:text="${member.getCellPhone()}">Cell</td>
            <td th:if="${showPager}" th:text="${member.getPager()}">Pager</td>
            <td th:if="${showEmail}" th:text="${member.getEmailAddress()}">Email</td>
            <td th:if="${showEmergency}" th:text="${member.getEmergencyCallUp()}">Emergency</td>
            <td th:if="${showSubstitute}" th:text="${member.getNightSubstitute()}">Sub</td>
            <td th:if="${showCommit}" th:text="${member.getCommitment()}">Commitment</td>
            <td th:if="${showInstructor}" th:text="${member.getInstructor()}">Instructor</td>
            <td th:if="${showDirector}" th:text="${member.isDirector() ? 'Yes' : 'No'}">Director</td>
            <td th:if="${showLastUpdated}" th:text="${member.getLastUpdated()}">Updated</td>
            <td th:if="${showComments}" th:text="${member.getComments()}">Comments</td>
            <td th:if="${showCanEarnCredits}" th:text="${member.canEarnCredits() ? 'Yes' : 'No'}">Credits</td>
            <td th:if="${showCreditsEarned}" th:text="${member.getCreditsEarned()}">Earned</td>
            <td th:if="${showCreditDate}" th:text="${member.getLastCreditUpdate()}">Credit Date</td>
            <td th:if="${showTeamLead}" th:text="${member.isTeamLead() ? 'Yes' : 'No'}">Team Lead</td>
            <td th:if="${showMentoring}" th:text="${member.isMentoring() ? 'Yes' : 'No'}">Mentoring</td>
            
            <!-- Assignment counts and details -->
            <td th:if="${showDayCnt}" th:text="${member.AssignmentCount[1]}">0</td>
            <td th:if="${showDayList}" th:text="${member.szAssignments[1]}">Details</td>
            <td th:if="${showSwingCnt}" th:text="${member.AssignmentCount[2]}">0</td>
            <td th:if="${showSwingList}" th:text="${member.szAssignments[2]}">Details</td>
            <td th:if="${showNightCnt}" th:text="${member.AssignmentCount[0]}">0</td>
            <td th:if="${showNightList}" th:text="${member.szAssignments[0]}">Details</td>
            <td th:if="${showTrainingCnt}" th:text="${member.AssignmentCount[3]}">0</td>
            <td th:if="${showTrainingList}" th:text="${member.szAssignments[3]}">Details</td>
            <td th:if="${showOtherCnt}" th:text="${member.AssignmentCount[4]}">0</td>
            <td th:if="${showOtherList}" th:text="${member.szAssignments[4]}">Details</td>
            <td th:if="${showHolidayCnt}" th:text="${member.AssignmentCount[5]}">0</td>
            <td th:if="${showHolidayList}" th:text="${member.szAssignments[5]}">Details</td>
        </tr>
    </table>
    
    <!-- Footer -->
    <div>
        <span th:text="'Total Patrollers Listed=' + ${patrollersListed}">Total</span>
        <br/>
        <span th:text="'As of: ' + ${#dates.format(currentDate, 'EEE MMM dd HH:mm:ss zzz yyyy')}">Date</span>
    </div>
    
    <!-- Common footer will be extracted to fragment later -->
    <div th:replace="~{fragments/footer :: footer}"></div>
</body>
</html>
```

**Why:** Uses Thymeleaf's `th:if` for conditional rendering matching the servlet's display flags. Uses `th:each` to loop through members. Uses `th:text` for safe output. Template structure mirrors the servlet's HTML output exactly.

**How to apply:** The template must produce identical HTML to the servlet for the migration to be verifiable. Any differences in output should be investigated and fixed.

---

**templates/fragments/header.html:**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head></head>
<body>
    <div th:fragment="header(resort)">
        <!-- Resort logo, navigation, etc. -->
        <!-- Extracted from OuterPage.printResortHeader() -->
        <div>
            <img th:src="@{/images/{resort}-logo.jpg(resort=${resort})}" 
                 alt="Resort Logo"/>
            <!-- Navigation links -->
        </div>
    </div>
</body>
</html>
```

**templates/fragments/footer.html:**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head></head>
<body>
    <div th:fragment="footer">
        <!-- Common footer content -->
        <!-- Extracted from OuterPage.printResortFooter() -->
        <footer>
            <p>&copy; Patrol Calendar</p>
        </footer>
    </div>
</body>
</html>
```

**Why:** Reusable fragments reduce duplication and make it easier to migrate other servlets using the same header/footer.

### 5. Coexistence Strategy

**LegacyServletConfig.java:**

```java
package org.nsponline.calendar.config;

import org.nsponline.calendar.resources.*;
import org.nsponline.calendar.utils.Logger;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
public class LegacyServletConfig {
    
    // Register unmigrated servlets
    
    @Bean
    public ServletRegistrationBean<VolunteerFormServletWrapper> volunteerFormServlet() {
        return new ServletRegistrationBean<>(
            new VolunteerFormServletWrapper(), "/VolunteerForm");
    }
    
    @Bean
    public ServletRegistrationBean<MemberLoginServletWrapper> memberLoginServlet() {
        return new ServletRegistrationBean<>(
            new MemberLoginServletWrapper(), "/MemberLogin");
    }
    
    // ... register other unmigrated servlets ...
    
    // Wrapper classes to adapt old servlet-style constructors
    
    private static class VolunteerFormServletWrapper extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
                throws IOException {
            Logger LOG = new Logger(req, "OuterVolunteerForm");
            OuterVolunteerForm servlet = new OuterVolunteerForm(req, resp, LOG);
            servlet.runner("OuterVolunteerForm");
        }
    }
    
    private static class MemberLoginServletWrapper extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
                throws IOException {
            Logger LOG = new Logger(req, "OuterMemberLogin");
            OuterMemberLogin servlet = new OuterMemberLogin(req, resp, LOG);
            servlet.runner("OuterMemberLogin");
        }
    }
}
```

**Why:** Allows unmigrated servlets to run alongside Spring controllers during the transition. Each servlet gets wrapped and registered with Spring Boot's embedded Tomcat. Once all servlets are migrated, this configuration class can be deleted.

**How to apply:** Add servlet wrappers incrementally as needed. Delete wrappers once their corresponding controllers are migrated and verified.

### 6. Build and Run

**Building:**

```bash
# Clean build
mvn clean package

# Produces: target/calendar-1.jar (executable JAR with embedded Tomcat)
```

**Running locally:**

```bash
# Run Spring Boot application
java -jar target/calendar-1.jar

# Or use Maven plugin
mvn spring-boot:run

# Application starts on http://localhost:8080/calendar-1/
```

**Testing the migrated endpoint:**

```bash
# Access via browser
http://localhost:8080/calendar-1/ListPatrollers?resort=Sample

# All query parameters work the same as servlet version
http://localhost:8080/calendar-1/ListPatrollers?resort=Sample&CLASS=1&SHOW_ID=1&BAS=1&FullTime=1
```

**Verification strategy:**

1. Run both old servlet and new controller side-by-side
2. Compare HTML output byte-by-byte (excluding timestamps)
3. Test all parameter combinations
4. Verify authentication redirects work correctly
5. Test with multiple resorts
6. Check email button functionality
7. Verify sorting and filtering logic

### 7. Migration Checklist

**Phase 1: Setup (Day 1)**
- [ ] Update pom.xml with Spring Boot parent and dependencies
- [ ] Create CalendarApplication.java
- [ ] Create application.properties
- [ ] Verify project builds and runs

**Phase 2: Controller (Day 2-3)**
- [ ] Create ListPatrollersController.java
- [ ] Implement parameter reading helpers
- [ ] Implement data loading logic
- [ ] Implement sorting/filtering logic
- [ ] Test controller returns correct model data

**Phase 3: Template (Day 3-4)**
- [ ] Create list-patrollers.html template
- [ ] Implement table structure with conditional columns
- [ ] Create header/footer fragments
- [ ] Test template renders correctly

**Phase 4: Integration (Day 4-5)**
- [ ] Create LegacyServletConfig for remaining servlets
- [ ] Test new controller endpoint
- [ ] Compare output with old servlet
- [ ] Fix any rendering differences
- [ ] Test all parameter combinations

**Phase 5: Verification (Day 5)**
- [ ] Test authentication/authorization
- [ ] Test multi-tenant behavior (multiple resorts)
- [ ] Test email button functionality
- [ ] Test sorting options
- [ ] Test filtering options
- [ ] Performance comparison
- [ ] Browser compatibility check

**Phase 6: Cleanup (Day 6)**
- [ ] Remove OuterListPatrollers.java
- [ ] Update CLAUDE.md with Spring Boot info
- [ ] Document migration pattern for next servlet
- [ ] Commit changes

## Testing Strategy

**Unit Testing:**
- Not required for this migration (keeping logic as-is)
- Existing JUnit tests should still pass
- Integration tests via Jetty will need Spring Boot equivalent

**Manual Testing:**
- Compare rendered HTML from servlet vs controller
- Test all 40+ display flag combinations
- Verify sorting by name, classification, commitment, shift count
- Test date range filtering
- Test minimum days filtering
- Verify multi-tenant behavior with different resorts

**Regression Testing:**
- All existing functionality must work identically
- No visual differences in output
- Same performance characteristics
- Same authentication behavior

## Rollback Plan

If issues arise during migration:

1. **Servlet still exists** - Can revert servlet mapping in LegacyServletConfig
2. **Branch strategy** - Migrate on feature branch, merge only after verification
3. **Gradual rollout** - Deploy to test environment first, then production
4. **Fallback URL** - Keep both `/ListPatrollers` (new) and `/ListPatrollersOld` (servlet) during transition

## Success Criteria

Migration is complete when:

1. ✅ ListPatrollersController produces identical HTML output to OuterListPatrollers servlet
2. ✅ All query parameter combinations work correctly
3. ✅ Authentication and session management work as before
4. ✅ Multi-tenant resort switching works correctly
5. ✅ Email button and links generate correct URLs
6. ✅ Sorting and filtering produce identical results
7. ✅ Performance is comparable or better
8. ✅ No regressions in other servlets
9. ✅ Code is reviewed and committed
10. ✅ Documentation updated

## Future Improvements (Out of Scope)

After establishing the migration pattern with this first servlet:

- **Spring Security integration** - Replace custom auth with Spring Security
- **Service layer extraction** - Separate business logic from controllers
- **Repository pattern** - Replace direct JDBC with Spring Data JPA
- **DTO objects** - Create request/response objects instead of raw parameters
- **Multi-tenancy refactor** - Implement proper schema-per-tenant or discriminator approach
- **UI simplification** - Reduce the 40+ boolean flags with better UX design
- **REST API** - Create proper REST endpoints alongside HTML views
- **Testing** - Add comprehensive unit and integration tests
- **Configuration** - Extract hardcoded values to properties files
- **Logging** - Replace custom Logger with SLF4J/Logback

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Output differences break existing integrations | High | Byte-by-byte comparison testing before deployment |
| Performance degradation | Medium | Load testing, profiling before production |
| Session management breaks | High | Extensive auth testing with real user sessions |
| Other servlets stop working | High | Keep servlets registered, test all endpoints |
| Spring Boot overhead on old hardware | Medium | Monitor resource usage, tune JVM settings |
| Thymeleaf template errors | Low | Template validation, error handling |
| Multi-tenant logic breaks | High | Test with all configured resorts |
| Dependency conflicts | Medium | Careful pom.xml management, dependency resolution |

## Open Questions

None - design is approved and ready for implementation.
