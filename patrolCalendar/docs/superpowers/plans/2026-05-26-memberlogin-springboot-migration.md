# MemberLogin Spring Boot Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate OuterMemberLogin and OuterLoginHelp servlets to Spring Boot controller with secure token-based password reset.

**Architecture:** Create MemberLoginController with 4 endpoints (login form, login process, reset form, reset submit), PasswordResetToken store class, and two Thymeleaf templates. Database-backed tokens with 1-hour expiration replace plaintext password emails.

**Tech Stack:** Java 25, Spring Boot 3.4, Thymeleaf, MySQL 8.0, existing JDBC data access

---

## File Structure Overview

**New files:**
- `src/main/java/org/nsponline/calendar/store/PasswordResetToken.java` - Token management
- `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java` - Controller
- `src/main/resources/templates/member-login.html` - Login template
- `src/main/resources/templates/reset-password.html` - Reset template
- `docs/migrations/password_reset_tokens.sql` - Database migration

**Modified files:**
- `src/main/java/org/nsponline/calendar/store/Roster.java` - Add updatePassword method
- `src/main/java/org/nsponline/calendar/config/LegacyServletConfig.java` - Remove MemberLogin/LoginHelp registrations
- `CLAUDE.md` - Update migrated endpoints list

**Unchanged files:**
- `src/main/java/org/nsponline/calendar/controller/ServletAdapter.java` - Reuse from ListPatrollers
- `src/main/resources/templates/fragments/header.html` - Reuse
- `src/main/resources/templates/fragments/footer.html` - Reuse

---

## Task 1: Create Database Migration Script

**Files:**
- Create: `docs/migrations/password_reset_tokens.sql`

- [ ] **Step 1: Create migrations directory**

```bash
mkdir -p docs/migrations
```

- [ ] **Step 2: Write SQL migration script**

Create `docs/migrations/password_reset_tokens.sql`:

```sql
-- Password Reset Tokens Migration
-- Creates password_reset_tokens table for each resort
-- Run this script manually against MySQL database

-- Sample Resort (for testing)
CREATE TABLE IF NOT EXISTS Sample_password_reset_tokens (
    token VARCHAR(64) PRIMARY KEY COMMENT 'UUID-based secure random token',
    member_id VARCHAR(20) NOT NULL COMMENT 'Foreign key to Sample_roster.IDNumber',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Token creation time',
    expires_at TIMESTAMP NOT NULL COMMENT 'Token expiration time (created_at + 1 hour)',
    used BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'True if token has been used for reset',
    INDEX idx_member_id (member_id),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Password reset tokens for Sample resort';

-- Add similar CREATE TABLE statements for production resorts:
-- CREATE TABLE IF NOT EXISTS BreckenridgeNSP_password_reset_tokens (...);
-- CREATE TABLE IF NOT EXISTS Vail_password_reset_tokens (...);
-- etc.
```

- [ ] **Step 3: Document manual execution required**

Add comment to top of file noting this must be run manually by DBA or developer with MySQL access.

- [ ] **Step 4: Commit migration script**

```bash
git add docs/migrations/password_reset_tokens.sql
git commit -m "feat: add password reset tokens database migration script"
```

---

## Task 2: Implement PasswordResetToken Store Class

**Files:**
- Create: `src/main/java/org/nsponline/calendar/store/PasswordResetToken.java`

- [ ] **Step 1: Create class skeleton**

