# MemberLogin Spring Boot Migration Design Specification

**Date:** 2026-05-26  
**Status:** Approved  
**Migration Pattern:** ListPatrollers established pattern + security enhancement

---

## 1. Overview

### Goal
Migrate `OuterMemberLogin` and `OuterLoginHelp` servlets to Spring Boot controller with Thymeleaf templates while improving password reset security from plaintext email delivery to secure token-based reset links.

### Scope
- Migrate OuterMemberLogin servlet (login form display)
- Migrate OuterLoginHelp servlet (authentication + password reset)
- Implement secure password reset with token-based links
- Create new password_reset_tokens database table
- Maintain compatibility with existing session management
- Follow established ListPatrollers migration pattern

### Out of Scope
- Spring Security framework integration (future enhancement)
- Password strength validation (future enhancement)
- Rate limiting on reset requests (future enhancement)
- CSRF protection (future enhancement)
- Multi-factor authentication (future enhancement)

---

## 2. Architecture

### Components

**New Components:**
1. **MemberLoginController** - Spring MVC controller (`@Controller`)
   - Location: `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`
   - Replaces: OuterMemberLogin.java, OuterLoginHelp.java

2. **PasswordResetToken** - Token management store class
   - Location: `src/main/java/org/nsponline/calendar/store/PasswordResetToken.java`
   - Purpose: Generate, validate, and manage password reset tokens

3. **member-login.html** - Login form template
   - Location: `src/main/resources/templates/member-login.html`
   - Includes: Login form, password reset request form, NSP card images

4. **reset-password.html** - Password reset form template
   - Location: `src/main/resources/templates/reset-password.html`
   - Purpose: Allow user to enter new password after clicking email link

5. **Database Table** - Password reset tokens per resort
   - Table name: `{resort}_password_reset_tokens`
   - Purpose: Store and validate reset tokens

**Reused Components:**
- ServletAdapter (from ListPatrollers migration)
- PatrolData (database connection, isValidLogin)
- SessionData (session management)
- Roster (member data)
- MailMan (email delivery)
- Header/Footer fragments (from ListPatrollers)

### Request Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                        Login Flow                            │
└─────────────────────────────────────────────────────────────┘

GET /MemberLogin?resort=Sample&NSPgoto=MonthCalendar
    ↓
[MemberLoginController.showLoginForm]
    ↓
Display member-login.html
    ↓
User enters ID + Password
    ↓
POST /LoginHelp (ID, Password, resort, NSPgoto)
    ↓
[MemberLoginController.processLogin → handleLoginAttempt]
    ↓
PatrolData.isValidLogin(ID, Password)
    ↓
    ├─ Valid ───→ SessionData.setLoggedInUserId(ID)
    │             SessionData.setLoggedInResort(resort)
    │             response.sendRedirect(NSPgoto destination)
    │
    └─ Invalid ─→ model.addAttribute("errorMessage")
                  Return member-login.html


┌─────────────────────────────────────────────────────────────┐
│                   Password Reset Flow                        │
└─────────────────────────────────────────────────────────────┘

POST /LoginHelp?emailme=1 (ID2 or email, resort)
    ↓
[MemberLoginController.processLogin → handlePasswordResetRequest]
    ↓
Find member by ID or email
    ↓
    ├─ Found ──→ PasswordResetToken.createToken(memberId)
    │            Save token to database
    │            Build reset URL with token
    │            MailMan.sendMessage(reset link email)
    │            Display success message
    │
    └─ Not Found → Display error message

Email delivered to member:
"Click link: /ResetPassword?resort=Sample&token=abc123..."
    ↓
GET /ResetPassword?resort=Sample&token=abc123
    ↓
[MemberLoginController.showResetForm]
    ↓
Validate token (exists, not used, not expired)
    ↓
    ├─ Valid ───→ Display reset-password.html
    │
    └─ Invalid ─→ Redirect to /MemberLogin with error

User enters new password + confirmation
    ↓
POST /ResetPassword (token, newPassword, confirmPassword)
    ↓
[MemberLoginController.processPasswordReset]
    ↓
Validate token + passwords match
    ↓
Roster.updatePassword(memberId, newPassword)
PasswordResetToken.markAsUsed(token)
    ↓
Redirect to /MemberLogin with success message
```

---

## 3. Database Schema

### Password Reset Tokens Table

**Table Name:** `{resort}_password_reset_tokens`  
**Multi-Tenant:** One table per resort (e.g., `Sample_password_reset_tokens`, `BreckenridgeNSP_password_reset_tokens`)

**Schema:**
```sql
CREATE TABLE {resort}_password_reset_tokens (
    token VARCHAR(64) PRIMARY KEY,
    member_id VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    
    INDEX idx_member_id (member_id),
    INDEX idx_expires (expires_at)
);
```

**Column Descriptions:**
- `token` - UUID-based random token (64 chars hex)
- `member_id` - Foreign key to `{resort}_roster.IDNumber`
- `created_at` - Timestamp when token was generated
- `expires_at` - Timestamp when token expires (created_at + 1 HOUR)
- `used` - Boolean flag, set to TRUE after password reset

**Indexes:**
- `idx_member_id` - Fast lookup for cleanup/auditing by member
- `idx_expires` - Fast lookup for expired token cleanup

**Housekeeping:**
- Periodic cleanup job to delete expired tokens (future enhancement)
- Tokens auto-expire after 1 hour via validation logic

---

## 4. PasswordResetToken Store Class

**Location:** `src/main/java/org/nsponline/calendar/store/PasswordResetToken.java`

**Class Structure:**
```java
package org.nsponline.calendar.store;

import java.sql.*;
import java.util.UUID;

public class PasswordResetToken {
    private String token;
    private String memberId;
    private Timestamp createdAt;
    private Timestamp expiresAt;
    private boolean used;
    
