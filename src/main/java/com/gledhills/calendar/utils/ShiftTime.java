package com.gledhills.calendar.utils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What counts as a start or end time on a shift, and what it looks like once stored.
 *
 * <p>The Start field was free text for twenty years, so a resort's rows hold both real times
 * ({@code 8:00}, {@code 0800}, {@code 8am}, {@code 4:00 pm}, {@code 13:00}, a bare {@code 6}) and
 * things that were never times at all ({@code dir}, {@code swing}, {@code mentee}, {@code aid
 * room}). Splitting the field into a Label and a Time needs one answer to "is this a time?", used
 * in three places that must agree: the migration that sorts the existing rows, the controllers that
 * validate what a director types, and the tests.
 *
 * <p><b>Everything is normalized to {@code H:MM}</b> - {@code 0800} and {@code 8am} and
 * {@code 8:00} all become {@code 8:00}. That is not tidiness: {@code shiftKey} in both controllers
 * identifies one shift by its start and end strings, so two rows of the same shift typed
 * {@code 8:00} and {@code 0800} would otherwise count as two different shifts and let somebody be
 * booked on both.
 *
 * <p><b>The match is anchored and whole-string</b>, which is the whole difficulty. {@code PMExtra}
 * is a real label in Brighton's data: anything that goes looking for "pm" inside the string turns
 * it into 12:00 and silently loses a label.
 */
public final class ShiftTime {

  private ShiftTime() {
  }

  /** {@code 8:00}, {@code 08:00}, {@code 8:00am}, {@code 4:00 pm} */
  private static final Pattern HOUR_MINUTE =
    Pattern.compile("^\\s*(\\d{1,2})\\s*:\\s*(\\d{2})\\s*(?:([ap])\\.?m\\.?)?\\s*$",
                    Pattern.CASE_INSENSITIVE);

  /** {@code 8am}, {@code 4 pm} - the meridiem is required, or a bare number is read as 24 hour */
  private static final Pattern HOUR_MERIDIEM =
    Pattern.compile("^\\s*(\\d{1,2})\\s*([ap])\\.?m\\.?\\s*$", Pattern.CASE_INSENSITIVE);

  /** a bare {@code 6} or {@code 16}, read as 24 hour */
  private static final Pattern BARE_HOUR = Pattern.compile("^\\s*(\\d{1,2})\\s*$");

  /** {@code 800}, {@code 0800}, {@code 1900} - no separator at all */
  private static final Pattern PACKED = Pattern.compile("^\\s*(\\d{3,4})\\s*$");

  /**
   * The canonical form of a time, or null when this is not a time at all.
   *
   * <p>Null is the answer for a label, for empty, and for a number that cannot be a clock time
   * ({@code 25:00}, {@code 8:75}, {@code 2400}) - a caller deciding where to put an old value
   * treats all of those the same way, as "not a time".
   */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String text = raw.trim();
    if (text.isEmpty()) {
      return null;
    }

    Matcher hourMinute = HOUR_MINUTE.matcher(text);
    if (hourMinute.matches()) {
      return build(number(hourMinute.group(1)), number(hourMinute.group(2)), hourMinute.group(3));
    }

    Matcher hourMeridiem = HOUR_MERIDIEM.matcher(text);
    if (hourMeridiem.matches()) {
      return build(number(hourMeridiem.group(1)), 0, hourMeridiem.group(2));
    }

    Matcher bareHour = BARE_HOUR.matcher(text);
    if (bareHour.matches()) {
      return build(number(bareHour.group(1)), 0, null);
    }

    Matcher packed = PACKED.matcher(text);
    if (packed.matches()) {
      String digits = packed.group(1);
      int split = digits.length() - 2;
      return build(number(digits.substring(0, split)), number(digits.substring(split)), null);
    }

    return null;
  }

  /** Whether this value belongs in the Start/End field rather than in the Label beside it. */
  public static boolean isTime(String raw) {
    return normalize(raw) != null;
  }

  /**
   * Null when the hour and minute are not a real clock time, so every rejection leaves by the same
   * door as an unrecognised format.
   */
  private static String build(int hour, int minute, String meridiem) {
    if (minute < 0 || minute > 59) {
      return null;
    }
    if (meridiem != null) {
      //a meridiem means a 12 hour clock: 12am is midnight and 12pm is noon, and there is no 0am
      if (hour < 1 || hour > 12) {
        return null;
      }
      boolean pm = meridiem.equalsIgnoreCase("p");
      if (hour == 12) {
        hour = pm ? 12 : 0;
      }
      else if (pm) {
        hour += 12;
      }
    }
    else if (hour < 0 || hour > 23) {
      return null;
    }
    return hour + ":" + (minute < 10 ? "0" : "") + minute;
  }

  private static int number(String digits) {
    return Integer.parseInt(digits.trim(), 10);
  }

  /** Lower-cased and trimmed, for comparing a typed value against a stored one. */
  static String key(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
  }
}