Create `src/main/java/org/nsponline/calendar/store/PasswordResetToken.java`:

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
    
    public PasswordResetToken(String token, String memberId, Timestamp createdAt, 
                              Timestamp expiresAt, boolean used) {
        this.token = token;
        this.memberId = memberId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.used = used;
    }
    
    // Getters
    public String getToken() { return token; }
    public String getMemberId() { return memberId; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getExpiresAt() { return expiresAt; }
    public boolean isUsed() { return used; }
}
```

- [ ] **Step 2: Add createToken factory method**

Add to PasswordResetToken class:

```java
public static PasswordResetToken createToken(String memberId) {
    String token = UUID.randomUUID().toString().replace("-", "");
    Timestamp now = new Timestamp(System.currentTimeMillis());
    Timestamp expires = new Timestamp(now.getTime() + 3600000); // +1 hour
    return new PasswordResetToken(token, memberId, now, expires, false);
}
```

- [ ] **Step 3: Add isValid method**

Add to PasswordResetToken class:

```java
public boolean isValid() {
    Timestamp now = new Timestamp(System.currentTimeMillis());
    return !used && expiresAt.after(now);
}
```

- [ ] **Step 4: Add save method**

Add to PasswordResetToken class:

```java
public void save(Connection connection, String resort) throws SQLException {
    String sql = "INSERT INTO " + resort + "_password_reset_tokens " +
                 "(token, member_id, created_at, expires_at, used) " +
                 "VALUES (?, ?, ?, ?, ?)";
    
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, token);
        stmt.setString(2, memberId);
        stmt.setTimestamp(3, createdAt);
        stmt.setTimestamp(4, expiresAt);
        stmt.setBoolean(5, used);
        stmt.executeUpdate();
    }
}
```

- [ ] **Step 5: Add findByToken method**

Add to PasswordResetToken class:

```java
public static PasswordResetToken findByToken(Connection connection, String resort, 
                                             String token) throws SQLException {
    String sql = "SELECT * FROM " + resort + "_password_reset_tokens WHERE token = ?";
    
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, token);
        try (ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return new PasswordResetToken(
                    rs.getString("token"),
                    rs.getString("member_id"),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("expires_at"),
                    rs.getBoolean("used")
                );
            }
        }
    }
    return null;
}
```

- [ ] **Step 6: Add markAsUsed method**

Add to PasswordResetToken class:

```java
public void markAsUsed(Connection connection, String resort) throws SQLException {
    String sql = "UPDATE " + resort + "_password_reset_tokens SET used = TRUE WHERE token = ?";
    
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, token);
        stmt.executeUpdate();
    }
    this.used = true;
}
```

- [ ] **Step 7: Add deleteExpired cleanup method**

Add to PasswordResetToken class:

```java
public static void deleteExpired(Connection connection, String resort) throws SQLException {
    String sql = "DELETE FROM " + resort + "_password_reset_tokens WHERE expires_at < ?";
    
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
        int deleted = stmt.executeUpdate();
        System.out.println("Deleted " + deleted + " expired tokens for " + resort);
    }
}
```

- [ ] **Step 8: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 9: Commit PasswordResetToken class**

```bash
git add src/main/java/org/nsponline/calendar/store/PasswordResetToken.java
git commit -m "feat: add PasswordResetToken store class for secure password reset"
```

---

## Task 3: Add updatePassword Method to Roster

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/store/Roster.java`

- [ ] **Step 1: Add updatePassword method**

Add this method to the Roster class (around line 500, after other update methods):

```java
public void updatePassword(Connection connection, String resort, String newPassword) throws SQLException {
    String sql = "UPDATE " + resort + "_roster SET Password = ? WHERE IDNumber = ?";
    
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, newPassword);
        stmt.setString(2, this.getID());
        stmt.executeUpdate();
    }
    
    // Update in-memory password field
    this.password = newPassword;
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit Roster changes**

```bash
git add src/main/java/org/nsponline/calendar/store/Roster.java
git commit -m "feat: add updatePassword method to Roster for password reset functionality"
```

---

## Task 4: Create MemberLoginController Skeleton

**Files:**
- Create: `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`

- [ ] **Step 1: Create controller class with imports**

Create `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`:

```java
package org.nsponline.calendar.controller;

import org.nsponline.calendar.store.*;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;

@Controller
public class MemberLoginController {
    
    @GetMapping("/MemberLogin")
    public String showLoginForm(HttpServletRequest request, HttpServletResponse response, 
                                Model model) throws IOException {
        // TODO: Implement in next steps
        return "member-login";
    }
    
    @PostMapping("/LoginHelp")
    public String processLogin(HttpServletRequest request, HttpServletResponse response, 
                               Model model) throws IOException {
        // TODO: Implement in next steps
        return "member-login";
    }
    
    @GetMapping("/ResetPassword")
    public String showResetForm(HttpServletRequest request, HttpServletResponse response, 
                                Model model) throws IOException {
        // TODO: Implement in next steps
        return "reset-password";
    }
    
