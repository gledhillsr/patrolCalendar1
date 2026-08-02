package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The login cookie used to be the bare string "resort,memberId", so anyone could type one and be
 * logged in as any member.  These tests pin the signature check that closes that hole.
 */
class AuthCookieTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static final byte[] DIFFERENT_KEY = "fedcba9876543210fedcba9876543210".getBytes();

    private static SessionData sessionDataWithKey(byte[] key) {
        SessionData sessionData = mock(SessionData.class);
        when(sessionData.readCookieSigningKey()).thenReturn(key);
        return sessionData;
    }

    private static Logger quietLogger() {
        return mock(Logger.class);
    }

    @Test
    void acceptsACookieItIssuedItself() {
        SessionData sessionData = sessionDataWithKey(KEY);
        String value = AuthCookie.create(sessionData, quietLogger(), "Sample", "123456", 3600);

        AuthCookie.Verified verified = AuthCookie.verify(sessionData, quietLogger(), value);

        assertNotNull(verified);
        assertEquals("Sample", verified.resort());
        assertEquals("123456", verified.memberId());
    }

    @Test
    void rejectsTheOldUnsignedFormat() {
        //this is exactly what an attacker used to be able to type by hand
        AuthCookie.Verified verified =
            AuthCookie.verify(sessionDataWithKey(KEY), quietLogger(), "Sample,123456");

        assertNull(verified, "unsigned legacy cookie must not authenticate anyone");
    }

    @Test
    void rejectsAHandCraftedValueWithNoValidSignature() {
        assertNull(AuthCookie.verify(sessionDataWithKey(KEY), quietLogger(),
                                     "Sample|123456|99999999999|notarealsignature"));
    }

    @Test
    void rejectsATamperedMemberId() {
        SessionData sessionData = sessionDataWithKey(KEY);
        String value = AuthCookie.create(sessionData, quietLogger(), "Sample", "123456", 3600);

        //swap in a different member, keeping the signature that was issued for 123456
        String tampered = value.replace("123456", "999999");

        assertNull(AuthCookie.verify(sessionData, quietLogger(), tampered),
                   "changing the member id must invalidate the signature");
    }

    @Test
    void rejectsACookieSignedWithAnotherKey() {
        String value = AuthCookie.create(sessionDataWithKey(KEY), quietLogger(), "Sample", "123456", 3600);

        assertNull(AuthCookie.verify(sessionDataWithKey(DIFFERENT_KEY), quietLogger(), value));
    }

    @Test
    void rejectsAnExpiredCookie() {
        SessionData sessionData = sessionDataWithKey(KEY);
        //negative lifetime puts the expiry in the past, and it is covered by the signature
        String value = AuthCookie.create(sessionData, quietLogger(), "Sample", "123456", -60);

        assertNull(AuthCookie.verify(sessionData, quietLogger(), value));
    }

    @Test
    void failsClosedWhenNoSigningKeyIsConfigured() {
        SessionData noKey = sessionDataWithKey(null);

        assertNull(AuthCookie.create(noKey, quietLogger(), "Sample", "123456", 3600),
                   "must not issue an unsigned cookie");
        assertNull(AuthCookie.verify(noKey, quietLogger(), "Sample|123456|99999999999|sig"),
                   "must not accept a cookie it cannot verify");
    }

    @Test
    void rejectsMalformedValues() {
        SessionData sessionData = sessionDataWithKey(KEY);
        Logger LOG = quietLogger();

        assertNull(AuthCookie.verify(sessionData, LOG, null));
        assertNull(AuthCookie.verify(sessionData, LOG, ""));
        assertNull(AuthCookie.verify(sessionData, LOG, "Sample|123456"));
        assertNull(AuthCookie.verify(sessionData, LOG, "Sample|123456|notanumber|sig"));
    }

    @Test
    void setCookieHeaderCarriesTheProtectiveFlags() {
        String header = ValidateCredentialsRedirectIfNeeded.buildSetCookieHeader(true, "abc", 1209600);

        assertTrue(header.startsWith("nspToken=abc"), header);
        assertTrue(header.contains("; HttpOnly"), "must be hidden from JavaScript: " + header);
        assertTrue(header.contains("; SameSite=Lax"), "must not ride along on cross site requests: " + header);
        assertTrue(header.contains("; Secure"), "must be HTTPS only when the request was: " + header);
        assertTrue(header.contains("; Path=/"), header);
        assertTrue(header.contains("; Max-Age=1209600"), header);
    }

    @Test
    void setCookieHeaderOmitsSecureOnPlainHttp() {
        //local development runs over http; a Secure cookie there would simply never be sent back
        String header = ValidateCredentialsRedirectIfNeeded.buildSetCookieHeader(false, "abc", 1209600);

        assertFalse(header.contains("Secure"), header);
        assertTrue(header.contains("; HttpOnly"), header);
    }
}
