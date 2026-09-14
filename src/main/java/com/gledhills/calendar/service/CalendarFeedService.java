package com.gledhills.calendar.service;

import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.ResortTimeZones;
import com.gledhills.calendar.utils.ShiftTime;
import com.gledhills.calendar.utils.SiteIdentity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * One patroller's shifts, as an iCalendar document their own calendar can subscribe to.
 *
 * <p>Written by hand rather than with a library because the output is small and the format's
 * awkward corners - line folding, escaping, stable UIDs - are fewer than the corners of adding a
 * dependency to a twenty year old application.
 *
 * <p><b>Times go out in UTC.</b> The alternative is a {@code TZID} naming a zone plus a
 * {@code VTIMEZONE} block defining it, and a hand-written {@code VTIMEZONE} that gets a daylight
 * rule wrong is wrong twice a year in a way nobody reports. Converting through the resort's own
 * {@link ResortTimeZones} zone at each shift's date settles daylight saving here, where it can be
 * tested, and every client understands a UTC instant.
 */
public final class CalendarFeedService {

  /** How far back the feed reaches. Recent history is useful; the whole archive is not. */
  private static final int DAYS_OF_HISTORY = 30;

  private static final DateTimeFormatter UTC_STAMP =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
  private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyyMMdd");

  private CalendarFeedService() {
  }

  /**
   * The whole document, ready to serve.
   *
   * @param patrollerId whose shifts - already resolved from the token by the caller
   */
  public static String buildFeed(String resort, String patrollerId, PatrolData patrolData,
                                 com.gledhills.calendar.utils.Logger LOG) {
    ZoneId zone = ResortTimeZones.inForce(resort, patrolData.readDirectorSettings());
    LocalDate earliest = LocalDate.now(zone).minusDays(DAYS_OF_HISTORY);
    String resortName = PatrolData.getResortFullName(resort, LOG);

    StringBuilder ics = new StringBuilder();
    line(ics, "BEGIN:VCALENDAR");
    line(ics, "VERSION:2.0");
    line(ics, "PRODID:-//" + SiteIdentity.DOMAIN + "//PatrolCalendar//EN");
    line(ics, "CALSCALE:GREGORIAN");
    line(ics, "METHOD:PUBLISH");
    line(ics, "X-WR-CALNAME:" + escape(resortName + " Patrol Shifts"));
    line(ics, "X-WR-CALDESC:" + escape("Your scheduled patrol shifts at " + resortName));
    //Both spellings of the same hint: one is the standard, the other is what several clients
    //actually read. Neither is binding - the client refreshes when it feels like it.
    line(ics, "REFRESH-INTERVAL;VALUE=DURATION:PT1H");
    line(ics, "X-PUBLISHED-TTL:PT1H");

    String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(UTC_STAMP);
    for (Assignments shift : patrolData.readAllSortedAssignments(patrollerId)) {
      appendEvent(ics, shift, zone, earliest, stamp, resort, resortName);
    }

    line(ics, "END:VCALENDAR");
    return ics.toString();
  }