    // Constructor
    public PasswordResetToken(String token, String memberId, 
                              Timestamp createdAt, Timestamp expiresAt, 
                              boolean used);
    
    // Factory method: Create new token
    public static PasswordResetToken createToken(String memberId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp expires = new Timestamp(now.getTime() + 3600000); // +1 hour
        return new PasswordResetToken(token, memberId, now, expires, false);
    }
    
    // Persistence methods
    public void save(Connection connection, String resort) throws SQLException {
        String sql = "INSERT INTO " + resort + "_password_reset_tokens " +
                     "(token, member_id, created_at, expires_at, used) " +
                     "VALUES (?, ?, ?, ?, ?)";
        // PreparedStatement execution
    }
    
    public static PasswordResetToken findByToken(Connection connection, 
                                                  String resort, 
                                                  String token) throws SQLException {
        String sql = "SELECT * FROM " + resort + "_password_reset_tokens " +
                     "WHERE token = ?";
        // Query and return PasswordResetToken or null
    }
    
    public void markAsUsed(Connection connection, String resort) throws SQLException {
        String sql = "UPDATE " + resort + "_password_reset_tokens " +
                     "SET used = TRUE WHERE token = ?";
        // PreparedStatement execution
    }
    
    // Validation
    public boolean isValid() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return !used && expiresAt.after(now);
    }
    
    // Housekeeping (called periodically)
    public static void deleteExpired(Connection connection, 
                                     String resort) throws SQLException {
        String sql = "DELETE FROM " + resort + "_password_reset_tokens " +
                     "WHERE expires_at < ?";
        // PreparedStatement execution
    }
    
    // Getters
    public String getToken();
    public String getMemberId();
    public Timestamp getCreatedAt();
    public Timestamp getExpiresAt();
    public boolean isUsed();
}
```

**Token Generation:**
- Uses `UUID.randomUUID()` for cryptographic security
- Removes hyphens for cleaner URLs (32 hex chars)
- Example: `a1b2c3d4e5f67890a1b2c3d4e5f67890`

**Validation Logic:**
1. Token exists in database
2. `used = FALSE`
3. `expires_at > current_timestamp`

---

## 5. MemberLoginController Implementation

**Location:** `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`

### Endpoint Methods

#### 5.1 GET /MemberLogin - Display Login Form

```java
@GetMapping("/MemberLogin")
public String showLoginForm(
    HttpServletRequest request, 
    HttpServletResponse response, 
    Model model) throws IOException {
    
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String gotoPage = request.getParameter("NSPgoto");
    
    // 2. Validate resort
    if (!PatrolData.isValidResort(resort)) {
        response.sendError(400, "Invalid resort: " + resort);
        return null;
    }
    
    // 3. Set defaults
    if (StaticUtils.isEmpty(gotoPage)) {
        gotoPage = "MonthCalendar";
    }
    
    // 4. Prepare model
    model.addAttribute("resort", resort);
    model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
    model.addAttribute("gotoPage", gotoPage);
    model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));
    
    // 5. Return template
    return "member-login";
}
```

#### 5.2 POST /LoginHelp - Process Login or Password Reset

```java
@PostMapping("/LoginHelp")
public String processLogin(
    HttpServletRequest request, 
    HttpServletResponse response, 
    Model model) throws IOException {
    
    // Check if this is a password reset request or login attempt
    String emailme = request.getParameter("emailme");
    
    if (emailme != null) {
        return handlePasswordResetRequest(request, response, model);
    } else {
        return handleLoginAttempt(request, response, model);
    }
}
```

#### 5.3 GET /ResetPassword - Display Password Reset Form

```java
@GetMapping("/ResetPassword")
public String showResetForm(
    HttpServletRequest request, 
    HttpServletResponse response, 
    Model model) throws IOException {
    
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String token = request.getParameter("token");
    
    // 2. Validate resort
    if (!PatrolData.isValidResort(resort)) {
        response.sendError(400, "Invalid resort");
        return null;
    }
    
    // 3. Validate token exists
    if (StaticUtils.isEmpty(token)) {
        model.addAttribute("errorMessage", "Invalid or missing reset token");
        return "redirect:/MemberLogin?resort=" + resort;
    }
    
    // 4. Use ServletAdapter for legacy code
    javax.servlet.http.HttpServletRequest javaxRequest = 
        ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, 
                            "GET", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    
    try {
        Connection conn = patrolData.getConnection();
        
        // 5. Validate token
        PasswordResetToken resetToken = 
            PasswordResetToken.findByToken(conn, resort, token);
        
        if (resetToken == null || !resetToken.isValid()) {
            model.addAttribute("errorMessage", 
                "This reset link has expired or is invalid. " +
                "Please request a new password reset.");
            return "redirect:/MemberLogin?resort=" + resort;
        }
        
        // 6. Prepare model
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", 
            PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("token", token);
        
        return "reset-password";
        
    } finally {
        patrolData.close();
    }
}
```

#### 5.4 POST /ResetPassword - Process Password Reset

```java
@PostMapping("/ResetPassword")
public String processPasswordReset(
    HttpServletRequest request, 
    HttpServletResponse response, 
    Model model) throws IOException {
    
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String token = request.getParameter("token");
    String newPassword = request.getParameter("newPassword");
    String confirmPassword = request.getParameter("confirmPassword");
    
    // 2. Validate passwords match
    if (!newPassword.equals(confirmPassword)) {
        model.addAttribute("errorMessage", "Passwords do not match");
        model.addAttribute("resort", resort);
        model.addAttribute("token", token);
        return "reset-password";
    }
    
    // 3. Use ServletAdapter
    javax.servlet.http.HttpServletRequest javaxRequest = 
        ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, 
                            "POST", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    
    try {
        Connection conn = patrolData.getConnection();
        
        // 4. Validate token
        PasswordResetToken resetToken = 
            PasswordResetToken.findByToken(conn, resort, token);
        
        if (resetToken == null || !resetToken.isValid()) {
            model.addAttribute("errorMessage", "Invalid or expired reset token");
            return "redirect:/MemberLogin?resort=" + resort;
        }
        
        // 5. Load member and update password
        String memberId = resetToken.getMemberId();
        Roster member = patrolData.getMemberByID(memberId);
        
        if (member == null) {
            model.addAttribute("errorMessage", "Member not found");
            return "redirect:/MemberLogin?resort=" + resort;
        }
        
        // 6. Update password in database
        member.updatePassword(conn, resort, newPassword);
        
        // 7. Mark token as used
        resetToken.markAsUsed(conn, resort);
        
        // 8. Redirect to login with success message
        return "redirect:/MemberLogin?resort=" + resort + 
               "&successMessage=" + 
               "Password reset successful. Please login with your new password.";
        
    } finally {
        patrolData.close();
    }
}
```

### Helper Methods

#### 5.5 handleLoginAttempt

```java
private String handleLoginAttempt(
    HttpServletRequest request, 
    HttpServletResponse response, 
    Model model) throws IOException {
    
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String id = request.getParameter("ID");
    String password = request.getParameter("Password");
    String gotoPage = request.getParameter("NSPgoto");
    
    if (StaticUtils.isEmpty(gotoPage)) {
        gotoPage = "MonthCalendar";
    }
    
    // 2. Use ServletAdapter for legacy code compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = 
        ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = 
        ServletAdapter.toJavaxResponse(response);
    
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, 
                            "POST", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    
    try {
        // 3. Clear any existing session
        sessionData.clearLoggedInResort();
        sessionData.clearLoggedInUserId();
        
        // 4. Validate credentials
        if (patrolData.isValidLogin(null, resort, id, password, sessionData)) {
            // Success - set session and redirect
            sessionData.setLoggedInUserId(id);
            sessionData.setLoggedInResort(resort);
            
            String redirectUrl = PatrolData.SERVLET_URL + gotoPage + 
                               "?resort=" + resort + "&ID=" + id;
            response.sendRedirect(redirectUrl);
            return null; // redirect already sent
            
        } else {
            // Failure - show error on login form
            model.addAttribute("errorMessage", 
                "Invalid member ID or password. Please try again.");
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", 
                PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("gotoPage", gotoPage);
            return "member-login";
        }
        
    } finally {
        patrolData.close();
    }
}
```

#### 5.6 handlePasswordResetRequest

```java
private String handlePasswordResetRequest(
    HttpServletRequest request, 
    HttpServletResponse response, 
    Model model) throws IOException {
    
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String id = request.getParameter("ID2");
    String email = request.getParameter("email");
    String gotoPage = request.getParameter("NSPgoto");
    
    // 2. Use ServletAdapter
    javax.servlet.http.HttpServletRequest javaxRequest = 
        ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, 
                            "POST", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    
    try {
        Connection conn = patrolData.getConnection();
        Roster member = null;
        
        // 3. Find member by ID or email
        if (!StaticUtils.isEmpty(id) && id.length() > 4) {
            member = patrolData.getMemberByID(id);
        } else if (!StaticUtils.isEmpty(email) && email.length() > 4) {
            member = patrolData.getMemberByEmail(email.trim());
        }
        
        // 4. Handle member not found
        if (member == null) {
            model.addAttribute("errorMessage", 
                "No member found with that ID or email address.");
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", 
                PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("gotoPage", gotoPage);
            return "member-login";
        }
        
        // 5. Validate member has email
        String memberEmail = member.getEmailAddress();
        if (memberEmail == null || !memberEmail.contains("@")) {
            model.addAttribute("errorMessage", 
                "No valid email address found for this member. " +
                "Please contact your patrol director.");
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", 
                PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("gotoPage", gotoPage);
            return "member-login";
        }
        
        // 6. Generate reset token
        PasswordResetToken resetToken = 
            PasswordResetToken.createToken(member.getID());
        resetToken.save(conn, resort);
        
        // 7. Build reset URL
        String baseUrl = request.getRequestURL().toString()
            .replace("/LoginHelp", "");
        String resetUrl = baseUrl + "/ResetPassword?resort=" + resort + 
                         "&token=" + resetToken.getToken();
        
        // 8. Send email
        String subject = "Password Reset Request for " + 
                        PatrolData.getResortFullName(resort, LOG);
        String message = buildPasswordResetEmail(member.getFullName(), 
                                                 resetUrl, 
                                                 PatrolData.getResortFullName(resort, LOG));
        
        MailMan mailMan = new MailMan(memberEmail, sessionData, LOG);
        mailMan.sendMessage(subject, message, memberEmail);
        
        LOG.info("Password reset email sent to " + member.getFullName() + 
                " at " + memberEmail);
        
        // 9. Show success message
        model.addAttribute("successMessage", 
            "An email with password reset instructions has been sent to " + 
            memberEmail);
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", 
            PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("gotoPage", gotoPage);
        return "member-login";
        
    } catch (Exception e) {
        LOG.error("Error sending password reset email", e);
        model.addAttribute("errorMessage", 
            "An error occurred while processing your request. " +
            "Please try again or contact your patrol director.");
        model.addAttribute("resort", resort);
        return "member-login";
        
    } finally {
        patrolData.close();
    }
}

