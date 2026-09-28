package com.gledhills.calendar.controller;

import com.gledhills.calendar.config.BotBlockingFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import static com.gledhills.calendar.controller.MonthCalendarTestHarness.BASE_YEAR;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.FUTURE_MONTH;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.FUTURE_YEAR;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.Case;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.LEAP_YEAR;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.RICH_DATA_YEAR;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.goldenCases;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.render;
import static com.gledhills.calendar.controller.MonthCalendarTestHarness.scrubVolatile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Byte-for-byte characterization of the /MonthCalendar page.
 *
 * Pins the HTML that MonthCalendarService and month-calendar.html produce, so any future edit to
 * either has to own up to what it changed: refactor, re-run, expect zero diff. It is a safety net,
 * NOT a statement that the output is correct - see MonthCalendarRenderTest for what the page is
 * supposed to do.
 *
 * Recording or updating the goldens (inspect the diff before committing!):
 *   mvn test -Dtest=MonthCalendarGoldenTest -Dgolden.update=true
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class MonthCalendarGoldenTest {

  private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden/month-calendar");

  @Autowired
  private WebApplicationContext webApplicationContext;

  @Autowired
  private BotBlockingFilter botBlockingFilter;

  private MockMvc mockMvc;
  private java.util.List<com.gledhills.calendar.store.MenuButton> originalButtons;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
      .addFilters(botBlockingFilter)
      .build();
    // The header now draws whatever menu buttons this resort has configured, so a golden recorded
    // while somebody had one set up would fail the moment they removed it. Blank them for the run
    // and put back exactly what was there - these goldens are about the calendar, not the menu.
    originalButtons = menuButtons();
    writeMenuButtons(blankButtons());
  }

  @org.junit.jupiter.api.AfterEach
  void restoreMenuButtons() {
    writeMenuButtons(originalButtons);
  }

  private java.util.List<com.gledhills.calendar.store.MenuButton> blankButtons() {
    java.util.List<com.gledhills.calendar.store.MenuButton> blanks = new java.util.ArrayList<>();
    for (int position = 1;
         position <= com.gledhills.calendar.store.MenuButton.MAX_BUTTONS; position++) {
      blanks.add(com.gledhills.calendar.store.MenuButton.empty(position));
    }
    return blanks;
  }

  private java.util.List<com.gledhills.calendar.store.MenuButton> menuButtons() {
    java.util.List<com.gledhills.calendar.store.MenuButton> stored = new java.util.ArrayList<>();
    withMenuButtonConnection(
      (log, patrolData) -> stored.addAll(com.gledhills.calendar.store.MenuButton
                                           .readAll(log, patrolData.getConnection())));
    return stored;
  }

  private void writeMenuButtons(java.util.List<com.gledhills.calendar.store.MenuButton> buttons) {
    withMenuButtonConnection(
      (log, patrolData) -> com.gledhills.calendar.store.MenuButton
                             .writeAll(log, patrolData.getConnection(), buttons));
  }

  private void withMenuButtonConnection(
      java.util.function.BiConsumer<com.gledhills.calendar.utils.Logger,
                                    com.gledhills.calendar.utils.PatrolData> work) {
    org.springframework.mock.web.MockHttpServletRequest request =
      new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.mock.web.MockHttpSession session =
      new org.springframework.mock.web.MockHttpSession();
    session.setAttribute("loggedInUserId", "123456");
    session.setAttribute("loggedInResort", "Sample");
    request.setSession(session);
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    com.gledhills.calendar.utils.Logger log = new com.gledhills.calendar.utils.Logger(
      MonthCalendarGoldenTest.class, javaxRequest, "GET", "Sample",
      com.gledhills.calendar.utils.Logger.INFO);
    com.gledhills.calendar.utils.PatrolData patrolData =
      new com.gledhills.calendar.utils.PatrolData(
        "Sample", new com.gledhills.calendar.utils.SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
    }
    finally {
      patrolData.close();
    }
  }

  /**
   * MonthCalendarService rejects any year outside realCurrYear-2..realCurrYear+1 and falls back
   * to today's month without saying so. Once the clock moves past that window the
   * goldens would silently become recordings of the current month and stop testing anything.
   */
  @Test
  void goldenYearsAreStillInsideTheAllowedWindow() {
    int currentYear = new GregorianCalendar().get(Calendar.YEAR);

    for (int year : List.of(BASE_YEAR, LEAP_YEAR, RICH_DATA_YEAR, FUTURE_YEAR)) {
      assertTrue(year >= currentYear - 2 && year <= currentYear + 1,
                 "golden year " + year + " has aged out of MonthCalendar's accepted range ("
                   + (currentYear - 2) + ".." + (currentYear + 1) + "). Pick fresh months in "
                   + "MonthCalendarTestHarness and re-record with -Dgolden.update=true");
    }

    //FUTURE_MONTH/FUTURE_YEAR carries the "a member may still sign up" tests, and a past month
    //is now frozen for members - so the day it stops being in the future those tests start
    //failing for a reason that has nothing to do with what they are checking.
    GregorianCalendar now = new GregorianCalendar();
    boolean stillAhead = FUTURE_YEAR > now.get(Calendar.YEAR)
                           || (FUTURE_YEAR == now.get(Calendar.YEAR)
                                 && FUTURE_MONTH > now.get(Calendar.MONTH));
    assertTrue(stillAhead,
               "FUTURE_MONTH/FUTURE_YEAR in MonthCalendarTestHarness is no longer in the future, "
                 + "so the member-signup tests are now asking about a frozen month. Move it on.");
  }

  @Test
  void renderedCalendarsMatchTheirGoldenFiles() throws Exception {
    boolean updating = Boolean.getBoolean("golden.update");
    List<String> failures = new ArrayList<>();

    for (Map.Entry<String, Case> entry : goldenCases().entrySet()) {
      String name = entry.getKey();
      Case testCase = entry.getValue();
      Path goldenFile = GOLDEN_DIR.resolve(name + ".html");

      String actual = scrubVolatile(render(mockMvc, testCase.month(), testCase.year(),
                                          testCase.userId(), testCase.extra()));

      if (updating || !Files.exists(goldenFile)) {
        Files.createDirectories(GOLDEN_DIR);
        Files.writeString(goldenFile, actual);
        failures.add(name + ": golden " + (updating ? "updated" : "did not exist and was created"));
        continue;
      }

      String expected = Files.readString(goldenFile);
      if (!expected.equals(actual)) {
        failures.add(name + ": output differs from " + goldenFile + "\n" + firstDifference(expected, actual));
      }
    }

    if (!failures.isEmpty()) {
      fail("MonthCalendar output changed (re-record with -Dgolden.update=true once the change is "
             + "intended):\n\n" + String.join("\n\n", failures));
    }
  }

  /** Every golden holds a real calendar, not an error page or a login redirect. */
  @Test
  void goldenFilesLookLikeCalendars() throws Exception {
    for (String name : goldenCases().keySet()) {
      Path goldenFile = GOLDEN_DIR.resolve(name + ".html");
      if (!Files.exists(goldenFile)) {
        continue; //renderedCalendarsMatchTheirGoldenFiles reports the missing file
      }
      String golden = Files.readString(goldenFile);
      assertTrue(golden.contains("Shift Schedule for"), name + " should contain the calendar heading");
      assertTrue(golden.contains("Sunday") && golden.contains("Saturday"),
                 name + " should contain the day-of-week header row");
      assertEquals(1, countOf(golden, "class=\"calendar\""),
                   name + " should hold exactly one calendar table");
    }
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

  /** A readable pointer at the first differing line rather than a 50KB dump. */
  private static String firstDifference(String expected, String actual) {
    String[] expectedLines = expected.split("\n", -1);
    String[] actualLines = actual.split("\n", -1);

    for (int line = 0; line < Math.min(expectedLines.length, actualLines.length); line++) {
      if (!expectedLines[line].equals(actualLines[line])) {
        return "  first difference at line " + (line + 1)
          + "\n  expected: " + expectedLines[line].trim()
          + "\n  actual:   " + actualLines[line].trim();
      }
    }
    return "  identical for " + Math.min(expectedLines.length, actualLines.length)
      + " lines, then length differs (expected " + expectedLines.length
      + " lines, actual " + actualLines.length + ")";
  }
}