  /**
   * One shift, or nothing at all when it is not a shift anybody works.
   *
   * <p>A row with a patroller count of zero is a spacer a director used to lay a day out - there is
   * nobody on it, so putting it in somebody's calendar would be an appointment for nothing.
   */
  private static void appendEvent(StringBuilder ics, Assignments shift, ZoneId zone,
                                  LocalDate earliest, String stamp, String resort,
                                  String resortName) {
    if (shift.getCount() <= 0) {
      return;
    }
    LocalDate date;
    try {
      date = LocalDate.of(shift.getYear(), shift.getMonth(), shift.getDay());
    }
    catch (Exception e) {
      return;                              //a date that will not parse is not one to guess at
    }
    if (date.isBefore(earliest)) {
      return;
    }

    LocalTime start = timeOf(shift.getStartingTimeString());
    LocalTime end = timeOf(shift.getEndingTimeString());

    line(ics, "BEGIN:VEVENT");
    //Stable, so a refresh updates the event a patroller may have dragged about rather than adding
    //a second copy of it. The row's own key is already unique within the resort.
    line(ics, "UID:" + shift.getDate() + "@" + resort + "." + SiteIdentity.DOMAIN);
    line(ics, "DTSTAMP:" + stamp);

    if (start == null) {
      //A shift with no readable start - "Helper", "Instructor" - still goes out, as an all-day
      //entry. Leaving it out entirely would tell a patroller they are free on a day they are
      //rostered, which is the one failure here that costs somebody a shift.
      line(ics, "DTSTART;VALUE=DATE:" + date.format(DATE_ONLY));
      line(ics, "DTEND;VALUE=DATE:" + date.plusDays(1).format(DATE_ONLY));
    }
    else {
      ZonedDateTime from = ZonedDateTime.of(LocalDateTime.of(date, start), zone);
      //An end before the start is an overnight shift, not a mistake: a 20:00-02:00 patrol ends the
      //next day. An hour is the fallback when there is no end at all.
      ZonedDateTime to = end == null
                           ? from.plusHours(1)
                           : ZonedDateTime.of(LocalDateTime.of(date, end), zone);
      if (!to.isAfter(from)) {
        to = to.plusDays(1);
      }
      line(ics, "DTSTART:" + from.withZoneSameInstant(ZoneOffset.UTC).format(UTC_STAMP));
      line(ics, "DTEND:" + to.withZoneSameInstant(ZoneOffset.UTC).format(UTC_STAMP));
    }

    line(ics, "SUMMARY:" + escape(summaryOf(shift, resortName)));
    line(ics, "LOCATION:" + escape(resortName));
    line(ics, "DESCRIPTION:" + escape(descriptionOf(shift)));
    line(ics, "END:VEVENT");
  }

  /** What the patroller reads at a glance in their own calendar. */
  private static String summaryOf(Assignments shift, String resortName) {
    String label = shift.getLabel() == null ? "" : shift.getLabel().trim();
    String name = shift.getEventName() == null ? "" : shift.getEventName().trim();
    String what = !label.isEmpty() ? label : (!name.isEmpty() ? name : "Patrol shift");
    return resortName + " Patrol: " + what;
  }

  private static String descriptionOf(Assignments shift) {
    StringBuilder text = new StringBuilder(shift.describeLabelAndTimes());
    String name = shift.getEventName() == null ? "" : shift.getEventName().trim();
    if (!name.isEmpty() && !name.equals("null")) {
      text.append("\n").append(name);
    }
    return text.toString();
  }

  /** Null when the field holds something that is not a clock time, which is left to the caller. */
  private static LocalTime timeOf(String raw) {
    String canonical = ShiftTime.normalize(raw);
    if (canonical == null) {
      return null;
    }
    try {
      String[] parts = canonical.split(":");
      return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
    catch (Exception e) {
      return null;
    }
  }

  /**
   * RFC 5545 text escaping: backslash, semicolon, comma and newline all mean something in a
   * property value, so a shift label containing one would end the value early.
   */
  private static String escape(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("\\", "\\\\")
               .replace("\n", "\\n")
               .replace(";", "\\;")
               .replace(",", "\\,");
  }

  /**
   * One property, folded and CRLF-terminated.
   *
   * <p>A line over 75 octets has to be broken and continued with a leading space, or strict parsers
   * reject the file - and the ones that do not reject it truncate instead, which is worse because
   * it looks like it worked. Folded on characters rather than octets, which is conservative for the
   * ASCII these values are in practice.
   */
  private static void line(StringBuilder out, String property) {
    int limit = 73;
    if (property.length() <= limit) {
      out.append(property).append("\r\n");
      return;
    }
    out.append(property, 0, limit).append("\r\n");
    int at = limit;
    while (at < property.length()) {
      int next = Math.min(at + limit - 1, property.length());
      out.append(' ').append(property, at, next).append("\r\n");
      at = next;
    }
  }
}