private String buildPasswordResetEmail(String memberName, 
                                       String resetUrl, 
                                       String resortName) {
    return "Hello " + memberName + ",\n\n" +
           "You requested a password reset for your " + resortName + 
           " patrol account.\n\n" +
           "Click the link below to reset your password:\n" +
           resetUrl + "\n\n" +
           "This link will expire in 1 hour.\n\n" +
           "If you didn't request this reset, please ignore this email. " +
           "Your password will remain unchanged.\n\n" +
           "For assistance, contact your patrol director.\n\n" +
           "Thanks,\n" +
           "Patrol Calendar System";
}
```

---

## 6. Thymeleaf Templates

### 6.1 member-login.html

**Location:** `src/main/resources/templates/member-login.html`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Type" content="text/html; charset=windows-1252">
    <meta http-equiv="Pragma" content="no-cache">
    <meta http-equiv="Expires" content="-1">
    <title th:text="${resortFullName} + ' Ski Patrol Login'">Login</title>
    <style type="text/css">
        body {
            font-family: Verdana, Arial, Helvetica, sans-serif;
            font-size: 10pt;
            color: #000000;
            background-color: #ffffff;
        }
        h2 {
            font-size: 14pt;
            font-weight: bold;
        }
        table {
            border-collapse: collapse;
        }
        td {
            padding: 4px;
        }
        .demo-credentials {
            width: 500px;
            border: 0;
            cellspacing: 4;
            cellpadding: 0;
            margin-bottom: 10px;
        }
        .login-form {
            width: 450px;
            background-color: #ABABAB;
            border: 0;
            border-spacing: 1px;
            padding: 5px;
        }
        .login-form-inner {
            width: 75%;
            background-color: #ABABAB;
            border: 0;
            border-spacing: 1px;
            padding: 1px;
        }
        .login-form td {
            font-size: 10pt;
            font-weight: bold;
            color: white;
        }
        .reset-form {
            width: 450px;
            background-color: #EFEFEF;
            border: 0;
            border-collapse: collapse;
            margin-top: 20px;
        }
        .reset-left {
            width: 120px;
            background-color: #FFFFFF;
            text-align: left;
            font-weight: bold;
        }
        .reset-input {
            width: 153px;
            background-color: #C0F0B5;
            text-align: right;
        }
        .reset-field {
            width: 167px;
            background-color: #C0F0B5;
        }
        .help-images {
            margin-top: 20px;
        }
        .help-images img {
            margin: 10px;
            border: 0;
        }
        .error {
            color: red;
            font-weight: bold;
            margin: 10px 0;
        }
        .success {
            color: green;
            font-weight: bold;
            margin: 10px 0;
        }
    </style>
</head>
<body>
    <!-- Reuse header fragment -->
    <div th:replace="~{fragments/header :: header(${resort})}"></div>
    
    <!-- Page title -->
    <p><h2 th:text="${resortFullName} + ' Ski Patrol Login'">Ski Patrol Login</h2></p>
    
    <!-- Sample resort demo credentials -->
    <div th:if="${showSampleCredentials}">
        <p>&nbsp;</p>
        <table class="demo-credentials">
            <tr>
                <td width="75%">
                    To try <b>DEMO</b> mode, login as a Director, or a Normal Patroller<br/>
                    &nbsp;&nbsp;&nbsp;&nbsp;Director  id='<b>123456</b>' password='<b>password</b>'<br/>
                    &nbsp;&nbsp;&nbsp;&nbsp;Patroller id='<b>111111</b>' password='<b>password</b>'<br/><br/>
                    Everything is enabled, but no email notifications will be sent.
                </td>
            </tr>
        </table>
    </div>
    
    <!-- Error/Success messages -->
    <div th:if="${errorMessage}" class="error" th:text="${errorMessage}"></div>
    <div th:if="${successMessage}" class="success" th:text="${successMessage}"></div>
    
    <!-- Login form -->
    <p>&nbsp;</p>
    <form target="_self" method="post" th:action="@{/LoginHelp}">
        <input type="hidden" name="NSPgoto" th:value="${gotoPage}"/>
        <input type="hidden" name="resort" th:value="${resort}"/>
        
        <table class="login-form" align="center">
            <tr>
                <td>
                    <p>&nbsp;</p>
                    <table class="login-form-inner" align="center">
                        <tr>
                            <td><font face="verdana" size="2" color="white">Member ID Number:</font></td>
                            <td>
                                <input id="ID" name="ID" type="text"/>
                            </td>
                        </tr>
                        <tr>
                            <td><font face="verdana" size="2" color="white">Password:</font></td>
                            <td>
                                <input type="password" id="Password" name="Password"/>
                                <input type="submit" value="Login" id="submit1" name="submit1"/>
                            </td>
                        </tr>
                        <tr>
                            <td colspan="2" align="middle">
                                <font face="verdana" size="2">
                                    <a th:href="@{/LoginHelp(resort=${resort},NSPgoto=${gotoPage})}" target="_self">Login Help</a>
                                </font>
                            </td>
                        </tr>
                    </table>
                </td>
            </tr>
        </table>
    </form>
    
    <!-- Password reset section -->
    <p>&nbsp;</p>
    <form target="_self" method="post" th:action="@{/LoginHelp}">
        <input type="hidden" name="NSPgoto" th:value="${gotoPage}"/>
        <input type="hidden" name="resort" th:value="${resort}"/>
        <input type="hidden" name="emailme" value="1"/>
        
        <table class="reset-form">
            <tr>
                <td class="reset-left" rowspan="3">
                    <font face="verdana" size="2">
                        <b>If you forgot your password:</b>
                    </font>
                </td>
                <td class="reset-input">
                    <font face="verdana" size="2">Member ID:</font>
                </td>
                <td class="reset-field">
                    <input type="text" name="ID2" size="20"/>
                </td>
            </tr>
            <tr>
                <td class="reset-input">
                    <font size="2">-or-&nbsp; e-mail address:</font>
                </td>
                <td class="reset-field">
                    <input type="text" name="email" size="20"/>
                </td>
            </tr>
            <tr>
                <td colspan="2" align="center" class="reset-field">
                    <font face="verdana" size="2">
                        <input type="submit" value="Email me a password reset link" name="emailme"/>
                    </font>
                </td>
            </tr>
        </table>
    </form>
    
    <!-- Help images -->
    <div class="help-images">
        <font face="verdana" size="2">
            For help on locating your NSP Member ID Number, check the examples below of your NSP Membership Card, 
            your OEC Card and the mailing label on your issues of <i>Ski Patrol Magazine</i>
        </font>
        <table>
            <tr>
                <td align="center">
                    <img src="http://nsponline.org/images/MembershipCard.jpg" 
                         alt="NSP Membership Card" 
                         width="210" height="138"/>
                </td>
                <td align="center">
                    <img src="http://nsponline.org/images/OECCard.jpg" 
                         alt="NSP Membership Card" 
                         width="210" height="148"/>
                </td>
            </tr>
            <tr>
                <td align="center">
                    <img src="http://nsponline.org/images/label.jpg" 
                         alt="Mailing Label From SPM" 
                         width="210" height="93"/>
                </td>
            </tr>
        </table>
    </div>
    
    <!-- Reuse footer fragment -->
    <div th:replace="~{fragments/footer :: footer}"></div>
</body>
</html>
```

