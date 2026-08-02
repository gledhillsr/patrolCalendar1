package com.gledhills.calendar.utils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single funnel for every INSERT/UPDATE/DELETE/DDL statement in the application.
 * <p>
 * All database writes go through {@link #executeUpdate}, which logs the statement <b>after</b> it has run,
 * together with the number of rows it actually affected.  Logging at execution time (rather than when the
 * SQL string is built) means a log entry always corresponds to a real, completed write.
 * <p>
 * Statements are passed through {@link #redact} before they reach the log, so password and token values are
 * never written to the log file.  Values bound to '?' placeholders are never logged at all.
 */
public final class SqlWriter {

  /**
   * Binds the '?' placeholders of a prepared statement.  Bound values are never logged.
   */
  @FunctionalInterface
  public interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private static final String REDACTED = "'***'";

  //a column is sensitive if its name contains any of these (case insensitive)
  private static final String[] SENSITIVE_COLUMN_MARKERS = { "password", "passwd", "pwd", "token", "secret" };

  //matches  <identifier containing a sensitive marker> = <literal>
  private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
    "([A-Za-z_][A-Za-z0-9_]*)(\\s*=\\s*)('(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|[^\\s,)]+)");

  private SqlWriter() {
  }

  /**
   * Execute a write statement that has no bind parameters.
   *
   * @param LOG        logger to write the audit entry to
   * @param connection open database connection
   * @param operation  short name of the calling operation, e.g. "writeAssignment"
   * @param sql        the statement to execute
   * @return number of rows affected
   */
  public static int executeUpdate(Logger LOG, Connection connection, String operation, String sql)
    throws SQLException {
    return executeUpdate(LOG, connection, operation, sql, null);
  }

  /**
   * Execute a write statement, binding its '?' placeholders via the supplied binder.
   *
   * @param LOG        logger to write the audit entry to
   * @param connection open database connection
   * @param operation  short name of the calling operation, e.g. "updatePassword"
   * @param sql        the statement to execute
   * @param binder     binds the statement's parameters, or null when there are none
   * @return number of rows affected
   */
  public static int executeUpdate(Logger LOG, Connection connection, String operation, String sql, Binder binder)
    throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      if (binder != null) {
        binder.bind(statement);
      }
      int rowsAffected = statement.executeUpdate();
      LOG.logSqlWrite(operation, redact(sql), rowsAffected);
      return rowsAffected;
    }
    catch (SQLException e) {
      LOG.logSqlWriteFailure(operation, redact(sql), e);
      throw e;
    }
  }

  /**
   * Replace the value of every password/token column in a statement with '***'.
   * Handles both  SET col = value  assignments and  INSERT INTO t (cols) VALUES (vals)  lists.
   * '?' placeholders are left alone - they carry no value.
   */
  public static String redact(String sql) {
    if (sql == null) {
      return null;
    }
    return redactAssignments(redactInsertValues(sql));
  }

  //---------------------------------------------------------------------
  //     UPDATE roster SET Password = "secret"  ->  UPDATE roster SET Password = '***'
  //---------------------------------------------------------------------
  private static String redactAssignments(String sql) {
    Matcher matcher = SENSITIVE_ASSIGNMENT.matcher(sql);
    StringBuilder result = new StringBuilder();
    boolean valueDidNotParseCleanly = false;
    while (matcher.find()) {
      String replacement = matcher.group(0);
      if (isSensitiveColumn(matcher.group(1))) {
        if (!"?".equals(matcher.group(3))) {
          replacement = matcher.group(1) + matcher.group(2) + REDACTED;
        }
        //Roster wraps update values in double quotes without escaping embedded quotes, so a password
        //containing one produces a value we cannot delimit - and part of it would survive redaction
        if (!isCleanValueBoundary(sql, matcher.end(3))) {
          valueDidNotParseCleanly = true;
        }
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    if (valueDidNotParseCleanly) {
      return suppress(sql);
    }
    return result.toString();
  }

  /**
   * True when the character after a redacted value is where a value is allowed to end.
   * Anything else means the value ran past the quote we matched, so we cannot trust the redaction.
   */
  private static boolean isCleanValueBoundary(String sql, int endOfValue) {
    int i = endOfValue;
    while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
      ++i;
    }
    if (i >= sql.length()) {
      return true;
    }
    char c = sql.charAt(i);
    if (c == ',' || c == ')' || c == ';') {
      return true;
    }
    for (String keyword : new String[] { "WHERE", "ORDER", "LIMIT", "ON", "AND", "OR" }) {
      if (sql.regionMatches(true, i, keyword, 0, keyword.length()) &&
        isWordBoundary(sql, i + keyword.length())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Last resort when a sensitive value cannot be delimited: log only the leading clause, stopping at
   * the first quote so no value of any kind is emitted.
   */
  private static String suppress(String sql) {
    String prefix = sql.substring(0, Math.min(sql.length(), 60)).replaceAll("[\"'].*", "");
    return prefix.trim() + " ... <suppressed: statement contained an undelimitable password value>";
  }

  //---------------------------------------------------------------------
  //     INSERT INTO roster (Name, Password) VALUES ('bob', 'secret')
  //       ->  INSERT INTO roster (Name, Password) VALUES ('bob', '***')
  //---------------------------------------------------------------------
  private static String redactInsertValues(String sql) {
    int valuesKeyword = indexOfKeyword(sql, "VALUES");
    if (valuesKeyword < 0) {
      return sql;
    }
    int columnsOpen = lastUnquotedIndexOf(sql, '(', valuesKeyword);
    if (columnsOpen < 0) {
      return sql; //no column list, e.g. INSERT INTO assignments Values(...) - nothing we can map by name
    }
    int columnsClose = matchingParen(sql, columnsOpen);
    if (columnsClose < 0 || columnsClose > valuesKeyword) {
      return sql;
    }
    int valuesOpen = firstUnquotedIndexOf(sql, '(', valuesKeyword);
    if (valuesOpen < 0) {
      return sql;
    }
    int valuesClose = matchingParen(sql, valuesOpen);
    if (valuesClose < 0) {
      return sql;
    }

    List<String> columns = splitTopLevel(sql.substring(columnsOpen + 1, columnsClose));
    List<String> values = splitTopLevel(sql.substring(valuesOpen + 1, valuesClose));
    if (columns.size() != values.size()) {
      return sql; //shapes don't line up, don't guess
    }

    boolean changed = false;
    for (int i = 0; i < columns.size(); ++i) {
      if (isSensitiveColumn(columns.get(i).trim()) && !"?".equals(values.get(i).trim())) {
        values.set(i, " " + REDACTED);
        changed = true;
      }
    }
    if (!changed) {
      return sql;
    }
    return sql.substring(0, valuesOpen + 1) + String.join(",", values) + sql.substring(valuesClose);
  }

  private static boolean isSensitiveColumn(String columnName) {
    String name = columnName.replace("`", "").replace("\"", "").trim().toLowerCase();
    for (String marker : SENSITIVE_COLUMN_MARKERS) {
      if (name.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  //---------------------------------------------------------------------
  //     quote aware scanning helpers - SQL literals may contain '(' ',' and doubled quotes
  //---------------------------------------------------------------------
  private static List<String> splitTopLevel(String text) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    char quote = 0;
    for (int i = 0; i < text.length(); ++i) {
      char c = text.charAt(i);
      if (quote != 0) {
        current.append(c);
        if (c == quote) {
          if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
            current.append(quote);
            ++i;
          }
          else {
            quote = 0;
          }
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
      }
      else if (c == '(') {
        ++depth;
      }
      else if (c == ')') {
        --depth;
      }
      else if (c == ',' && depth == 0) {
        parts.add(current.toString());
        current.setLength(0);
        continue;
      }
      current.append(c);
    }
    parts.add(current.toString());
    return parts;
  }

  private static int matchingParen(String text, int openIndex) {
    int depth = 0;
    char quote = 0;
    for (int i = openIndex; i < text.length(); ++i) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
            ++i;
          }
          else {
            quote = 0;
          }
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
      }
      else if (c == '(') {
        ++depth;
      }
      else if (c == ')') {
        --depth;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int indexOfKeyword(String text, String keyword) {
    char quote = 0;
    for (int i = 0; i < text.length(); ++i) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
            ++i;
          }
          else {
            quote = 0;
          }
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (text.regionMatches(true, i, keyword, 0, keyword.length()) &&
        isWordBoundary(text, i - 1) && isWordBoundary(text, i + keyword.length())) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isWordBoundary(String text, int index) {
    if (index < 0 || index >= text.length()) {
      return true;
    }
    char c = text.charAt(index);
    return !Character.isLetterOrDigit(c) && c != '_';
  }

  private static int firstUnquotedIndexOf(String text, char target, int fromIndex) {
    char quote = 0;
    for (int i = fromIndex; i < text.length(); ++i) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
            ++i;
          }
          else {
            quote = 0;
          }
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (c == target) {
        return i;
      }
    }
    return -1;
  }

  private static int lastUnquotedIndexOf(String text, char target, int beforeIndex) {
    int found = -1;
    char quote = 0;
    for (int i = 0; i < beforeIndex && i < text.length(); ++i) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
            ++i;
          }
          else {
            quote = 0;
          }
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        continue;
      }
      if (c == target) {
        found = i;
      }
    }
    return found;
  }
}
