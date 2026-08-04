package com.gledhills.calendar.dto;

import java.util.List;

/**
 * Everything month-calendar.html needs to draw one month, built by MonthCalendarService.
 *
 * Replaces the HTML that the legacy MonthCalendar servlet used to concatenate: the template now
 * owns the markup and this record owns the decisions (who may click what, which days are closed,
 * how wide the columns are).
 *
 * @param resortFullName  display name for the heading
 * @param monthName       "January", for the heading
 * @param month           0 based, as the query parameter uses
 * @param year            four digit year
 * @param weeks           calendar rows, always seven days each, padded with blank days
 * @param dense           weekend cells lay their shifts out in two columns
 * @param dayWidth        percentage width for a weekday column
 * @param weekendWidth    percentage width for a weekend column
 * @param textFontSize    point size for shift text
 * @param nav             previous/next/today links
 * @param testingMessage  loud warning when the app is pointed at the old database, else empty
 */
public record CalendarMonth(
  String resortFullName,
  String monthName,
  int month,
  int year,
  List<Week> weeks,
  boolean dense,
  int dayWidth,
  int weekendWidth,
  int textFontSize,
  Nav nav,
  String testingMessage) {

  /** One calendar row. */
  public record Week(List<Day> days) {
  }

  /**
   * One cell.
   *
   * @param dayNumber      day of the month, or 0 for the padding cells before/after the month
   * @param weekend        Sunday or Saturday, which drives column width and the dense layout
   * @param eventNameHtml  sanitized day comment, ready to render unescaped; empty when none
   * @param highlighted    the day has a comment, so the cell gets the accent background
   * @param closedLabel    "CLOSED"/"Not Yet Available" etc, or null when the day is open
   * @param dateLink        URL behind the day number (directors only), or null for plain text
   * @param shiftRows      shift slots already grouped into rows: one slot per row normally, two
   *                       per row for weekend cells in the dense layout. Empty when the day
   *                       shows no slots.
   */
  public record Day(
    int dayNumber,
    boolean weekend,
    String eventNameHtml,
    boolean highlighted,
    String closedLabel,
    String dateLink,
    List<List<Slot>> shiftRows) {

    public boolean isBlank() {
      return dayNumber == 0;
    }

    /** Total slots in the cell, however they are grouped into rows. */
    public int slotCount() {
      return shiftRows.stream().mapToInt(List::size).sum();
    }

    public boolean hasEventName() {
      return eventNameHtml != null && !eventNameHtml.isEmpty();
    }

    public boolean isClosed() {
      return closedLabel != null;
    }
  }

  /**
   * One patroller line inside a day cell.
   *
   * @param time              shift start time, always shown
   * @param memberName        patroller's name, or empty for an unfilled slot
   * @param tooltip           name and phone for the link's title attribute
   * @param missed            the shift was missed, so the name shows struck through
   * @param needsReplacement  flagged for replacement, so the name gets a yellow background
   * @param editLink          URL to change the assignment, or null when the viewer may not
   */
  public record Slot(
    String time,
    String memberName,
    String tooltip,
    boolean missed,
    boolean needsReplacement,
    String editLink) {

    public boolean isEditable() {
      return editLink != null;
    }
  }

  /** Month navigation targets. */
  public record Nav(String previousUrl, String nextUrl, String todayUrl, String todayLabel) {
  }
}