### 6.2 reset-password.html

**Location:** `src/main/resources/templates/reset-password.html`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Type" content="text/html; charset=windows-1252">
    <meta http-equiv="Pragma" content="no-cache">
    <meta http-equiv="Expires" content="-1">
    <title th:text="${resortFullName} + ' Password Reset'">Password Reset</title>
    <style type="text/css">
        body {
            font-family: Verdana, Arial, Helvetica, sans-serif;
            font-size: 10pt;
            color: #000000;
            background-color: #ffffff;
        }
        h2 {
            font-size: 14pt;
            font-weight: bold;
        }
        table {
            border-collapse: collapse;
            width: 450px;
            background-color: #EFEFEF;
            margin: 20px auto;
        }
        td {
            padding: 8px;
            font-size: 10pt;
        }
        .label-col {
            text-align: right;
            width: 200px;
            font-weight: bold;
        }
        .input-col {
            width: 250px;
        }
        input[type="password"] {
            width: 200px;
            padding: 4px;
        }
        input[type="submit"] {
            padding: 6px 12px;
            margin-top: 10px;
        }
        .error {
            color: red;
            font-weight: bold;
            margin: 10px 0;
            text-align: center;
        }
        .instructions {
            margin: 20px 0;
            text-align: center;
            max-width: 600px;
            margin-left: auto;
            margin-right: auto;
        }
    </style>