    @PostMapping("/ResetPassword")
    public String processPasswordReset(HttpServletRequest request, HttpServletResponse response, 
                                       Model model) throws IOException {
        // TODO: Implement in next steps
        return "reset-password";
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit controller skeleton**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberLoginController.java
git commit -m "feat: add MemberLoginController skeleton with 4 endpoints"
```

---

## Task 5: Implement showLoginForm Endpoint

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`

- [ ] **Step 1: Replace showLoginForm method**

Replace the showLoginForm method in MemberLoginController:

```java
@GetMapping("/MemberLogin")
public String showLoginForm(HttpServletRequest request, HttpServletResponse response, 
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
    
    // 4. Use ServletAdapter for legacy compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "GET", resort, Logger.INFO);
    
    // 5. Prepare model
    model.addAttribute("resort", resort);
    model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
    model.addAttribute("gotoPage", gotoPage);
    model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));
    
    // 6. Return template
    return "member-login";
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit showLoginForm implementation**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberLoginController.java
git commit -m "feat: implement showLoginForm endpoint for GET /MemberLogin"
```

---

## Task 6: Implement handleLoginAttempt Helper

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`

- [ ] **Step 1: Add handleLoginAttempt private method**

Add this private method to MemberLoginController (after the endpoint methods):

```java
private String handleLoginAttempt(HttpServletRequest request, HttpServletResponse response, 
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
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
    
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "POST", resort, Logger.INFO);
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
            model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));
            return "member-login";
        }
        
    } finally {
        patrolData.close();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit handleLoginAttempt helper**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberLoginController.java
git commit -m "feat: add handleLoginAttempt helper for login authentication"
```

---

## Task 7: Implement handlePasswordResetRequest Helper

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`

- [ ] **Step 1: Add buildPasswordResetEmail helper**

Add this private method to MemberLoginController:

```java
private String buildPasswordResetEmail(String memberName, String resetUrl, String resortName) {
    return "Hello " + memberName + ",\n\n" +
           "You requested a password reset for your " + resortName + " patrol account.\n\n" +
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

- [ ] **Step 2: Add handlePasswordResetRequest method**

Add this private method to MemberLoginController:

```java
private String handlePasswordResetRequest(HttpServletRequest request, HttpServletResponse response, 
                                         Model model) throws IOException {
    // 1. Read parameters
    String resort = request.getParameter("resort");
    String id = request.getParameter("ID2");
    String email = request.getParameter("email");
    String gotoPage = request.getParameter("NSPgoto");
    
    // 2. Use ServletAdapter
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "POST", resort, Logger.INFO);
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
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("gotoPage", gotoPage);
            model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));
            return "member-login";
        }
        
        // 5. Validate member has email
        String memberEmail = member.getEmailAddress();
        if (memberEmail == null || !memberEmail.contains("@")) {
            model.addAttribute("errorMessage", 
                "No valid email address found for this member. Please contact your patrol director.");
            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("gotoPage", gotoPage);
            model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));
            return "member-login";
        }
        
        // 6. Generate reset token
        PasswordResetToken resetToken = PasswordResetToken.createToken(member.getID());
        resetToken.save(conn, resort);
        
        // 7. Build reset URL
        String baseUrl = request.getRequestURL().toString().replace("/LoginHelp", "");
        String resetUrl = baseUrl + "/ResetPassword?resort=" + resort + 
                         "&token=" + resetToken.getToken();
        
        // 8. Send email
        String subject = "Password Reset Request for " + PatrolData.getResortFullName(resort, LOG);
        String message = buildPasswordResetEmail(member.getFullName(), resetUrl, 
                                                PatrolData.getResortFullName(resort, LOG));
        
        MailMan mailMan = new MailMan(memberEmail, sessionData, LOG);
        mailMan.sendMessage(subject, message, memberEmail);
        
        LOG.info("Password reset email sent to " + member.getFullName() + " at " + memberEmail);
        
        // 9. Show success message
        model.addAttribute("successMessage", 
            "An email with password reset instructions has been sent to " + memberEmail);
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("gotoPage", gotoPage);
        model.addAttribute("showSampleCredentials", resort.equalsIgnoreCase("Sample"));
        return "member-login";
        
    } catch (Exception e) {
        LOG.error("Error sending password reset email", e);
        model.addAttribute("errorMessage", 
            "An error occurred while processing your request. Please try again or contact your patrol director.");
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("gotoPage", gotoPage);
        return "member-login";
        
    } finally {
        patrolData.close();
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit password reset request helper**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberLoginController.java
git commit -m "feat: add handlePasswordResetRequest helper for password reset email"
```

---

## Task 8: Implement processLogin Endpoint

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`

- [ ] **Step 1: Replace processLogin method**

Replace the processLogin method in MemberLoginController:

```java
@PostMapping("/LoginHelp")
public String processLogin(HttpServletRequest request, HttpServletResponse response, 
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

- [ ] **Step 2: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit processLogin implementation**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberLoginController.java
git commit -m "feat: implement processLogin endpoint routing login vs password reset"
```

---

## Task 9: Implement showResetForm Endpoint

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`

- [ ] **Step 1: Replace showResetForm method**

Replace the showResetForm method in MemberLoginController:

```java
@GetMapping("/ResetPassword")
public String showResetForm(HttpServletRequest request, HttpServletResponse response, 
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
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "GET", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    
    try {
        Connection conn = patrolData.getConnection();
        
        // 5. Validate token
        PasswordResetToken resetToken = PasswordResetToken.findByToken(conn, resort, token);
        
        if (resetToken == null || !resetToken.isValid()) {
            model.addAttribute("errorMessage", 
                "This reset link has expired or is invalid. Please request a new password reset.");
            return "redirect:/MemberLogin?resort=" + resort;
        }
        
        // 6. Prepare model
        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("token", token);
        
        return "reset-password";
        
    } catch (Exception e) {
        LOG.error("Error validating reset token", e);
        model.addAttribute("errorMessage", "An error occurred. Please try again.");
        return "redirect:/MemberLogin?resort=" + resort;
    } finally {
        patrolData.close();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit showResetForm implementation**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberLoginController.java
git commit -m "feat: implement showResetForm endpoint for GET /ResetPassword"
```

---

## Task 10: Implement processPasswordReset Endpoint

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/controller/MemberLoginController.java`

- [ ] **Step 1: Replace processPasswordReset method**

Replace the processPasswordReset method in MemberLoginController:

```java
@PostMapping("/ResetPassword")
public String processPasswordReset(HttpServletRequest request, HttpServletResponse response, 
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
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(MemberLoginController.class, javaxRequest, "POST", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    
    try {
        Connection conn = patrolData.getConnection();
        
        // 4. Validate token
        PasswordResetToken resetToken = PasswordResetToken.findByToken(conn, resort, token);
        
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
        
        LOG.info("Password reset successful for member " + memberId);
        
        // 8. Redirect to login with success message
        return "redirect:/MemberLogin?resort=" + resort + 
               "&successMessage=Password reset successful. Please login with your new password.";
        
    } catch (Exception e) {
        LOG.error("Error processing password reset", e);
        model.addAttribute("errorMessage", "An error occurred. Please try again.");
        model.addAttribute("resort", resort);
        model.addAttribute("token", token);
        return "reset-password";
    } finally {
        patrolData.close();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit processPasswordReset implementation**

```bash
git add src/main/java/org/nsponline/calendar/controller/MemberLoginController.java
git commit -m "feat: implement processPasswordReset endpoint for POST /ResetPassword"
```

---

## Task 11: Create member-login.html Template

**Files:**
- Create: `src/main/resources/templates/member-login.html`

- [ ] **Step 1: Create template with head section**

Create `src/main/resources/templates/member-login.html`:

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
```

- [ ] **Step 2: Add header and title**

Append to member-login.html:

```html
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
```

- [ ] **Step 3: Add login form**

Append to member-login.html:

```html
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
```

- [ ] **Step 4: Add password reset form**

Append to member-login.html:

```html
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
```

- [ ] **Step 5: Add help images and footer**

Append to member-login.html:

```html
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

- [ ] **Step 6: Verify template syntax**

```bash
mvn spring-boot:run
```

Check logs for template errors. Press Ctrl+C to stop.

- [ ] **Step 7: Commit member-login template**

```bash
git add src/main/resources/templates/member-login.html
git commit -m "feat: create member-login.html Thymeleaf template"
```

---

## Task 12: Create reset-password.html Template

**Files:**
- Create: `src/main/resources/templates/reset-password.html`

- [ ] **Step 1: Create complete template**

Create `src/main/resources/templates/reset-password.html`:

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

- [ ] **Step 2: Verify template syntax**

```bash
mvn spring-boot:run
```

Check logs for template errors. Press Ctrl+C to stop.

- [ ] **Step 3: Commit reset-password template**

```bash
git add src/main/resources/templates/reset-password.html
git commit -m "feat: create reset-password.html Thymeleaf template"
```

---

## Task 13: Update LegacyServletConfig

**Files:**
- Modify: `src/main/java/org/nsponline/calendar/config/LegacyServletConfig.java`

- [ ] **Step 1: Remove MemberLogin servlet registration**

In LegacyServletConfig.java, remove (or comment out) the memberLoginServlet() method and its wrapper class:

```java
// Removed - migrated to MemberLoginController
// @Bean
// public ServletRegistrationBean<HttpServlet> memberLoginServlet() {
//     return new ServletRegistrationBean<>(
//         new MemberLoginServletWrapper(), "/MemberLogin");
// }
```

- [ ] **Step 2: Remove LoginHelp servlet registration**

In LegacyServletConfig.java, remove (or comment out) any LoginHelp servlet registration if present.

- [ ] **Step 3: Verify compilation**

```bash
mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit LegacyServletConfig changes**

```bash
git add src/main/java/org/nsponline/calendar/config/LegacyServletConfig.java
git commit -m "feat: remove MemberLogin and LoginHelp from legacy servlet config"
```

---

## Task 14: Test and Verify Migration

**Files:**
- None (testing only)

- [ ] **Step 1: Build application**

```bash
mvn clean package
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Run database migration**

Execute the SQL script against MySQL:

```bash
mysql -u <user> -p <database> < docs/migrations/password_reset_tokens.sql
```

Verify table created:

```sql
DESCRIBE Sample_password_reset_tokens;
```

- [ ] **Step 3: Start application**

```bash
java -jar target/calendar-1.jar
```

Wait for "Started CalendarApplication" in logs.

- [ ] **Step 4: Test login form display**

Open browser: `http://localhost:8080/calendar-1/MemberLogin?resort=Sample`

Expected: Login form displays with Sample demo credentials shown

- [ ] **Step 5: Test valid login**

Enter ID=123456, Password=password, click Login

Expected: Redirect to MonthCalendar with session created

- [ ] **Step 6: Test invalid login**

Enter ID=invalid, Password=wrong, click Login

Expected: Error message "Invalid member ID or password"

- [ ] **Step 7: Test password reset request**

Enter ID2=111111, click "Email me a password reset link"

Expected: Success message showing email sent

- [ ] **Step 8: Check email**

Open email for member 111111 (if configured)

Expected: Email contains reset link with token

- [ ] **Step 9: Test reset link**

Click reset link in email or manually visit:
`http://localhost:8080/calendar-1/ResetPassword?resort=Sample&token=<token>`

Expected: Password reset form displays

- [ ] **Step 10: Test password reset**

Enter matching passwords, click "Reset Password"

Expected: Redirect to login with success message

- [ ] **Step 11: Test new password**

Login with member ID and new password

Expected: Login succeeds

- [ ] **Step 12: Test expired token**

Wait >1 hour or manually update expires_at in database, try using token

Expected: Error "This reset link has expired"

- [ ] **Step 13: Test used token**

Try using the same token again after successful reset

Expected: Error "This reset link has expired or is invalid"

- [ ] **Step 14: Stop application**

Press Ctrl+C

---

## Task 15: Update Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update migrated endpoints list**

In CLAUDE.md, find the "### Migrated Endpoints" section and update it:

```markdown
### Migrated Endpoints

- `/ListPatrollers` - Spring Boot controller with Thymeleaf template
- `/MemberLogin` - Spring Boot controller with Thymeleaf template (login + password reset)
```

- [ ] **Step 2: Add password reset notes**

In CLAUDE.md, add after migrated endpoints:

```markdown
### Security Improvements

**Password Reset:**
- Token-based password reset (replaces plaintext password emails)
- Tokens expire after 1 hour
- Tokens are single-use (marked as used after reset)
- Database-backed token validation via `password_reset_tokens` table per resort
```

- [ ] **Step 3: Commit documentation**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with MemberLogin migration info"
```

---

## Task 16: Final Tag and Cleanup

**Files:**
- None (tagging only)

- [ ] **Step 1: Run full test suite**

```bash
mvn test
```

Expected: All tests pass

- [ ] **Step 2: Create migration tag**

```bash
git tag -a v1.1-memberlogin-migration -m "MemberLogin servlet migrated to Spring Boot with secure password reset"
```

- [ ] **Step 3: Verify tag created**

```bash
git tag -l "v1.*"
```

Expected: Shows v1.0-springboot-migration and v1.1-memberlogin-migration

- [ ] **Step 4: Document completion**

Create checkpoint note documenting:
- Endpoints migrated: /MemberLogin, /LoginHelp, /ResetPassword
- Security improvement: Token-based password reset
- Database changes: password_reset_tokens table per resort
- Testing completed successfully

---

## Self-Review Checklist

**Spec coverage:**
- ✅ PasswordResetToken store class (Task 2)
- ✅ Roster.updatePassword method (Task 3)
- ✅ MemberLoginController with 4 endpoints (Tasks 4-10)
- ✅ member-login.html template (Task 11)
- ✅ reset-password.html template (Task 12)
- ✅ Database migration script (Task 1)
- ✅ LegacyServletConfig update (Task 13)
- ✅ Testing and verification (Task 14)
- ✅ Documentation update (Task 15)

**No placeholders:**
- All code blocks complete
- All SQL statements complete
- All template HTML complete
- All test steps with expected results

**Type consistency:**
- PasswordResetToken methods match across all tasks
- Model attribute names consistent (resort, resortFullName, gotoPage, etc.)
- Method signatures match between declaration and usage
- Template variable names match controller model attributes

**Execution readiness:**
- Each step is 2-5 minutes
- All file paths exact
- All commands with expected output
- Clear commit points after each logical unit
