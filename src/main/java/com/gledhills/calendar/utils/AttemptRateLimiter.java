package com.gledhills.calendar.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory throttle for abusable unauthenticated endpoints - failed logins and password reset.
 * <p>
 * Two independent counters, because the two shapes of abuse look different:
 * <ul>
 *   <li><b>per subject</b> - hammering one member: guessing one password, or filling one member's
 *       inbox with reset mail. A small budget.</li>
 *   <li><b>per source address</b> - spreading the same abuse across many members, which would never
 *       trip the per subject counter. A larger budget, since a whole patrol behind one office NAT
 *       shares an address.</li>
 * </ul>
 * Tripping either blocks for a cooling off period, after which it clears itself - nobody is locked
 * out permanently. A blank subject counts against the source address only, for flows like submitting
 * a reset token where there is no meaningful per member identity to key on.
 * <p>
 * State is per JVM and deliberately not persisted - a restart clearing the counters is acceptable and
 * it keeps this free of schema changes. Entries are evicted once their window has passed. If this app
 * ever runs on more than one instance, this needs to move to the database or a shared cache.
 */
public class AttemptRateLimiter {

  //failed logins: an online guessing attack against the fast, globally salted password hash
  static final int LOGIN_MAX_PER_SUBJECT = 5;
  static final int LOGIN_MAX_PER_ADDRESS = 20;

  //password reset: every accepted request sends real email, so the budget is tighter than for logins
  static final int RESET_MAX_PER_SUBJECT = 3;
  static final int RESET_MAX_PER_ADDRESS = 10;

  static final long WINDOW_MILLIS = 15 * 60 * 1000L;   //attempts older than this no longer count
  static final long BLOCK_MILLIS = 15 * 60 * 1000L;    //how long a tripped counter stays blocked

  /** Sweep the map every so often rather than on every single request. */
  private static final int CLEANUP_EVERY_N_RECORDS = 100;

  private final Map<String, Attempts> attemptsByKey = new ConcurrentHashMap<>();
  private final AtomicInteger recordsSinceCleanup = new AtomicInteger();
  private final Clock clock;
  private final String purpose;
  private final int maxPerSubject;
  private final int maxPerAddress;

  /** Indirection so tests can move time without sleeping. */
  @FunctionalInterface
  public interface Clock {
    long currentTimeMillis();
  }

  private static final class Attempts {
    private int count;
    private long windowStartedAt;
    private long blockedUntil;
  }

  /** Throttle for failed login attempts, keyed by member id. */
  public static AttemptRateLimiter forFailedLogins() {
    return new AttemptRateLimiter("login", LOGIN_MAX_PER_SUBJECT, LOGIN_MAX_PER_ADDRESS,
                                  System::currentTimeMillis);
  }

  /** Throttle for password reset traffic, keyed by whichever member identifier was supplied. */
  public static AttemptRateLimiter forPasswordResets() {
    return new AttemptRateLimiter("passwordReset", RESET_MAX_PER_SUBJECT, RESET_MAX_PER_ADDRESS,
                                  System::currentTimeMillis);
  }

  AttemptRateLimiter(String purpose, int maxPerSubject, int maxPerAddress, Clock clock) {
    this.purpose = purpose;
    this.maxPerSubject = maxPerSubject;
    this.maxPerAddress = maxPerAddress;
    this.clock = clock;
  }

  /**
   * Whether this attempt should be refused before doing any real work.
   *
   * @param resort        resort in play
   * @param subject       member id or email address being acted on (may be null or blank)
   * @param remoteAddress source address of the request (may be null)
   */
  public boolean isBlocked(String resort, String subject, String remoteAddress) {
    return isKeyBlocked(subjectKey(resort, subject)) || isKeyBlocked(addressKey(remoteAddress));
  }

  /**
   * Seconds remaining before this attempt would be allowed, for telling the member how long to wait.
   * Returns 0 when not blocked.
   */
  public long secondsUntilUnblocked(String resort, String subject, String remoteAddress) {
    long now = clock.currentTimeMillis();
    long until = Math.max(blockedUntil(subjectKey(resort, subject)), blockedUntil(addressKey(remoteAddress)));
    return until <= now ? 0 : ((until - now) + 999) / 1000;
  }

  /**
   * Count one attempt against both the subject and the source address.  For logins this is called only
   * on failure; for password reset it is called on every request, since an accepted request is itself
   * the thing being abused.
   */
  public void recordAttempt(String resort, String subject, String remoteAddress) {
    String subjectKey = subjectKey(resort, subject);
    if (subjectKey != null) {
      recordAttemptForKey(subjectKey, maxPerSubject);
    }
    recordAttemptForKey(addressKey(remoteAddress), maxPerAddress);
    if (recordsSinceCleanup.incrementAndGet() >= CLEANUP_EVERY_N_RECORDS) {
      recordsSinceCleanup.set(0);
      evictExpired();
    }
  }

  /**
   * Clear the subject's counter after a legitimate success.  The source address counter is deliberately
   * left alone - one success does not excuse a spray across many members from the same place.
   */
  public void recordSuccess(String resort, String subject) {
    String subjectKey = subjectKey(resort, subject);
    if (subjectKey != null) {
      attemptsByKey.remove(subjectKey);
    }
  }

  public String getPurpose() {
    return purpose;
  }

  private boolean isKeyBlocked(String key) {
    return key != null && blockedUntil(key) > clock.currentTimeMillis();
  }

  private long blockedUntil(String key) {
    if (key == null) {
      return 0;
    }
    Attempts attempts = attemptsByKey.get(key);
    if (attempts == null) {
      return 0;
    }
    synchronized (attempts) {
      return attempts.blockedUntil;
    }
  }

  private void recordAttemptForKey(String key, int maxAttempts) {
    long now = clock.currentTimeMillis();
    Attempts attempts = attemptsByKey.computeIfAbsent(key, unused -> new Attempts());
    synchronized (attempts) {
      if (now - attempts.windowStartedAt > WINDOW_MILLIS) {
        //previous window has aged out, start counting again
        attempts.count = 0;
        attempts.windowStartedAt = now;
      }
      attempts.count++;
      if (attempts.count >= maxAttempts) {
        attempts.blockedUntil = now + BLOCK_MILLIS;
        attempts.count = 0;
        attempts.windowStartedAt = now;
      }
    }
  }

  private void evictExpired() {
    long now = clock.currentTimeMillis();
    attemptsByKey.entrySet().removeIf(entry -> {
      Attempts attempts = entry.getValue();
      synchronized (attempts) {
        return attempts.blockedUntil <= now && (now - attempts.windowStartedAt) > WINDOW_MILLIS;
      }
    });
  }

  /**
   * @return the per subject key, or null when no subject was supplied - a blank subject must not put
   *         every anonymous request into one shared bucket
   */
  private static String subjectKey(String resort, String subject) {
    if (subject == null || subject.trim().isEmpty()) {
      return null;
    }
    //ids and emails are matched case insensitively elsewhere, so normalise or the counter is
    //trivially reset by changing the case of a letter
    return "subject:" + lower(resort) + ":" + lower(subject);
  }

  private static String addressKey(String remoteAddress) {
    return "address:" + lower(remoteAddress);
  }

  private static String lower(String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  /** Test seam. */
  int trackedKeyCount() {
    return attemptsByKey.size();
  }
}