</head>
<body>
    <!-- Reuse header fragment -->
    <div th:replace="~{fragments/header :: header(${resort})}"></div>
    
    <!-- Page title -->
    <p><h2 th:text="${resortFullName} + ' Password Reset'">Password Reset</h2></p>
    
    <!-- Instructions -->
    <div class="instructions">
        <p>Enter your new password below. Your password will be updated immediately.</p>
    </div>
    
    <!-- Error message -->
    <div th:if="${errorMessage}" class="error" th:text="${errorMessage}"></div>
    
    <!-- Reset form -->
    <form method="post" th:action="@{/ResetPassword}">
        <input type="hidden" name="resort" th:value="${resort}"/>
        <input type="hidden" name="token" th:value="${token}"/>
        
        <table>
            <tr>
                <td class="label-col">New Password:</td>
                <td class="input-col">
                    <input type="password" name="newPassword" id="newPassword" required/>
                </td>
            </tr>
            <tr>
                <td class="label-col">Confirm Password:</td>
                <td class="input-col">
                    <input type="password" name="confirmPassword" id="confirmPassword" required/>
                </td>
            </tr>
            <tr>
                <td colspan="2" align="center">
                    <input type="submit" value="Reset Password"/>
                </td>
            </tr>
        </table>
    </form>
    
    <!-- Reuse footer fragment -->
    <div th:replace="~{fragments/footer :: footer}"></div>
</body>
</html>
```

---

## 7. Error Handling & Validation

### Controller-Level Errors

**Invalid Resort:**
```java
if (!PatrolData.isValidResort(resort)) {
    response.sendError(400, "Invalid resort: " + resort);
    return null;
}
```

**Missing Token:**
```java
if (StaticUtils.isEmpty(token)) {
    model.addAttribute("errorMessage", "Invalid or missing reset token");
    return "redirect:/MemberLogin?resort=" + resort;
}
```

**Invalid/Expired Token:**
```java
PasswordResetToken resetToken = PasswordResetToken.findByToken(conn, resort, token);
if (resetToken == null || !resetToken.isValid()) {
    model.addAttribute("errorMessage", 
        "This reset link has expired or is invalid. Please request a new password reset.");
    return "redirect:/MemberLogin?resort=" + resort;
}
```

**Password Mismatch:**
```java
if (!newPassword.equals(confirmPassword)) {
    model.addAttribute("errorMessage", "Passwords do not match");
    model.addAttribute("resort", resort);
    model.addAttribute("token", token);
    return "reset-password";
}
```

**Member Not Found (Reset Request):**
```java
if (member == null) {
    model.addAttribute("errorMessage", 
        "No member found with that ID or email address.");
    return "member-login";
}
```

**Invalid Email Address:**
```java
if (memberEmail == null || !memberEmail.contains("@")) {
    model.addAttribute("errorMessage", 
        "No valid email address found for this member. Please contact your patrol director.");
    return "member-login";
}
```

**Login Failure:**
```java
if (!patrolData.isValidLogin(null, resort, id, password, sessionData)) {
    model.addAttribute("errorMessage", 
        "Invalid member ID or password. Please try again.");
    return "member-login";
}
```

### Database Error Handling

**Resource Cleanup:**
```java
try {
    // Database operations
} finally {
    patrolData.close();
}
```

**SQL Exception Handling:**
```java
try {
    // Token operations
} catch (SQLException e) {
    LOG.error("Database error during password reset", e);
    model.addAttribute("errorMessage", 
        "An error occurred. Please try again or contact your patrol director.");
    return "member-login";
}
```

**Email Sending Errors:**
```java
try {
    mailMan.sendMessage(subject, message, memberEmail);
} catch (Exception e) {
    LOG.error("Error sending password reset email", e);
    model.addAttribute("errorMessage", 
        "Failed to send reset email. Please try again.");
    return "member-login";
}
```

---

## 8. Email Communication

### Old Email (Current - Insecure)

**Subject:** `Here is your password you requested`

**Body:**
```
[Member Name]

