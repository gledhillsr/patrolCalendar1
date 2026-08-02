package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

import static com.gledhills.calendar.utils.PatrolData.LoginOutcome;
import static com.gledhills.calendar.utils.PatrolData.judgeCredentials;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The login page used to accept a member's last name as their password even when the account had a
 * real password set.  Last names are printed on the member list and the calendar, so that was a
 * one-step takeover of any account.  These tests pin the narrowed rule.
 */
class JudgeCredentialsTest {

    private static final String HASH_OF_CORRECT = "aaaa1111hashofcorrectpassword";
    private static final String HASH_OF_WRONG = "bbbb2222hashofsomethingelse";

    @Test
    void lastNameIsRejectedWhenTheAccountHasAHashedPassword() {
        LoginOutcome outcome = judgeCredentials(
            "", HASH_OF_CORRECT, HASH_OF_WRONG, "Gledhill", "Gledhill");

        assertEquals(LoginOutcome.REJECTED, outcome,
                     "last name must not unlock an account that has a password");
    }

    @Test
    void lastNameIsRejectedWhenTheAccountHasAPlaintextPassword() {
        LoginOutcome outcome = judgeCredentials(
            "realPassword", "", null, "Gledhill", "Gledhill");

        assertEquals(LoginOutcome.REJECTED, outcome,
                     "last name must not unlock an account that has a password");
    }

    @Test
    void lastNameStillWorksForAnAccountWithNoPasswordAtAll() {
        //members migrated in without credentials still need a way in
        LoginOutcome outcome = judgeCredentials("", null, null, "Gledhill", "gledhill");

        assertEquals(LoginOutcome.LAST_NAME_NO_PASSWORD_SET, outcome);
    }

    @Test
    void blankLastNameCannotBeUsedAsACredential() {
        //an account with no password and no last name must not be openable with an empty password
        assertEquals(LoginOutcome.REJECTED, judgeCredentials("", "", null, "", ""));
        assertEquals(LoginOutcome.REJECTED, judgeCredentials("", "", null, "   ", "   "));
        assertEquals(LoginOutcome.REJECTED, judgeCredentials("", "", null, null, ""));
    }

    @Test
    void correctHashedPasswordIsAccepted() {
        assertEquals(LoginOutcome.HASHED_PASSWORD,
                     judgeCredentials("", HASH_OF_CORRECT, HASH_OF_CORRECT, "Gledhill", "whatever"));
    }

    @Test
    void wrongPasswordIsRejectedEvenWhenBothCredentialsExist() {
        assertEquals(LoginOutcome.REJECTED,
                     judgeCredentials("realPassword", HASH_OF_CORRECT, HASH_OF_WRONG, "Gledhill", "guess"));
    }

    @Test
    void legacyPlaintextPasswordStillWorks() {
        assertEquals(LoginOutcome.PLAINTEXT_PASSWORD,
                     judgeCredentials("realPassword", "", null, "Gledhill", "realPassword"));
    }

    @Test
    void aHashedAccountIsNotUnlockedByItsOwnPlaintextColumnBeingBlank() {
        //blank plaintext column must not be treated as "matches an empty submission"
        assertEquals(LoginOutcome.REJECTED,
                     judgeCredentials("", HASH_OF_CORRECT, HASH_OF_WRONG, "Gledhill", ""));
    }
}
