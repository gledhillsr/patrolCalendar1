package com.gledhills.calendar.utils;

/**
 * What a member typed into the Member ID box, resolved to the id the rest of the site runs on.
 * <p>
 * Members sign in with their patrol member number, but a great many of them try their email
 * address instead: 24 distinct people and 48 attempts in the retained logs, which is 21% of every
 * "memberId not found" failure the site has recorded.  Three of them went on to request password
 * reset after password reset - each one succeeding - because a correct reset cannot fix an
 * identifier that was never the problem.
 * <p>
 * {@link PatrolData#resolveLoginIdentifier} produces one of these so the login page can accept the
 * address when it names exactly one member, and say something useful when it does not.
 *
 * @param memberId   what to actually attempt the login with.  The resolved member number when an
 *                   address named one member, and otherwise <em>the typed value unchanged</em>, so
 *                   that an unresolvable address reaches the credential check exactly as it does
 *                   today - the back door account included
 * @param typed      what the member actually put in the box, for the message shown back to them
 * @param resolution which of the four cases this was
 */
public record LoginIdentifier(String memberId, String typed, Resolution resolution) {

  public enum Resolution {
    /** Not an email address at all - a member number, or a last name.  The ordinary path. */
    NOT_AN_EMAIL,
    /** An email address naming exactly one member, whose number is in {@link #memberId}. */
    RESOLVED,
    /** An email address on no roster row at this resort. */
    UNKNOWN_EMAIL,
    /**
     * An email address shared by more than one member.  Refused rather than guessed: 39 members
     * across 14 resorts share an address with somebody - couples, almost always - and picking
     * either of them would sign a person in as their spouse whenever the two also share a
     * password.  Those members keep using their number.
     */
    AMBIGUOUS_EMAIL
  }

  public static LoginIdentifier notAnEmail(String typed) {
    return new LoginIdentifier(typed, typed, Resolution.NOT_AN_EMAIL);
  }

  public static LoginIdentifier resolved(String typed, String memberId) {
    return new LoginIdentifier(memberId, typed, Resolution.RESOLVED);
  }

  public static LoginIdentifier unknownEmail(String typed) {
    return new LoginIdentifier(typed, typed, Resolution.UNKNOWN_EMAIL);
  }

  public static LoginIdentifier ambiguousEmail(String typed) {
    return new LoginIdentifier(typed, typed, Resolution.AMBIGUOUS_EMAIL);
  }

  /** True when the member typed something with an {@code @} in it, however it resolved. */
  public boolean looksLikeAnEmail() {
    return resolution != Resolution.NOT_AN_EMAIL;
  }
}