You have requested your login information for your resort [Resort] 
on the online scheduling web site.

Your patrol ID is: 123456
Your password is: password123

Please change this password ASAP (since it was sent in an email).

If you continue to have problems, please contact your director.

Thanks.
```

**Problems:**
- ❌ Plaintext password in email
- ❌ Password stored in email archives
- ❌ Email interception reveals password
- ❌ No expiration

### New Email (Secure)

**Subject:** `Password Reset Request for [Resort Name]`

**Body:**
```
Hello [Member Name],

You requested a password reset for your [Resort Name] patrol account.

Click the link below to reset your password:
http://localhost:8080/calendar-1/ResetPassword?resort=Sample&token=abc123def456...

This link will expire in 1 hour.

If you didn't request this reset, please ignore this email. 
Your password will remain unchanged.

For assistance, contact your patrol director.

Thanks,
Patrol Calendar System
```

**Improvements:**
- ✅ No password in email
- ✅ Secure random token
- ✅ Expires after 1 hour
- ✅ Single-use link
- ✅ Clear expiration notice
- ✅ Security note (ignore if not requested)

---

## 9. Testing Strategy

### Manual Testing Checklist

**Login Flow:**
- [ ] Valid credentials → successful login → redirect to NSPgoto destination
- [ ] Invalid ID → error message displayed
- [ ] Invalid password → error message displayed
- [ ] Empty fields → appropriate error
- [ ] Sample resort demo credentials work
- [ ] Session created after successful login
- [ ] Redirect to MonthCalendar when NSPgoto not specified

**Password Reset Request:**
- [ ] Valid member ID → email sent → success message
- [ ] Valid email address → email sent → success message
- [ ] Invalid member ID → error message
- [ ] Invalid email address → error message
- [ ] Member with no email → appropriate error
- [ ] Email delivery confirmed (check inbox)
- [ ] Email contains correct reset link
- [ ] Email mentions 1-hour expiration

**Password Reset Process:**
- [ ] Valid token → reset form displayed
- [ ] Invalid token → error redirect to login
- [ ] Expired token (>1 hour) → error redirect to login
- [ ] Used token → error redirect to login
- [ ] Matching passwords → password updated successfully
- [ ] Mismatched passwords → error on reset form
- [ ] After reset → can login with new password
- [ ] After reset → token marked as used (can't reuse)

**Edge Cases:**
- [ ] Multiple reset requests → all tokens work (until used/expired)
- [ ] Reset link clicked twice → second attempt fails (token already used)
- [ ] Long passwords handled correctly
- [ ] Special characters in passwords
- [ ] XSS attempts in form fields

### Database Testing

**Schema:**
- [ ] Table created for all resorts (Sample, etc.)
- [ ] Indexes created correctly
- [ ] Foreign key constraint (if implemented)

**Token Operations:**
- [ ] Token insertion successful
- [ ] Token retrieval by token string
- [ ] Token validation (used flag)
- [ ] Token validation (expiration)
- [ ] Token marked as used after reset
- [ ] Expired token cleanup (manual test)

### Integration Testing

**Legacy Code Integration:**
- [ ] ServletAdapter works correctly
- [ ] SessionData integration
- [ ] PatrolData.isValidLogin() works
- [ ] PatrolData.getMemberByID() works
- [ ] PatrolData.getMemberByEmail() works
- [ ] Roster.updatePassword() works
- [ ] MailMan.sendMessage() works
- [ ] Logger writes logs correctly

**Template Rendering:**
- [ ] member-login.html renders correctly
- [ ] reset-password.html renders correctly
- [ ] Header/footer fragments included
- [ ] Model attributes displayed
- [ ] Forms submit to correct endpoints
- [ ] CSS styling applied
- [ ] NSP card images display

**Session Management:**
- [ ] Session created on login
- [ ] Session persists across requests
- [ ] Redirect to protected pages works after login
- [ ] Logout clears session (if implemented)

---

## 10. Migration Steps

### Implementation Sequence

1. **Database Schema**
   - Create SQL migration script for password_reset_tokens table
   - Run for all resorts (Sample, Breckenridge, etc.)
   - Verify tables created with correct structure

2. **PasswordResetToken Store Class**
   - Implement PasswordResetToken.java
   - Add createToken(), save(), findByToken(), markAsUsed()
   - Unit test token generation and validation

3. **Roster.updatePassword() Method**
   - Add updatePassword() method to Roster.java
   - Update password in {resort}_roster table
   - Test password update

4. **MemberLoginController - Login Flow**
   - Implement showLoginForm() endpoint
   - Implement handleLoginAttempt() helper
   - Test login with valid/invalid credentials

5. **MemberLoginController - Password Reset**
   - Implement handlePasswordResetRequest()
   - Implement showResetForm()
   - Implement processPasswordReset()
   - Test entire reset flow

6. **Thymeleaf Templates**
   - Create member-login.html
   - Create reset-password.html
   - Test template rendering

7. **Update LegacyServletConfig**
   - Remove OuterMemberLogin registration
   - Remove OuterLoginHelp registration
   - Verify new controller takes over

8. **Testing**
   - Run manual testing checklist
   - Verify database operations
   - Test email delivery
   - Integration testing

9. **Documentation**
   - Update CLAUDE.md with new endpoint
   - Document password reset flow
   - Add migration notes

10. **Deployment**
    - Deploy to staging environment
    - Run smoke tests
    - Deploy to production
    - Monitor for issues

### SQL Migration Script

**File:** `docs/migrations/password_reset_tokens.sql`

```sql
-- Password Reset Tokens Migration
-- Creates password_reset_tokens table for each resort

