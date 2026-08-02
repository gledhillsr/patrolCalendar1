package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Password and token values must never reach the log file.  These tests pin the redaction
 * applied to every statement SqlWriter logs, using the exact statement shapes the
 * Roster / PasswordResetToken builders produce.
 */
class SqlWriterRedactionTest {

    @Test
    void redactsPasswordColumnsInRosterUpdate() {
        String sql = "UPDATE roster SET LastName = \"Gledhill\" , Password = \"hunter2\" , " +
            "newPassword = \"6f1ed002ab5595859014ebf0951522d9\" , email = \"a@b.com\" WHERE IDNumber =123";

        String redacted = SqlWriter.redact(sql);

        assertFalse(redacted.contains("hunter2"), "plaintext password leaked: " + redacted);
        assertFalse(redacted.contains("6f1ed002ab5595859014ebf0951522d9"), "hash leaked: " + redacted);
        assertTrue(redacted.contains("Password = '***'"));
        assertTrue(redacted.contains("newPassword = '***'"));
        //non sensitive columns are untouched, the log is still useful
        assertTrue(redacted.contains("LastName = \"Gledhill\""));
        assertTrue(redacted.contains("email = \"a@b.com\""));
        assertTrue(redacted.contains("WHERE IDNumber =123"));
    }

    @Test
    void redactsPasswordColumnsInRosterInsertByColumnPosition() {
        String sql = "INSERT INTO roster (IDNumber, LastName, Password, email, newPassword) " +
            "VALUES ('123', 'Gledhill', 'hunter2', 'a@b.com', 'deadbeefhash' )";

        String redacted = SqlWriter.redact(sql);

        assertFalse(redacted.contains("hunter2"), "plaintext password leaked: " + redacted);
        assertFalse(redacted.contains("deadbeefhash"), "hash leaked: " + redacted);
        assertTrue(redacted.contains("'123'"));
        assertTrue(redacted.contains("'Gledhill'"));
        assertTrue(redacted.contains("'a@b.com'"));
        //column list itself is left alone, so the log still shows which columns were written
        assertTrue(redacted.contains("(IDNumber, LastName, Password, email, newPassword)"));
    }

    @Test
    void handlesEscapedQuotesAndCommasInsideValues() {
        String sql = "INSERT INTO roster (LastName, Comments, Password) " +
            "VALUES ('O''Keefe', 'likes, commas (and parens)', 'hunter2')";

        String redacted = SqlWriter.redact(sql);

        assertFalse(redacted.contains("hunter2"), "plaintext password leaked: " + redacted);
        assertTrue(redacted.contains("'O''Keefe'"), "value misaligned: " + redacted);
        assertTrue(redacted.contains("'likes, commas (and parens)'"), "value misaligned: " + redacted);
    }

    @Test
    void leavesBindPlaceholdersAlone() {
        //values bound to '?' are never logged in the first place, so there is nothing to hide
        String sql = "UPDATE roster SET Password = ? WHERE IDNumber = ?";

        assertEquals(sql, SqlWriter.redact(sql));
    }

    @Test
    void redactsTokenValues() {
        String sql = "INSERT INTO Sample_password_reset_tokens (token, member_id) VALUES ('abc123secret', '99')";

        String redacted = SqlWriter.redact(sql);

        assertFalse(redacted.contains("abc123secret"), "reset token leaked: " + redacted);
        assertTrue(redacted.contains("'99'"));
    }

    @Test
    void leavesTableNamesAndNonSensitiveStatementsUnchanged() {
        String update = "UPDATE Sample_password_reset_tokens SET used = TRUE WHERE token = ?";
        String delete = "DELETE FROM roster WHERE IDNumber = '123'";
        String insert = "INSERT INTO assignments Values('2026-01-05_0', \"09:00\", \"16:00\", \"Day\", \"1\", 4)";

        assertEquals(update, SqlWriter.redact(update));
        assertEquals(delete, SqlWriter.redact(delete));
        assertEquals(insert, SqlWriter.redact(insert));
    }

    @Test
    void suppressesTheStatementWhenAPasswordValueCannotBeDelimited() {
        //Roster's update builder does not escape embedded double quotes, so this password breaks the
        //value delimiters - redaction must give up on the whole statement rather than leak the tail
        String sql = "UPDATE roster SET LastName = \"Gledhill\" , Password = \"he\"llo\" WHERE IDNumber =123";

        String redacted = SqlWriter.redact(sql);

        assertFalse(redacted.contains("llo"), "password fragment leaked: " + redacted);
        assertFalse(redacted.contains("Gledhill"), "no values at all once suppressed: " + redacted);
        assertTrue(redacted.startsWith("UPDATE roster SET"), "leading clause is still useful: " + redacted);
        assertTrue(redacted.contains("suppressed"));
    }

    @Test
    void toleratesNullAndMalformedSql() {
        assertNull(SqlWriter.redact(null));
        //column and value counts don't line up - redaction must not guess or throw
        String mismatched = "INSERT INTO roster (LastName, Password) VALUES ('a', 'b', 'c')";
        assertEquals(mismatched, SqlWriter.redact(mismatched));
    }
}
