package com.gledhills.calendar.store;

import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.SqlWriter;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

/**
 * The secret in a patroller's calendar subscription link.
 *
 * <p>A calendar app cannot sign in - it fetches a URL on a timer, with no session and no way to be
 * prompted for anything - so the link itself has to be the credential. That makes the token the
 * whole of the security here: <b>anyone holding it can read that patroller's shifts</b>, which is
 * why it is 24 random bytes rather than anything derived from a member number, and why
 * {@link #issue} can be called again to replace one that has escaped.
 *
 * <p>Nothing else is exposed by it. The feed carries one patroller's own shifts and no contact
 * details, so a leaked link reveals when somebody is working - which matters, but is a good deal
 * less than a session would be.
 *
 * <p>One row per patroller: re-issuing overwrites, and the old link stops working on the next
 * fetch. The table is created by the Site Wide Configuration job like every other, never on demand.
 */
public class CalendarFeedToken {

  private static final String TABLE = "calendarfeedtokens";

  /** 24 bytes, URL-safe, no padding - 32 characters that can sit in a path segment untouched. */
  private static final int TOKEN_BYTES = 24;

  private static final SecureRandom RANDOM = new SecureRandom();

  private CalendarFeedToken() {
  }

  /**
   * This patroller's current token, or null when they have never asked for one.
   *
   * <p>Null is the ordinary case for most of a roster, not an error: a subscription link is made
   * the moment somebody first wants one.
   */
  public static String forPatroller(Logger LOG, Connection connection, String patrollerId) {
    if (patrollerId == null || patrollerId.isBlank()) {
      return null;
    }
    String sql = "SELECT Token FROM " + TABLE + " WHERE PatrollerId = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, patrollerId.trim());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
    catch (SQLException e) {
      LOG.logException("could not read a calendar feed token", e);
      return null;   //a resort whose table is missing has no links, rather than a broken My Info
    }
  }

  /**
   * Give this patroller a new token, replacing any they had.
   *
   * <p>This is both "create my link" and "reset my link" - there is no difference worth making
   * between them, and having one path means the reset cannot be the one that was never tested.
   *
   * @return the new token, or null when it could not be stored - callers must not show a link they
   *         did not manage to save, since it would 404 for the rest of its life
   */
  public static String issue(Logger LOG, Connection connection, String patrollerId) {
    if (patrollerId == null || patrollerId.isBlank()) {
      return null;
    }
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

    String sql = "REPLACE INTO " + TABLE + " (PatrollerId, Token, Created) VALUES (?, ?, NOW())";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, patrollerId.trim());
      statement.setString(2, token);
      statement.executeUpdate();
      return token;
    }
    catch (SQLException e) {
      LOG.logException("could not store a calendar feed token", e);
      return null;
    }
  }

  /** Drop this patroller's link entirely, so it stops working and none replaces it. */
  public static boolean revoke(Logger LOG, Connection connection, String patrollerId) {
    if (patrollerId == null || patrollerId.isBlank()) {
      return false;
    }
    String sql = "DELETE FROM " + TABLE + " WHERE PatrollerId = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, patrollerId.trim());
      statement.executeUpdate();
      return true;
    }
    catch (SQLException e) {
      LOG.logException("could not revoke a calendar feed token", e);
      return false;
    }
  }

  /**
   * Whose feed this token opens, or null if it opens none.
   *
   * <p>The only place a token is ever turned back into a member, and the one lookup standing
   * between a URL and somebody's schedule. It is a plain equality match on an indexed column - no
   * prefix, no LIKE, nothing that could match a token other than the one presented.
   */
  public static String patrollerFor(Logger LOG, Connection connection, String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    String sql = "SELECT PatrollerId FROM " + TABLE + " WHERE Token = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, token.trim());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
    catch (SQLException e) {
      LOG.logException("could not resolve a calendar feed token", e);
      return null;
    }
  }

  /**
   * Created by the Site Wide Configuration job, like every other table here.
   *
   * <p>Returns false rather than throwing, so a resort without it degrades to "no subscription
   * links" instead of taking My Info down for everybody.
   */
  public static boolean createTable(Logger LOG, Connection connection) {
    String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
      + "PatrollerId VARCHAR(16) NOT NULL, "
      + "Token VARCHAR(64) NOT NULL, "
      + "Created DATETIME NOT NULL, "
      + "PRIMARY KEY (PatrollerId), "
      + "UNIQUE KEY Token (Token)"
      + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    try {
      SqlWriter.executeUpdate(LOG, connection, "ensureCalendarFeedTokensTable", sql);
      return true;
    }
    catch (SQLException e) {
      return false; //already logged by SqlWriter
    }
  }
}