-- Sample Resort
CREATE TABLE IF NOT EXISTS Sample_password_reset_tokens (
    token VARCHAR(64) PRIMARY KEY,
    member_id VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_member_id (member_id),
    INDEX idx_expires (expires_at)
);

-- Add similar CREATE TABLE statements for other resorts:
-- CREATE TABLE IF NOT EXISTS BreckenridgeNSP_password_reset_tokens (...);
-- CREATE TABLE IF NOT EXISTS Vail_password_reset_tokens (...);
-- etc.
```

---

## 11. Rollback Plan

### If Issues Arise After Deployment

**Immediate Rollback (< 5 minutes):**

1. **Re-enable legacy servlets in LegacyServletConfig:**
   ```java
   @Bean
   public ServletRegistrationBean<HttpServlet> memberLoginServlet() {
       return new ServletRegistrationBean<>(
           new MemberLoginServletWrapper(), "/MemberLogin");
   }
   
   @Bean
   public ServletRegistrationBean<HttpServlet> loginHelpServlet() {
       return new ServletRegistrationBean<>(
           new LoginHelpServletWrapper(), "/LoginHelp");
   }
   ```

2. **Restart application** - Legacy servlets take over immediately

3. **Database state:**
   - password_reset_tokens table remains (harmless)
   - Any tokens created still exist (can be cleaned up later)

**Partial Rollback (keep reset tokens):**

If only password reset is problematic but login works:
1. Comment out password reset endpoints in controller
2. Re-enable only LoginHelp servlet for old reset behavior
3. Keep new login flow active

**Investigation & Redeployment:**

1. Review logs for errors
2. Test in staging environment
3. Fix issues
4. Redeploy corrected version
5. Re-remove legacy servlet registrations

### Backwards Compatibility

**Database:**
- New password_reset_tokens table is isolated
- Legacy code doesn't query this table
- Can coexist indefinitely

**Code:**
- Legacy servlets still present in codebase
- Can be re-enabled via LegacyServletConfig
- No breaking changes to other components

---

## 12. Security Improvements Summary

### Before (Current Implementation)

**Vulnerabilities:**
- ❌ Plaintext passwords transmitted via email
- ❌ Passwords stored in user email archives
- ❌ Email interception reveals permanent password
- ❌ No expiration mechanism
- ❌ Password exposure to email provider

**Attack Scenarios:**
- Email account compromise → attacker gets password
- Email server breach → passwords exposed
- Man-in-the-middle → password intercepted
- Email forwarding → password shared unintentionally

### After (New Implementation)

**Security Features:**
- ✅ No passwords in email (only secure reset links)
- ✅ Cryptographically secure tokens (UUID-based)
- ✅ Short expiration window (1 hour)
- ✅ Single-use tokens (marked used after reset)
- ✅ Token validation (database-backed)
- ✅ Clear expiration notice in email
- ✅ Security note (ignore if not requested)

**Protection Against:**
- ✅ Email account compromise → attacker gets time-limited token
- ✅ Email forwarding → token expires/already used
- ✅ Token replay → marked as used after first use
- ✅ Brute force → 64-char random token space

### Future Security Enhancements (Out of Scope)

**Not Included in This Migration:**
- Rate limiting on reset requests (prevent abuse)
- Password strength validation (enforce complexity)
- Account lockout after failed login attempts
- CSRF protection (Spring Security)
- Two-factor authentication
- Password history (prevent reuse)
- Audit logging for security events
- Captcha on forms (prevent bots)

**Note:** These enhancements should be considered for future iterations after the basic migration is stable.

---

## 13. Dependencies & Constraints

### External Dependencies

**Unchanged (Reused):**
- PatrolData.java - Database connection management
- SessionData.java - Session management
- Roster.java - Member data access
- MailMan.java - Email delivery
- Logger.java - Logging
- ServletAdapter.java - Legacy compatibility

**New Dependency:**
- PasswordResetToken.java - Token management

### Database Constraints

**Multi-Tenant Pattern:**
- Each resort gets own password_reset_tokens table
- Table names follow {resort}_* pattern
- No shared token storage across resorts

**MySQL Version:**
- Requires MySQL 5.6+ (TIMESTAMP support)
- Uses BOOLEAN type (stored as TINYINT(1))
- Standard SQL syntax (portable)

### Email System Constraints

**MailMan Integration:**
- Uses existing MailMan class (no changes)
- Email delivery depends on SMTP configuration
- No email queuing (synchronous sending)
- Failures logged but don't block user flow

### Session Management Constraints

**Existing SessionData:**
- Uses existing session mechanism (cookies + database)
- No changes to session structure
- Maintains compatibility with legacy servlets
- Session creation on successful login

---

## 14. Success Criteria

### Functional Requirements

**Must Have:**
- ✅ Users can login with valid credentials
- ✅ Invalid credentials show error message
- ✅ Users can request password reset by ID or email
- ✅ Reset email sent with secure token link
- ✅ Reset link displays password reset form
- ✅ New password successfully updates member record
- ✅ Token expires after 1 hour
- ✅ Token is single-use (marked as used)
- ✅ Session created on successful login
- ✅ Redirect to NSPgoto destination after login
- ✅ Sample resort demo credentials work
- ✅ NSP card images display for ID help

**Should Have:**
- ✅ Error messages are user-friendly
- ✅ Success messages confirm actions
- ✅ Email includes clear instructions
- ✅ Forms validate input (required fields)
- ✅ Database cleanup for expired tokens (manual for now)

### Non-Functional Requirements

**Security:**
- ✅ No plaintext passwords in email
- ✅ Cryptographically secure tokens
- ✅ Token expiration enforced
- ✅ Single-use tokens enforced

**Performance:**
- ✅ Login response < 2 seconds
- ✅ Reset email sent < 5 seconds
- ✅ Database queries optimized (indexed)

**Usability:**
- ✅ UI matches existing design patterns
- ✅ Forms are intuitive
- ✅ Error messages are helpful
- ✅ Mobile-responsive (inherits from CSS)

**Maintainability:**
- ✅ Follows established ListPatrollers pattern
- ✅ Code is well-documented
- ✅ Minimal changes to legacy code
- ✅ Easy to rollback if needed

---

## 15. Open Questions & Future Work

### Resolved During Design

- ✅ Token storage mechanism → Database table
- ✅ Token expiration time → 1 hour
- ✅ Email content → Secure reset link (not password)
- ✅ Controller structure → Single controller with 4 endpoints
- ✅ Template structure → Two templates (login, reset)

### Deferred to Future Enhancements

**Security Improvements:**
- Rate limiting on password reset requests
- Password strength validation (complexity requirements)
- Account lockout after N failed login attempts
- CSRF protection (Spring Security integration)
- Two-factor authentication
- Password history tracking

**User Experience:**
- Password visibility toggle (show/hide password)
- "Remember me" checkbox (persistent login)
- Social login (OAuth providers)
- Password reset via SMS

**Operations:**
- Automated cleanup of expired tokens (cron job)
- Email delivery retry mechanism
- Monitoring/alerting for failed logins
- Audit log for security events

**Code Quality:**
- Unit tests for controller methods
- Integration tests for full flows
- Selenium tests for UI interactions
- Load testing for password reset

---

## Appendices

### Appendix A: File Locations

**New Files:**
```
src/main/java/org/nsponline/calendar/controller/MemberLoginController.java
src/main/java/org/nsponline/calendar/store/PasswordResetToken.java
src/main/resources/templates/member-login.html
src/main/resources/templates/reset-password.html
docs/migrations/password_reset_tokens.sql
docs/superpowers/specs/2026-05-26-memberlogin-springboot-migration-design.md
```

**Modified Files:**
```
src/main/java/org/nsponline/calendar/config/LegacyServletConfig.java
  (remove MemberLogin and LoginHelp servlet registrations)

