package com.gledhills.calendar.controller;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Shared plumbing for the /MonthCalendar tests.
 *
 * These run against the live Sample database, like the other controller tests, and assume
 * 123456 (Steve) is a director and 200401 is not.
 */
final class MonthCalendarTestHarness {

  static final String DIRECTOR_ID = "123456";
  static final String MEMBER_ID = "200401";

  /**
   * BotBlockingFilter answers 403 to anything that looks automated - and a request with NO
   * User-Agent counts as automated. Every test request must carry one.
   */
  static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  /**
   * Months the golden files are recorded for. MonthCalendarService refuses a year outside
   * realCurrYear-2..realCurrYear+1 and silently falls back to today's month, which would quietly
   * turn every golden into a recording of the current month -
   * MonthCalendarGoldenTest.goldenYearsAreStillInsideTheAllowedWindow guards against that.
   */
  static final int BASE_YEAR = 2026;
  static final int LEAP_YEAR = 2024;
  /** November 2024 is the one month in the Sample data with a missed shift and a replacement flag. */
  static final int RICH_DATA_YEAR = 2024;

  private MonthCalendarTestHarness() {
  }

  static MockHttpServletRequestBuilder calendarRequest(int month, int year, String userId) {
    return calendarRequest(month, year, userId, Map.of());
  }

  /**
   * @param month 0 based, as MonthCalendar expects
   * @param userId member to be logged in as, or null for an anonymous visitor
   * @param extra additional query parameters, e.g. textLen to force the dense layout
   */
  static MockHttpServletRequestBuilder calendarRequest(int month, int year, String userId,
                                                       Map<String, String> extra) {
    MockHttpServletRequestBuilder builder = get("/MonthCalendar")
      .param("resort", "Sample")
      .param("month", String.valueOf(month))
      .param("year", String.valueOf(year))
      .header("User-Agent", BROWSER);

    for (Map.Entry<String, String> parameter : extra.entrySet()) {
      builder = builder.param(parameter.getKey(), parameter.getValue());
    }

    if (userId != null) {
      MockHttpSession session = new MockHttpSession();
      session.setAttribute("loggedInUserId", userId);
      session.setAttribute("loggedInResort", "Sample");
      builder = builder.session(session).param("ID", userId);
    }

    return builder;
  }

  static String render(MockMvc mockMvc, int month, int year, String userId) throws Exception {
    return render(mockMvc, month, year, userId, Map.of());
  }

  static String render(MockMvc mockMvc, int month, int year, String userId,
                       Map<String, String> extra) throws Exception {
    return mockMvc.perform(calendarRequest(month, year, userId, extra))
      .andReturn().getResponse().getContentAsString();
  }

  /**
   * Blank out the parts of the page that change with the wall clock, so a golden file recorded
   * today still matches tomorrow:
   * <ul>
   *   <li>the "As of:" line, which carries the render timestamp</li>
   *   <li>the home button, whose href and ALT text carry the real current month/year</li>
   * </ul>
   * Both are whole-line replacements: a substring match missed the timestamp once the template
   * started wrapping it in a span.
   */
  /**
   * Removes the parts of the page that change on their own, so a golden records the calendar and
   * not the day it was recorded.
   *
   * <p>The home button is scrubbed <b>anchor and all</b>, not just its image line. Its href is
   * "go to today", built from the real clock - {@code month=7} in August, {@code month=8} in
   * September - so scrubbing only the {@code <img>} line left the volatile month sitting on the
   * line above it and every golden failed on the first of the month.
   */
  static String scrubVolatile(String html) {
    return html
      .replaceAll("(?m)^.*As of:.*$", "[AS OF LINE SCRUBBED]")
      .replaceAll("<a[^>]*><img[^>]*ncgohome\\.gif[^>]*></a>", "[HOME BUTTON SCRUBBED]");
  }

  /** Named cases the golden test records, kept in one place so both tests agree on them. */
  static Map<String, Case> goldenCases() {
    Map<String, Case> cases = new LinkedHashMap<>();
    cases.put("january-director", new Case(0, BASE_YEAR, DIRECTOR_ID, Map.of()));
    cases.put("january-member", new Case(0, BASE_YEAR, MEMBER_ID, Map.of()));
    cases.put("january-director-dense", new Case(0, BASE_YEAR, DIRECTOR_ID, Map.of("textLen", "20")));
    cases.put("february-leap-year-director", new Case(1, LEAP_YEAR, DIRECTOR_ID, Map.of()));
    cases.put("july-out-of-season-director", new Case(6, BASE_YEAR, DIRECTOR_ID, Map.of()));
    //assigned patrollers, a struck-through missed shift and a needs-replacement highlight
    cases.put("november-assigned-director", new Case(10, RICH_DATA_YEAR, DIRECTOR_ID, Map.of()));
    cases.put("november-assigned-member", new Case(10, RICH_DATA_YEAR, MEMBER_ID, Map.of()));
    return cases;
  }

  record Case(int month, int year, String userId, Map<String, String> extra) {
  }
}
