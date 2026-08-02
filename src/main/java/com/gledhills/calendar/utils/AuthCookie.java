package com.gledhills.calendar.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Format and verification for the long lived "remember me" login cookie.
 * <p>
 * The cookie used to be the bare string "resort,memberId", which anyone could type by hand to be
 * logged in as any member of any resort.  It now carries an expiry and an HMAC-SHA256 signature over
 * all three fields, so a value this server did not issue is rejected.  The signing key comes from
 * credentials.properties and never leaves the server.
 * <p>
 * Value layout:  {@code resort|memberId|expiryEpochSeconds|base64url(signature)}
 * <p>
 * Note this cookie only re-establishes a login; it is not the session itself.  The servlet session
 * (JSESSIONID) remains the primary credential.
 */
public final class AuthCookie {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final char FIELD_SEPARATOR = '|';

  /**
   * A cookie whose signature, expiry and field count all checked out.  Says nothing about whether the
   * resort or member still exist - the caller checks that against the database.
   */
  public record Verified(String resort, String memberId) {
  }

  private AuthCookie() {
  }

  /**
   * Build a signed cookie value for a member.
   *
   * @return the cookie value, or null when no signing key is configured (the caller must then skip
   *         setting a cookie rather than fall back to an unsigned one)
   */
  public static String create(SessionData sessionData, Logger LOG, String resort, String memberId,
                              int maxAgeInSeconds) {
    byte[] key = sessionData.readCookieSigningKey();
    if (key == null) {
      LOG.error("no cookie signing key configured, refusing to issue a login cookie");
      return null;
    }
    long expiry = (System.currentTimeMillis() / 1000L) + maxAgeInSeconds;
    String payload = resort + FIELD_SEPARATOR + memberId + FIELD_SEPARATOR + expiry;
    String signature = sign(key, payload, LOG);
    if (signature == null) {
      return null;
    }
    return payload + FIELD_SEPARATOR + signature;
  }

  /**
   * Verify a cookie value's signature and expiry.
   *
   * @return the resort and member it authenticates, or null if it is malformed, expired, unsigned or
   *         signed with the wrong key.  A null return must be treated as "not logged in".
   */
  public static Verified verify(SessionData sessionData, Logger LOG, String cookieValue) {
    if (cookieValue == null || cookieValue.isEmpty()) {
      return null;
    }
    byte[] key = sessionData.readCookieSigningKey();
    if (key == null) {
      LOG.error("no cookie signing key configured, rejecting login cookie");
      return null;
    }

    //split into exactly 4 fields; a memberId or resort containing a separator cannot forge extra fields
    String[] fields = cookieValue.split("\\" + FIELD_SEPARATOR, -1);
    if (fields.length != 4) {
      LOG.warn("rejecting login cookie: expected 4 fields, found " + fields.length);
      return null;
    }
    String resort = fields[0];
    String memberId = fields[1];
    String expiryField = fields[2];
    String presentedSignature = fields[3];

    String expectedSignature = sign(key, resort + FIELD_SEPARATOR + memberId + FIELD_SEPARATOR + expiryField, LOG);
    if (expectedSignature == null) {
      return null;
    }
    //constant time compare so a wrong signature leaks nothing about the right one
    if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                               presentedSignature.getBytes(StandardCharsets.UTF_8))) {
      LOG.warn("rejecting login cookie: bad signature for resort=" + resort);
      return null;
    }

    long expiry;
    try {
      expiry = Long.parseLong(expiryField);
    }
    catch (NumberFormatException e) {
      LOG.warn("rejecting login cookie: unparsable expiry");
      return null;
    }
    if (expiry < (System.currentTimeMillis() / 1000L)) {
      LOG.debug("rejecting login cookie: expired");
      return null;
    }

    return new Verified(resort, memberId);
  }

  private static String sign(byte[] key, String payload, Logger LOG) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
      return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    catch (Exception e) {
      LOG.logException("failed to sign login cookie ", e);
      return null;
    }
  }
}
