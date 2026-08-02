package com.gledhills.calendar.store;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A reset link is a bearer credential sitting in someone's inbox, so its lifetime is a security
 * setting worth pinning rather than leaving to a magic number.
 */
class PasswordResetTokenTest {

    @Test
    void newTokenExpiresAfterTheConfiguredLifetime() {
        long before = System.currentTimeMillis();
        PasswordResetToken token = PasswordResetToken.createToken("123456");
        long after = System.currentTimeMillis();

        long expectedMillis = PasswordResetToken.LIFETIME_MINUTES * 60 * 1000L;
        long actualLifetime = token.getExpiresAt().getTime() - token.getCreatedAt().getTime();

        assertEquals(expectedMillis, actualLifetime);
        //and the expiry really is that far in the future, not just consistent with a stale createdAt
        assertTrue(token.getExpiresAt().getTime() >= before + expectedMillis);
        assertTrue(token.getExpiresAt().getTime() <= after + expectedMillis);
    }

    @Test
    void lifetimeIsFifteenMinutes() {
        assertEquals(15, PasswordResetToken.LIFETIME_MINUTES,
                     "reset links are short lived on purpose; update the email text if this changes");
    }

    @Test
    void aFreshTokenIsValid() {
        assertTrue(PasswordResetToken.createToken("123456").isValid());
    }

    @Test
    void anExpiredTokenIsNotValid() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp expiredASecondAgo = new Timestamp(System.currentTimeMillis() - 1000);
        PasswordResetToken expired =
            new PasswordResetToken("sometoken", "123456", now, expiredASecondAgo, false);

        assertFalse(expired.isValid());
    }

    @Test
    void aUsedTokenIsNotValidEvenBeforeItExpires() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp wellInTheFuture = new Timestamp(System.currentTimeMillis() + 60_000);
        PasswordResetToken used = new PasswordResetToken("sometoken", "123456", now, wellInTheFuture, true);

        assertFalse(used.isValid(), "tokens are single use");
    }
}