src/main/java/org/nsponline/calendar/store/Roster.java
  (add updatePassword method)

CLAUDE.md
  (update migrated endpoints list)
```

**Unchanged Files:**
```
src/main/java/org/nsponline/calendar/resources/OuterMemberLogin.java (kept for rollback)
src/main/java/org/nsponline/calendar/resources/OuterLoginHelp.java (kept for rollback)
src/main/java/org/nsponline/calendar/controller/ServletAdapter.java (reused)
src/main/resources/templates/fragments/header.html (reused)
src/main/resources/templates/fragments/footer.html (reused)
```

### Appendix B: API Endpoints

**New Endpoints:**
```
GET  /MemberLogin?resort={resort}&NSPgoto={destination}
POST /LoginHelp (ID, Password, resort, NSPgoto)
POST /LoginHelp?emailme=1 (ID2 or email, resort, NSPgoto)
GET  /ResetPassword?resort={resort}&token={token}
POST /ResetPassword (token, newPassword, confirmPassword, resort)
```

**Removed Endpoints:**
```
(Legacy servlet registrations removed from LegacyServletConfig)
/MemberLogin (servlet)
/LoginHelp (servlet)
```

### Appendix C: Database Schema DDL

**Full DDL for All Resorts:**

```sql
-- Password Reset Tokens Tables
-- Run this script to create tables for all resorts

CREATE TABLE IF NOT EXISTS Sample_password_reset_tokens (
    token VARCHAR(64) PRIMARY KEY COMMENT 'UUID-based secure random token',
    member_id VARCHAR(20) NOT NULL COMMENT 'Foreign key to Sample_roster.IDNumber',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Token creation time',
    expires_at TIMESTAMP NOT NULL COMMENT 'Token expiration time (created_at + 1 hour)',
    used BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'True if token has been used for reset',
    INDEX idx_member_id (member_id),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Password reset tokens for Sample resort';

-- Repeat for each resort in PatrolData.resortMap
-- Example for other resorts:
-- CREATE TABLE IF NOT EXISTS BreckenridgeNSP_password_reset_tokens (...);
-- CREATE TABLE IF NOT EXISTS Vail_password_reset_tokens (...);
```

**Verification Query:**
```sql
-- Verify table was created
SHOW TABLES LIKE '%password_reset_tokens';

-- Check structure
DESCRIBE Sample_password_reset_tokens;

-- Test token insertion
INSERT INTO Sample_password_reset_tokens 
(token, member_id, created_at, expires_at, used) 
VALUES 
('test123', '111111', NOW(), DATE_ADD(NOW(), INTERVAL 1 HOUR), FALSE);

-- Verify insertion
SELECT * FROM Sample_password_reset_tokens WHERE token = 'test123';

-- Cleanup test data
DELETE FROM Sample_password_reset_tokens WHERE token = 'test123';
```

---

## Document Revision History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| 2026-05-26 | 1.0 | Initial design specification | Claude |

---

**End of Design Specification**
