package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

import static com.gledhills.calendar.utils.AttemptRateLimiter.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Online guessing is the practical attack against this app's fast, globally salted password hash, and
 * the reset endpoint sends real email on demand.  These tests pin how many attempts are tolerated,
 * for how long, and that legitimate members are never locked out permanently.
 */
class AttemptRateLimiterTest {

    private static final String RESORT = "Sample";
    private static final String ADDRESS = "203.0.113.7";

    /** Movable clock so the window and block expiry can be tested without sleeping. */
    private static final class TestClock implements AttemptRateLimiter.Clock {
        private long now = 1_000_000_000L;

        @Override
        public long currentTimeMillis() {
            return now;
        }

        void advanceMillis(long millis) {
            now += millis;
        }
    }

    private static AttemptRateLimiter loginLimiter(TestClock clock) {
        return new AttemptRateLimiter("login", LOGIN_MAX_PER_SUBJECT, LOGIN_MAX_PER_ADDRESS, clock);
    }

    private static AttemptRateLimiter resetLimiter(TestClock clock) {
        return new AttemptRateLimiter("passwordReset", RESET_MAX_PER_SUBJECT, RESET_MAX_PER_ADDRESS, clock);
    }

    @Test
    void blocksASubjectAfterTooManyAttempts() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        for (int i = 0; i < LOGIN_MAX_PER_SUBJECT - 1; i++) {
            limiter.recordAttempt(RESORT, "123456", ADDRESS);
            assertFalse(limiter.isBlocked(RESORT, "123456", ADDRESS),
                        "should still be allowed after " + (i + 1) + " attempts");
        }

        limiter.recordAttempt(RESORT, "123456", ADDRESS);

