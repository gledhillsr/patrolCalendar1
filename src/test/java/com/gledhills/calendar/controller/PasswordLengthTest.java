package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Roster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A password too long for the column is refused with a message that says so.
 *
 * <p>{@code roster.Password} was {@code varchar(16)} and nothing checked the length, so a password
 * manager's generated password reached MySQL, came back as "Data too long for column 'Password'",
 * and the member saw only "An error occurred. Please try again." Seven resorts hit it in the
 * retained logs. On the My Info page it was worse: the over-long value failed the UPDATE that
 * carries every field on the form, so the member's address and phone changes were lost with it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class PasswordLengthTest {

    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    /** What a password manager hands you: comfortably over the old 16-character column. */
    private static final String TOO_LONG = "x".repeat(Roster.MAX_PASSWORD_LENGTH + 1);

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /**
     * The point of the whole fix: the length is judged <b>before</b> the one-time token is looked
     * up, so a member who picks too long a password still has a live reset link. Getting this
     * order wrong is what made the original bug so expensive - the token was spent, and the member
     * had to request another to discover the same failure again.
     */
    @Test
    void anOverLongPasswordIsRefusedBeforeTheTokenIsSpent() throws Exception {
        String page = mockMvc.perform(post("/ResetPassword")
                                        .param("resort", "Sample")
                                        .param("token", "a-token-that-was-never-issued")
                                        .param("newPassword", TOO_LONG)
                                        .param("confirmPassword", TOO_LONG)
                                        .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("characters or fewer"),
                   "it should say the password is too long: " + page);
        assertTrue(page.contains(String.valueOf(Roster.MAX_PASSWORD_LENGTH)),
                   "and name the limit: " + page);
        assertFalse(page.contains("An error occurred"),
                    "not the old message that told the member nothing: " + page);
        assertFalse(page.contains("Invalid or expired reset token"),
                    "the token must not even have been examined yet: " + page);
    }

    /** A password that fits is not caught by the new check - it gets on with the reset. */
    @Test
    void aPasswordThatFitsGetsPastTheLengthCheck() throws Exception {
        String ok = "x".repeat(Roster.MAX_PASSWORD_LENGTH);

        String page = mockMvc.perform(post("/ResetPassword")
                                        .param("resort", "Sample")
                                        .param("token", "a-token-that-was-never-issued")
                                        .param("newPassword", ok)
                                        .param("confirmPassword", ok)
                                        .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getContentAsString();

        assertFalse(page.contains("characters or fewer"),
                    "exactly at the limit must be allowed: " + page);
    }

    /** Mismatched passwords are still caught, and the length check has not displaced that. */
    @Test
    void mismatchedPasswordsAreStillReported() throws Exception {
        String page = mockMvc.perform(post("/ResetPassword")
                                        .param("resort", "Sample")
                                        .param("token", "a-token-that-was-never-issued")
                                        .param("newPassword", "one")
                                        .param("confirmPassword", "another")
                                        .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("Passwords do not match"), page);
    }

    /**
     * The browser stops it first. Both boxes, not just the first - a maxlength on one of a pair
     * that must match is a way to make them silently differ.
     */
    @Test
    void bothResetBoxesCarryTheLimit() throws Exception {
        String page = mockMvc.perform(get("/MemberLogin")
                                        .param("resort", "Sample")
                                        .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getContentAsString();
        //the reset form lives on its own page; read it directly
        String resetPage = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/main/resources/templates/reset-password.html")));

        int withLimit = resetPage.split("maxlength=\"" + Roster.MAX_PASSWORD_LENGTH + "\"", -1).length - 1;
        assertTrue(withLimit >= 2,
                   "both the new and confirm boxes should carry maxlength, found " + withLimit);
        assertTrue(page.length() > 0, "login page still renders");
    }

    /** And the My Info password box, which fails a whole profile update when it overflows. */
    @Test
    void theMyInfoPasswordBoxCarriesTheLimit() throws Exception {
        String template = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/main/resources/templates/update-info.html")));

        int passwordInput = template.indexOf("th:field=\"*{password}\"");
        assertTrue(passwordInput > 0, "the password input should exist");
        String tag = template.substring(passwordInput, Math.min(template.length(), passwordInput + 300));
        assertTrue(tag.contains("maxlength=\"" + Roster.MAX_PASSWORD_LENGTH + "\""),
                   "it should carry the limit: " + tag);
    }
}
