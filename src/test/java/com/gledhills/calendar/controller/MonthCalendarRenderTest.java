package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.gledhills.calendar.config.BotBlockingFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gledhills.calendar.controller.MonthCalendarTestHarness.BASE_YEAR;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.DIRECTOR_ID;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.FUTURE_MONTH;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.FUTURE_YEAR;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.LEAP_YEAR;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.MEMBER_ID;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.calendarRequest;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the /MonthCalendar page is supposed to do, stated independently of how the HTML is
 * currently produced. These assertions must keep passing as the legacy MonthCalendar servlet is
 * migrated to Thymeleaf - unlike MonthCalendarGoldenTest, they are not tied to exact markup.
 *
 * Runs against the live Sample database.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class MonthCalendarRenderTest {

  @Autowired
  private WebApplicationContext webApplicationContext;

  @Autowired
  private BotBlockingFilter botBlockingFilter;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    //webAppContextSetup does not register servlet filters on its own, and the bot check is a
    //filter - without this the tests would not exercise what production does
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
      .addFilters(botBlockingFilter)
      .build();
  }

  @Test
  void anonymousVisitorIsSentToLogin() throws Exception {
    mockMvc.perform(calendarRequest(0, BASE_YEAR, null))
      .andExpect(status().is3xxRedirection())
      .andExpect(redirectedUrl("/calendar-1/MemberLogin?resort=Sample&NSPgoto=MonthCalendar"));
  }

  /**
   * BotBlockingFilter answers 403 to anything without a User-Agent. The legacy servlet used its
   * own in-controller check that answered 401; now that it is gone the calendar matches every
   * other page in the app.
   */
  @Test
  void requestWithoutUserAgentIsTreatedAsABot() throws Exception {
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                      .get("/MonthCalendar")
                      .param("resort", "Sample")
                      .param("month", "0")
                      .param("year", String.valueOf(BASE_YEAR)))
      .andExpect(status().isForbidden());
  }

  @Test
  void unknownResortIsRejected() throws Exception {
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                      .get("/MonthCalendar")
                      .param("resort", "NoSuchResort")
                      .param("month", "0")
                      .param("year", String.valueOf(BASE_YEAR))
                      .header("User-Agent", MonthCalendarTestHarness.BROWSER))
      .andExpect(status().isBadRequest());
  }

  /** Only directors may open DayShifts, so only they get the date as a link. */
  @Test
  void onlyDirectorsGetClickableDates() throws Exception {
    String directorPage = render(mockMvc, 0, BASE_YEAR, DIRECTOR_ID);
    String memberPage = render(mockMvc, 0, BASE_YEAR, MEMBER_ID);

    assertTrue(countOf(directorPage, "DayShifts?") > 0,
               "a director should be able to click a date");
    assertEquals(0, countOf(memberPage, "DayShifts?"),
                 "a non-director should see plain dates");
  }

  /** Members still reach the shift signup screen; only the date link is director-only. */
  @Test
  void membersKeepTheirShiftSignupLinks() throws Exception {
    String memberPage = render(mockMvc, FUTURE_MONTH, FUTURE_YEAR, MEMBER_ID);

    assertTrue(countOf(memberPage, "ChangeShiftAssignments?") > 0,
               "a member must still be able to sign up for a shift on a day still to come");
  }

  /**
   * A day that has already happened is nobody's to change but a director's.
   *
   * <p>Signing yourself into a shift that finished last March, or taking your name off one you had
   * already worked, rewrites the record a director has been reading. The freeze used to apply at
   * KellyCanyon and Brighton alone - a hard-coded pair carried over from the servlet - and now
   * applies everywhere.
   */
  @Test
  void aMemberCannotChangeAMonthThatHasPassed() throws Exception {
    String memberPage = render(mockMvc, 0, BASE_YEAR, MEMBER_ID);

    assertEquals(0, countOf(memberPage, "ChangeShiftAssignments?"),
                 "a member should get no signup links at all on a month in the past");
  }

  /**
   * The other half, and the reason directors are the exception: a shift somebody actually worked
   * sometimes has to be corrected after the fact.
   */
  @Test
  void aDirectorCanStillChangeAMonthThatHasPassed() throws Exception {
    String directorPage = render(mockMvc, 0, BASE_YEAR, DIRECTOR_ID);

    assertTrue(countOf(directorPage, "ChangeShiftAssignments?") > 0,
               "a director must still be able to fix a past shift");
  }

  @Test
  void everyDayOfTheMonthIsRendered() throws Exception {
    String january = render(mockMvc, 0, BASE_YEAR, DIRECTOR_ID);
    assertEquals(31, dayCellCount(january), "January has 31 days");

    String april = render(mockMvc, 3, BASE_YEAR, DIRECTOR_ID);
    assertEquals(30, dayCellCount(april), "April has 30 days");
  }

  @Test
  void februaryGainsADayInALeapYear() throws Exception {
    String leapFebruary = render(mockMvc, 1, LEAP_YEAR, DIRECTOR_ID);
    assertEquals(29, dayCellCount(leapFebruary), LEAP_YEAR + " is a leap year");

    String normalFebruary = render(mockMvc, 1, BASE_YEAR, DIRECTOR_ID);
    assertEquals(28, dayCellCount(normalFebruary), BASE_YEAR + " is not a leap year");
  }

  /** Sample's season runs 01-11 to 30-04, so a July calendar is closed all month. */
  @Test
  void monthsOutsideTheSeasonAreMarkedClosed() throws Exception {
    String july = render(mockMvc, 6, BASE_YEAR, DIRECTOR_ID);
    String january = render(mockMvc, 0, BASE_YEAR, DIRECTOR_ID);

    assertTrue(countOf(july, "CLOSED") > 0, "a July day should be marked CLOSED, out of season");
    assertEquals(0, countOf(january, "CLOSED"), "January is in season");
  }

  /** textLen switches the weekend columns to the two-column "dense" layout. */
  @Test
  void textLenForcesTheDenseWeekendLayout() throws Exception {
    String normal = render(mockMvc, 0, BASE_YEAR, DIRECTOR_ID);
    String dense = render(mockMvc, 0, BASE_YEAR, DIRECTOR_ID, Map.of("textLen", "20"));

    assertTrue(hasColumnWidth(normal, 14), "the normal layout gives every day 14%");
    assertTrue(hasColumnWidth(dense, 22) && hasColumnWidth(dense, 11),
               "the dense layout gives weekends 22% and weekdays 11%");
  }

  /**
   * The shift text is 10pt. This is a real regression guard: CSS inlining first emitted
   * "font-size: 10" (no unit) and then "font-size: \31 0pt" (escaped as an identifier), and both
   * are invalid, so browsers dropped the rule and drew the calendar in the default, larger font.
   */
  @Test
  void calendarCellsKeepTheirTenPointFont() throws Exception {
    String page = render(mockMvc, 0, BASE_YEAR, DIRECTOR_ID);

    assertTrue(page.contains("font-size: 10pt;"),
               "the calendar cell rule must carry a valid pt size");
    assertFalse(Pattern.compile("font-size:\\s*\\d+\\s*;").matcher(page).find(),
                "a unitless font-size is invalid and would be ignored by the browser");
    //match the declaration, not the token anywhere - the template comment mentions it too
    assertFalse(page.contains("font-size: \\31"),
                "an escaped numeric value means the CSS expression used [[..]] instead of [(..)]");
  }

  /** An out-of-range year is not an error: the calendar falls back to the current month. */
  @Test
  void unsupportedYearFallsBackToTheCurrentMonth() throws Exception {
    GregorianCalendar now = new GregorianCalendar();
    String expectedHeading = new java.text.SimpleDateFormat("MMMM yyyy").format(now.getTime());

    String page = render(mockMvc, 0, 1999, DIRECTOR_ID);

    assertTrue(page.contains("Shift Schedule for " + expectedHeading),
               "expected a fallback to " + expectedHeading + ", which the month/year 0/1999 is not");
    assertEquals(now.getActualMaximum(Calendar.DAY_OF_MONTH), dayCellCount(page),
                 "the fallback month should be fully rendered");
  }

  /** Counts the day-number elements, however they are marked up. */
  private static int dayCellCount(String html) {
    Matcher matcher = Pattern.compile("(?i)class=[\"']day-number[\"'][^>]*>\\s*(\\d+)\\s*<").matcher(html);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private static boolean hasColumnWidth(String html, int percent) {
    return Pattern.compile("(?i)width=[\"']" + percent + "%[\"']").matcher(html).find();
  }

  private static int countOf(String text, String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