        assertTrue(limiter.isBlocked(RESORT, "123456", ADDRESS),
                   "should be blocked after " + LOGIN_MAX_PER_SUBJECT + " attempts");
    }

    @Test
    void blockingOneSubjectDoesNotBlockAnother() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        for (int i = 0; i < LOGIN_MAX_PER_SUBJECT; i++) {
            limiter.recordAttempt(RESORT, "123456", ADDRESS);
        }

        assertTrue(limiter.isBlocked(RESORT, "123456", ADDRESS));
        assertFalse(limiter.isBlocked(RESORT, "999999", "198.51.100.4"),
                    "a different member from a different address must be unaffected");
    }

    @Test
    void subjectIsMatchedCaseInsensitively() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        for (int i = 0; i < LOGIN_MAX_PER_SUBJECT; i++) {
            limiter.recordAttempt(RESORT, "abc123", ADDRESS);
        }

        assertTrue(limiter.isBlocked(RESORT, "ABC123", ADDRESS),
                   "changing the case of the member id must not reset the counter");
    }

    @Test
    void blocksAnAddressSprayingManyDifferentSubjects() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        //one guess each against many members never trips the per subject counter
        for (int i = 0; i < LOGIN_MAX_PER_ADDRESS; i++) {
            limiter.recordAttempt(RESORT, "member" + i, ADDRESS);
        }

        assertTrue(limiter.isBlocked(RESORT, "somebodyNew", ADDRESS),
                   "the source address must be blocked once it has sprayed enough members");
    }

    @Test
    void successClearsTheSubjectCounter() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        for (int i = 0; i < LOGIN_MAX_PER_SUBJECT - 1; i++) {
            limiter.recordAttempt(RESORT, "123456", ADDRESS);
        }
        limiter.recordSuccess(RESORT, "123456");

        //the member who finally remembered their password starts from a clean slate
        for (int i = 0; i < LOGIN_MAX_PER_SUBJECT - 1; i++) {
            limiter.recordAttempt(RESORT, "123456", ADDRESS);
            assertFalse(limiter.isBlocked(RESORT, "123456", ADDRESS));
        }
    }

    @Test
    void blockExpiresOnItsOwn() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        for (int i = 0; i < LOGIN_MAX_PER_SUBJECT; i++) {
            limiter.recordAttempt(RESORT, "123456", ADDRESS);
        }
        assertTrue(limiter.isBlocked(RESORT, "123456", ADDRESS));

        clock.advanceMillis(BLOCK_MILLIS + 1);

        assertFalse(limiter.isBlocked(RESORT, "123456", ADDRESS),
                    "a legitimate member must not be locked out permanently");
        assertEquals(0, limiter.secondsUntilUnblocked(RESORT, "123456", ADDRESS));
    }

    @Test
    void attemptsSpreadBeyondTheWindowDoNotAccumulate() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        //one typo every window, forever, must never lock the account
        for (int i = 0; i < LOGIN_MAX_PER_SUBJECT * 3; i++) {
            limiter.recordAttempt(RESORT, "123456", ADDRESS);
            clock.advanceMillis(WINDOW_MILLIS + 1);
            assertFalse(limiter.isBlocked(RESORT, "123456", ADDRESS));
        }
    }

    @Test
    void reportsHowLongToWait() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        for (int i = 0; i < LOGIN_MAX_PER_SUBJECT; i++) {
            limiter.recordAttempt(RESORT, "123456", ADDRESS);
        }

        assertEquals(BLOCK_MILLIS / 1000, limiter.secondsUntilUnblocked(RESORT, "123456", ADDRESS));
    }

    @Test
    void nullSubjectAndAddressAreHandled() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        //a form posted with no ID at all must not blow up the login page
        assertDoesNotThrow(() -> limiter.recordAttempt(RESORT, null, null));
        assertDoesNotThrow(() -> limiter.isBlocked(RESORT, null, null));
    }

    @Test
    void expiredEntriesAreEvictedSoTheMapDoesNotGrowForever() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = loginLimiter(clock);

        //enough distinct members to cross the cleanup threshold
        for (int i = 0; i < 150; i++) {
            limiter.recordAttempt(RESORT, "member" + i, "10.0.0." + (i % 250));
        }
        int beforeExpiry = limiter.trackedKeyCount();
        assertTrue(beforeExpiry > 0);

        clock.advanceMillis(WINDOW_MILLIS + BLOCK_MILLIS + 1);
        //more traffic triggers the periodic sweep
        for (int i = 0; i < 100; i++) {
            limiter.recordAttempt(RESORT, "later", "10.1.1.1");
        }

        assertTrue(limiter.trackedKeyCount() < beforeExpiry,
                   "stale entries should be evicted, was " + beforeExpiry + " now " + limiter.trackedKeyCount());
    }

    // ---- password reset specific ----

    @Test
    void resetIsThrottledTighterThanLogin() {
        assertTrue(RESET_MAX_PER_SUBJECT < LOGIN_MAX_PER_SUBJECT,
                   "each accepted reset sends real email, so it must have a smaller budget than a login guess");
        assertTrue(RESET_MAX_PER_ADDRESS < LOGIN_MAX_PER_ADDRESS);
    }

    @Test
    void stopsRepeatedResetMailToOneMember() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = resetLimiter(clock);

        for (int i = 0; i < RESET_MAX_PER_SUBJECT; i++) {
            assertFalse(limiter.isBlocked(RESORT, "member@example.com", ADDRESS));
            limiter.recordAttempt(RESORT, "member@example.com", ADDRESS);
        }

        assertTrue(limiter.isBlocked(RESORT, "member@example.com", ADDRESS),
                   "a member's inbox must not be floodable with reset mail");
    }

    @Test
    void resetSubjectIsMatchedCaseInsensitivelyForEmails() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = resetLimiter(clock);

        for (int i = 0; i < RESET_MAX_PER_SUBJECT; i++) {
            limiter.recordAttempt(RESORT, "Member@Example.com", ADDRESS);
        }

        assertTrue(limiter.isBlocked(RESORT, "member@example.com", ADDRESS),
                   "changing the case of an email must not reset the counter");
    }

    @Test
    void blankSubjectFallsBackToTheAddressCounterOnly() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = resetLimiter(clock);

        //token submissions have no member identity to key on until the token resolves
        for (int i = 0; i < RESET_MAX_PER_ADDRESS; i++) {
            limiter.recordAttempt(RESORT, null, ADDRESS);
        }

        assertTrue(limiter.isBlocked(RESORT, null, ADDRESS), "token guessing must be throttled by address");
        assertFalse(limiter.isBlocked(RESORT, null, "198.51.100.4"),
                    "and a blank subject must not put every other address into one shared bucket");
    }

    @Test
    void blankSubjectDoesNotBlockNamedSubjectsFromOtherAddresses() {
        TestClock clock = new TestClock();
        AttemptRateLimiter limiter = resetLimiter(clock);

        for (int i = 0; i < RESET_MAX_PER_ADDRESS; i++) {
            limiter.recordAttempt(RESORT, "   ", ADDRESS);
        }

        assertFalse(limiter.isBlocked(RESORT, "someone@example.com", "198.51.100.9"),
                    "whitespace-only subjects must be treated as absent, not as a shared subject key");
    }

    @Test
    void loginAndResetLimitersAreIndependent() {
        TestClock clock = new TestClock();
        AttemptRateLimiter login = loginLimiter(clock);
        AttemptRateLimiter reset = resetLimiter(clock);

        for (int i = 0; i < LOGIN_MAX_PER_SUBJECT; i++) {
            login.recordAttempt(RESORT, "123456", ADDRESS);
        }

        assertTrue(login.isBlocked(RESORT, "123456", ADDRESS));
        assertFalse(reset.isBlocked(RESORT, "123456", ADDRESS),
                    "a member locked out of login still needs the reset link to work");
    }
}
