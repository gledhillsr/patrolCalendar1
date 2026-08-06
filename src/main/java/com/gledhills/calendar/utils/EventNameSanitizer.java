package com.gledhills.calendar.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * the harmless formatting tags below are restored. Because a formatting tag is only restored when
 * its name is followed immediately by "&gt;", no attribute can survive there -
 * "&lt;b onmouseover=...&gt;" stays escaped and shows up as literal text.
 *
 * Links are the one exception, because they need an attribute. An anchor is rebuilt only when it
 * carries a single href and nothing else, and only when that href is an ordinary web address:
 * http, https, mailto or a site-relative path. Everything else - "javascript:", "data:", an
 * entity-obfuscated scheme, an extra onclick - fails the check and is left escaped, visible as
 * text. Rebuilt links always get target="_blank" rel="noopener noreferrer", so a comment cannot
 * navigate the calendar away or reach back into it.
 */
public final class EventNameSanitizer {

  /** Formatting tags a director may use in a day comment. Paired tags may also appear closing. */
  private static final Pattern ALLOWED_TAG =
    Pattern.compile("&lt;(/?)(b|i|u|em|strong|small|sub|sup|/a)&gt;", Pattern.CASE_INSENSITIVE);

  /** Line breaks, in all the spellings people type. */
  private static final Pattern ALLOWED_BREAK =
    Pattern.compile("&lt;br\\s*/?&gt;", Pattern.CASE_INSENSITIVE);

  /**
   * An anchor whose ONLY attribute is href. A tag carrying anything else (onclick, target, style)
   * simply does not match, so it stays escaped rather than being partially trusted.
   */
  private static final Pattern ANCHOR_WITH_ONLY_HREF = Pattern.compile(
    "<a\\s+href\\s*=\\s*(?:\"([^\"<>]*)\"|'([^'<>]*)'|([^\\s\"'<>=`]+))\\s*/?>",
    Pattern.CASE_INSENSITIVE);

  /** URL schemes a day comment may link to. A site-relative "/path" is also accepted. */
  private static final String[] ALLOWED_SCHEMES = { "http://", "https://", "mailto:" };

  /** Markers standing in for a validated anchor while the rest of the text is escaped. */
  private static final char PLACEHOLDER_START = '\u0001';
  private static final char PLACEHOLDER_END = '\u0002';

  private EventNameSanitizer() {
  }

  /**
   * @param raw text as the director typed it, straight out of the database
   * @return HTML safe to render unescaped, keeping the allowed formatting tags and links
   */
  public static String sanitize(String raw) {
    if (raw == null) {
      return "";
    }

    //our own markers must not be forgeable from the stored text
    String input = raw.replace(PLACEHOLDER_START, ' ').replace(PLACEHOLDER_END, ' ');

    List<String> safeAnchors = new ArrayList<>();
    String withPlaceholders = extractSafeAnchors(input, safeAnchors);

    String escaped = withPlaceholders
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
      tags.appendReplacement(result, "<" + tags.group(1) + tags.group(2).toLowerCase(Locale.ROOT) + ">");
    }
    tags.appendTail(result);

    String withBreaks = ALLOWED_BREAK.matcher(result.toString()).replaceAll("<br>");
    return restoreAnchors(withBreaks, safeAnchors);
  }

  /**
   * Swap every acceptable anchor for a placeholder, collecting the rebuilt tag. Anchors that fail
   * the check are left in place, so the escaping step turns them into visible text.
   */
  private static String extractSafeAnchors(String input, List<String> safeAnchors) {
    Matcher anchors = ANCHOR_WITH_ONLY_HREF.matcher(input);
    StringBuilder withPlaceholders = new StringBuilder();

    while (anchors.find()) {
      String url = firstPresent(anchors.group(1), anchors.group(2), anchors.group(3));
      String replacement;

      if (isSafeUrl(url)) {
        safeAnchors.add("<a href=\"" + escapeAttribute(url.trim())
                          + "\" target=\"_blank\" rel=\"noopener noreferrer\">");
        replacement = PLACEHOLDER_START + String.valueOf(safeAnchors.size() - 1) + PLACEHOLDER_END;
      }
      else {
        replacement = anchors.group(); //left for the escaper to neutralise
      }
      anchors.appendReplacement(withPlaceholders, Matcher.quoteReplacement(replacement));
    }
    anchors.appendTail(withPlaceholders);

    return withPlaceholders.toString();
  }

  private static String restoreAnchors(String html, List<String> safeAnchors) {
    String restored = html;
    for (int index = 0; index < safeAnchors.size(); index++) {
      restored = restored.replace(PLACEHOLDER_START + String.valueOf(index) + PLACEHOLDER_END,
                                  safeAnchors.get(index));
    }
    return restored;
  }

  /**
   * An ordinary web address and nothing clever. The scheme has to be present literally, which is
   * what defeats the encoding tricks: "java&#115;cript:x" does not start with an allowed scheme,
   * so it never becomes a link, however the browser would later decode it.
   */
  private static boolean isSafeUrl(String url) {
    if (url == null) {
      return false;
    }
    String trimmed = url.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    //anything that could break out of the attribute, or hide the scheme, disqualifies the link
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c <= ' ' || c == 0x7F || c == '"' || c == '\'' || c == '<' || c == '>' || c == '`'
        || c == '\\') {
        return false;
      }
    }

    String lower = trimmed.toLowerCase(Locale.ROOT);
    for (String scheme : ALLOWED_SCHEMES) {
      if (lower.startsWith(scheme)) {
        return true;
      }
    }
    //a path on this site, but not "//host" which is a protocol-relative jump elsewhere
    return trimmed.startsWith("/") && !trimmed.startsWith("//");
  }

  private static String escapeAttribute(String value) {
    return value
      .replace("&", "&amp;")
      .replace("\"", "&quot;")
      .replace("<", "&lt;")
      .replace(">", "&gt;");
  }

  private static String firstPresent(String... values) {
    for (String value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
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
