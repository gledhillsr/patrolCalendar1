package com.gledhills.calendar.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a director-entered "Event Name/Comment" safe to drop into a page.
 *
 * The field deliberately accepts basic HTML tags (the DayShifts editor says so), and the month
 * calendar renders it as markup rather than text. Without this, anyone who can save a day comment
 * could store a &lt;script&gt; that runs for every member who later views the calendar.
 *
 * The approach is escape-everything-then-allow-back: the whole string is HTML escaped, then only
 * the handful of harmless formatting tags below are restored. Because a tag is only restored when
 * its name is followed immediately by "&gt;", no attribute can survive - "&lt;b onmouseover=...&gt;"
 * stays escaped and shows up as literal text.
 */
public final class EventNameSanitizer {

  /** Formatting tags a director may use in a day comment. Paired tags may also appear closing. */
  private static final Pattern ALLOWED_TAG =
    Pattern.compile("&lt;(/?)(b|i|u|em|strong|small|sub|sup)&gt;", Pattern.CASE_INSENSITIVE);

  /** Line breaks, in all the spellings people type. */
  private static final Pattern ALLOWED_BREAK =
    Pattern.compile("&lt;br\\s*/?&gt;", Pattern.CASE_INSENSITIVE);

  private EventNameSanitizer() {
  }

  /**
   * @param raw text as the director typed it, straight out of the database
   * @return HTML safe to render unescaped, keeping the allowed formatting tags
   */
  public static String sanitize(String raw) {
    if (raw == null) {
      return "";
    }

    String escaped = raw
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");

    //Roster/Assignments reads use "&nbsp;" as their empty placeholder; it escaped to "&amp;nbsp;"
    //above, so put the real entity back rather than showing the text "&nbsp;" to the member
    escaped = escaped.replace("&amp;nbsp;", "&nbsp;");

    StringBuilder result = new StringBuilder();
    Matcher tags = ALLOWED_TAG.matcher(escaped);
    while (tags.find()) {
      tags.appendReplacement(result, "<" + tags.group(1) + tags.group(2).toLowerCase() + ">");
    }
    tags.appendTail(result);

    return ALLOWED_BREAK.matcher(result.toString()).replaceAll("<br>");
  }

  /**
   * Plain text with no markup at all, for places that escape their own output (page titles,
   * spreadsheet cells, email subjects).
   */
  public static String toPlainText(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replaceAll("<[^>]*>", "").replace("&nbsp;", " ").trim();
  }
}
