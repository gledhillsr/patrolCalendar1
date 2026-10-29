package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.PasswordResetToken;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The password reset email tells the member the wall-clock time their link dies, in their own
 * resort's time zone - "This link will expire in 15 minutes. (at 4:19pm MST)".
 *
 * <p>"In 15 minutes" is true when the mail is composed and decreasingly true afterwards; a member
 * reading it twenty minutes later has no way to know the link is already dead. A clock time stays
 * true.
 *
 * <p>The zone comes from {@code directorsettings.timeZone} and from nowhere else. A resort that
 * has not set one gets the plain sentence, because the alternative - {@code ResortTimeZones
 * .inForce} - falls back to Mountain, and a confident wrong time three zones out is worse than no
 * time at all.
 */
class PasswordResetExpiryTimeTest {

    private static final String PLAIN = "This link will expire in 15 minutes.";

    /** 2026-01-15 23:19:30 UTC - 4:19pm in Denver, where January is MST. */
    private static PasswordResetToken tokenExpiringAt(String instant) {
        return new PasswordResetToken("tok", "123456", null,
                                      Timestamp.from(Instant.parse(instant)), false);
    }

    @Test
    void theExpiryTimeIsShownInTheResortsZone() {
        String wording = MemberLoginController.expiryWording(
            tokenExpiringAt("2026-01-15T23:19:30Z"), ZoneId.of("America/Denver"));

        assertEquals(PLAIN + " (at 4:19pm MST)", wording);
    }

    /** The same instant, read in three other resorts' zones. */
    @Test
    void eachZoneGetsItsOwnClockTime() {
        PasswordResetToken token = tokenExpiringAt("2026-01-15T23:19:30Z");

        assertEquals(PLAIN + " (at 6:19pm EST)",
                     MemberLoginController.expiryWording(token, ZoneId.of("America/New_York")));
        assertEquals(PLAIN + " (at 5:19pm CST)",
                     MemberLoginController.expiryWording(token, ZoneId.of("America/Chicago")));
        assertEquals(PLAIN + " (at 3:19pm PST)",
                     MemberLoginController.expiryWording(token, ZoneId.of("America/Los_Angeles")));
    }

    /**
     * Daylight saving is the zone's business, not ours. July in Denver is MDT, and the same
     * UTC instant is an hour later on the wall than it was in January.
     */
    @Test
    void daylightSavingIsReflectedInBothTheTimeAndTheAbbreviation() {
        String wording = MemberLoginController.expiryWording(
            tokenExpiringAt("2026-07-15T23:19:30Z"), ZoneId.of("America/Denver"));

        assertEquals(PLAIN + " (at 5:19pm MDT)", wording);
    }

    /** Arizona keeps the same clock all year, which is exactly why it is in the choices list. */
    @Test
    void arizonaDoesNotShift() {
        assertEquals(PLAIN + " (at 4:19pm MST)",
                     MemberLoginController.expiryWording(tokenExpiringAt("2026-01-15T23:19:30Z"),
                                                         ZoneId.of("America/Phoenix")));
        assertEquals(PLAIN + " (at 4:19pm MST)",
                     MemberLoginController.expiryWording(tokenExpiringAt("2026-07-15T23:19:30Z"),
                                                         ZoneId.of("America/Phoenix")));
    }

    /** Midnight and noon are where a 12-hour clock goes wrong. */
    @Test
    void middayAndMidnightReadTheRightWayRound() {
        assertTrue(MemberLoginController.expiryWording(
                     tokenExpiringAt("2026-01-15T19:05:00Z"), ZoneId.of("America/Denver"))
                     .contains("12:05pm"), "19:05 UTC is 12:05 noon in Denver");

        assertTrue(MemberLoginController.expiryWording(
                     tokenExpiringAt("2026-01-15T07:05:00Z"), ZoneId.of("America/Denver"))
                     .contains("12:05am"), "07:05 UTC is 12:05 midnight in Denver");
    }

    // ------------------------------------------------- the fallback

    /** A resort with no zone set is told the duration and no clock time. */
    @Test
    void noZoneMeansTheOriginalSentence() {
        String wording =
            MemberLoginController.expiryWording(tokenExpiringAt("2026-01-15T23:19:30Z"), null);

        assertEquals(PLAIN, wording);
        assertFalse(wording.contains("("), "no empty bracket should be left behind: " + wording);
    }

    /** And so is a token with no expiry on it, rather than a crash on the way to the member. */
    @Test
    void aTokenWithNoExpiryStillSendsTheEmail() {
        PasswordResetToken noExpiry = new PasswordResetToken("tok", "123456", null, null, false);

        assertEquals(PLAIN, MemberLoginController.expiryWording(noExpiry, ZoneId.of("America/Denver")));
        assertEquals(PLAIN, MemberLoginController.expiryWording(null, ZoneId.of("America/Denver")));
    }

    /**
     * The time shown is the token's own expiry, not fifteen minutes from whenever the email was
     * composed - so a mail delayed in a queue still names the moment the server will enforce.
     */
    @Test
    void theTimeComesFromTheTokenNotFromTheClock() {
        // an expiry well in the past: a "now + 15 minutes" implementation could not produce it
        String wording = MemberLoginController.expiryWording(
            tokenExpiringAt("2020-02-29T20:45:00Z"), ZoneId.of("America/Denver"));

        assertEquals(PLAIN + " (at 1:45pm MST)", wording);
    }
}
