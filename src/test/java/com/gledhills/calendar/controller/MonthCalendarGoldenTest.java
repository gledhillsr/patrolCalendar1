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

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
      .addFilters(botBlockingFilter)
      .build();
  }

  /**
   * MonthCalendarService rejects any year outside realCurrYear-2..realCurrYear+1 and falls back
   * to today's month without saying so. Once the clock moves past that window the
   * goldens would silently become recordings of the current month and stop testing anything.
   */
  @Test
  void goldenYearsAreStillInsideTheAllowedWindow() {
    int currentYear = new GregorianCalendar().get(Calendar.YEAR);

    for (int year : List.of(BASE_YEAR, LEAP_YEAR, RICH_DATA_YEAR)) {
      assertTrue(year >= currentYear - 2 && year <= currentYear + 1,
                 "golden year " + year + " has aged out of MonthCalendar's accepted range ("
                   + (currentYear - 2) + ".." + (currentYear + 1) + "). Pick fresh months in "
                   + "MonthCalendarTestHarness and re-record with -Dgolden.update=true");
    }
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
