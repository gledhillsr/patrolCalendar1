package com.gledhills.calendar.store;

import java.sql.*;
import java.util.UUID;

import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.SqlWriter;

/**
 * Store class for password reset tokens.
 *
 * Manages secure password reset tokens with a short expiration ({@link #LIFETIME_MINUTES} minutes).
 * Tokens are UUID-based (32 hex characters) and single-use.
 *
 * @author Steve Gledhill
 */
public class PasswordResetToken {

  /**
   * How long a reset link stays usable.  Also quoted to the member in the reset email, so read it
   * from here rather than writing the number out again.
   */
  public static final int LIFETIME_MINUTES = 15;

  private static final long LIFETIME_MILLIS = LIFETIME_MINUTES * 60 * 1000L;

  private final String token;
  private final String memberId;
  private final Timestamp createdAt;
  private final Timestamp expiresAt;
  private boolean used;

  public PasswordResetToken(String token, String memberId, Timestamp createdAt,
                            Timestamp expiresAt, boolean used) {
    this.token = token;
    this.memberId = memberId;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.used = used;
  }

  // Getters
  public String getToken() {
    return token;
  }

  public String getMemberId() {
    return memberId;
  }

  public Timestamp getCreatedAt() {
    return createdAt;
  }

  public Timestamp getExpiresAt() {
    return expiresAt;
  }

  public boolean isUsed() {
    return used;
  }

  /**
   * Factory method to create a new password reset token.
   * Token expires after {@link #LIFETIME_MINUTES} minutes.
   *
   * @param memberId The member ID for whom the token is created
   * @return A new PasswordResetToken instance
   */
  public static PasswordResetToken createToken(String memberId) {
    String token = UUID.randomUUID().toString().replace("-", "");
    Timestamp now = new Timestamp(System.currentTimeMillis());
    Timestamp expires = new Timestamp(now.getTime() + LIFETIME_MILLIS);
    return new PasswordResetToken(token, memberId, now, expires, false);
  }

  /**
   * Check if this token is valid (not used and not expired).
   *
   * @return true if token is valid, false otherwise
   */
  public boolean isValid() {
    Timestamp now = new Timestamp(System.currentTimeMillis());
    return !used && expiresAt.after(now);
  }

  /**
   * Create the per-resort token table if it does not exist yet.
   * The multi-tenant table set is otherwise created manually per resort
   * (see docs/migrations/password_reset_tokens.sql), which is easy to miss
   * when adding a resort — so the code guarantees it here.
   *
   * @param LOG logger the write is audited to
   * @param connection Database connection
   * @param resort Resort identifier for table prefix
   * @throws SQLException if database operation fails
   */
  private static void ensureTableExists(Logger LOG, Connection connection, String resort) throws SQLException {
    String sql = "CREATE TABLE IF NOT EXISTS " + resort + "_password_reset_tokens (" +
      "token VARCHAR(64) PRIMARY KEY, " +
      "member_id VARCHAR(20) NOT NULL, " +
      "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
      "expires_at TIMESTAMP NOT NULL, " +
      "used BOOLEAN NOT NULL DEFAULT FALSE, " +
      "INDEX idx_member_id (member_id), " +
      "INDEX idx_expires (expires_at)" +
      ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    SqlWriter.executeUpdate(LOG, connection, "ensureTokenTableExists", sql);
  }

  /**
   * Save this token to the database.
   *
   * @param LOG logger the write is audited to
   * @param connection Database connection
   * @param resort Resort identifier for table prefix
   * @throws SQLException if database operation fails
   */
  public void save(Logger LOG, Connection connection, String resort) throws SQLException {
    ensureTableExists(LOG, connection, resort);

    String sql = "INSERT INTO " + resort + "_password_reset_tokens " +
      "(token, member_id, created_at, expires_at, used) " +
      "VALUES (?, ?, ?, ?, ?)";

    //the token itself is a bearer secret - it stays in a bind parameter so it is never logged
    SqlWriter.executeUpdate(LOG, connection, "savePasswordResetToken", sql, stmt -> {
      stmt.setString(1, token);
      stmt.setString(2, memberId);
      stmt.setTimestamp(3, createdAt);
      stmt.setTimestamp(4, expiresAt);
      stmt.setBoolean(5, used);
    });
  }

  /**
   * Find a password reset token by token string.
   *
   * @param connection Database connection
   * @param resort Resort identifier for table prefix
   * @param token Token string to search for
   * @return PasswordResetToken if found, null otherwise
   * @throws SQLException if database operation fails
   */
  public static PasswordResetToken findByToken(Logger LOG, Connection connection, String resort,
                                               String token) throws SQLException {
    ensureTableExists(LOG, connection, resort);

    String sql = "SELECT * FROM " + resort + "_password_reset_tokens WHERE token = ?";

    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setString(1, token);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          return new PasswordResetToken(
            rs.getString("token"),
            rs.getString("member_id"),
            rs.getTimestamp("created_at"),
            rs.getTimestamp("expires_at"),
            rs.getBoolean("used")
          );
        }
      }
    }
    return null;
  }

  /**
   * Mark this token as used (after successful password reset).
   *
   * @param LOG logger the write is audited to
   * @param connection Database connection
   * @param resort Resort identifier for table prefix
   * @throws SQLException if database operation fails
   */
  public void markAsUsed(Logger LOG, Connection connection, String resort) throws SQLException {
    String sql = "UPDATE " + resort + "_password_reset_tokens SET used = TRUE WHERE token = ?";

    SqlWriter.executeUpdate(LOG, connection, "markPasswordResetTokenUsed", sql, stmt -> stmt.setString(1, token));
    this.used = true;
  }

  /**
   * Delete all expired tokens for a resort.
   * This is a cleanup method that should be run periodically.
   *
   * @param LOG logger the write is audited to
   * @param connection Database connection
   * @param resort Resort identifier for table prefix
   * @throws SQLException if database operation fails
   */
  public static void deleteExpired(Logger LOG, Connection connection, String resort) throws SQLException {
    String sql = "DELETE FROM " + resort + "_password_reset_tokens WHERE expires_at < ?";

    SqlWriter.executeUpdate(LOG, connection, "deleteExpiredPasswordResetTokens", sql,
                            stmt -> stmt.setTimestamp(1, new Timestamp(System.currentTimeMillis())));
  }
}
