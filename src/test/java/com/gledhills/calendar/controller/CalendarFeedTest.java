package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.CalendarFeedToken;
import com.gledhills.calendar.store.DirectorSettings;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The calendar subscription feed.
 *
 * <p>Runs against <b>Brighton</b> rather than Sample, and has to: a demo resort can never sync, so
 * Sample cannot reach any of this. The scratch shifts are written to real Brighton rows on dates
 * far from anything a golden test renders, and removed afterwards.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class CalendarFeedTest {

  private static final String RESORT = "Brighton";
  private static final String MEMBER = "192443";
  private static final String TIMED_SHIFT = "2027-02-09_8";
  private static final String LABEL_ONLY_SHIFT = "2027-02-10_8";
  private static final String EMPTY_SHIFT = "2027-02-11_8";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;
  private boolean syncWasOn;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    withPatrolData((log, patrolData) -> {
      CalendarFeedToken.createTable(log, patrolData.getConnection());
      DirectorSettings settings = patrolData.readDirectorSettings();
      syncWasOn = settings.getSyncEnabled();
      settings.setSyncEnabled(true);
      settings.write(patrolData.getConnection());
    });
  }

  @AfterEach
  void tidyUp() {
    withPatrolData((log, patrolData) -> {
      run(patrolData, "DELETE FROM assignments WHERE `Date` IN ('" + TIMED_SHIFT + "','"
                        + LABEL_ONLY_SHIFT + "','" + EMPTY_SHIFT + "')");
      CalendarFeedToken.revoke(log, patrolData.getConnection(), MEMBER);
      DirectorSettings settings = patrolData.readDirectorSettings();
      settings.setSyncEnabled(syncWasOn);
      settings.write(patrolData.getConnection());
    });
  }

  /**
   * A timed shift becomes an event at the right instant.
   *
   * <p>9 February is deliberately in winter: Brighton is on Mountain Standard Time then, so 08:00
   * local is 15:00 UTC. A feed that ignored the zone, or used the server's, would be an hour or
   * more out and every patroller would be late.
   */
  @Test
  void aTimedShiftBecomesAnEventAtTheRightInstant() throws Exception {
    writeShift(TIMED_SHIFT, "", "8:00", "16:30", 2);

    String ics = feed(issueToken());

    assertTrue(ics.contains("DTSTART:20270209T150000Z"),
               "08:00 Mountain in February is 15:00 UTC: " + eventIn(ics));
    assertTrue(ics.contains("DTEND:20270209T233000Z"), "and 16:30 is 23:30 UTC: " + eventIn(ics));
    assertTrue(ics.contains("UID:" + TIMED_SHIFT + "@" + RESORT),
               "the UID must be stable, or every refresh duplicates the event");
  }

  /**
   * A shift with a label and no times still reaches the patroller, as an all-day entry.
   *
   * <p>Leaving it out would tell somebody they are free on a day they are rostered - the one
   * failure here that actually costs a shift.
   */
  @Test
  void aLabelOnlyShiftBecomesAnAllDayEvent() throws Exception {
    writeShift(LABEL_ONLY_SHIFT, "Helper", "", "", 4);

    String ics = feed(issueToken());

    assertTrue(ics.contains("DTSTART;VALUE=DATE:20270210"), "all day, not midnight: " + eventIn(ics));
    assertTrue(ics.contains("DTEND;VALUE=DATE:20270211"), "and ending the next day");
    assertTrue(ics.contains("SUMMARY:Brighton Patrol: Helper"), "named by its label");
  }

  /** A row nobody works is a spacer a director used to lay the day out, not an appointment. */
  @Test
  void aShiftWithNoPatrollersIsLeftOut() throws Exception {
    writeShift(EMPTY_SHIFT, "", "8:00", "16:00", 0);

    String ics = feed(issueToken());

    assertFalse(ics.contains(EMPTY_SHIFT), "a count of 0 must not become a calendar entry");
  }

  /** The document has to be a document, whatever is or is not in it. */
  @Test
  void theFeedIsAWellFormedCalendar() throws Exception {
    String ics = feed(issueToken());

    assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"), "and CRLF, which the format requires");
    assertTrue(ics.trim().endsWith("END:VCALENDAR"));
    assertTrue(ics.contains("VERSION:2.0"));
    assertEquals(count(ics, "BEGIN:VEVENT"), count(ics, "END:VEVENT"), "every event is closed");
    for (String line : ics.split("\r\n")) {
      assertTrue(line.length() <= 75,
                 "a line over 75 octets has to be folded or parsers truncate it: " + line);
    }
  }

  /** An unknown token is a 404 - never a redirect, which a calendar app would cache as the feed. */
  @Test
  void anUnknownTokenIsNotFound() throws Exception {
    mockMvc.perform(get("/feed/" + RESORT + "/nosuchtokenatall.ics").header("User-Agent", BROWSER))
           .andExpect(status().isNotFound());
  }

  /** Switching sync off stops every subscription at that resort on the next fetch. */
  @Test
  void theFeedStopsWhenTheDirectorSwitchesSyncOff() throws Exception {
    String token = issueToken();
    assertEquals(200, fetch(token).getStatus(), "on to begin with");

    withPatrolData((log, patrolData) -> {
      DirectorSettings settings = patrolData.readDirectorSettings();
      settings.setSyncEnabled(false);
      settings.write(patrolData.getConnection());
    });

    assertEquals(404, fetch(token).getStatus(), "and gone the moment it is switched off");
  }

  /** A resort not cleared for the feature serves no feed at all, whatever its settings say. */
  @Test
  void aResortNotClearedForSyncServesNothing() throws Exception {
    mockMvc.perform(get("/feed/Sample/anything.ics").header("User-Agent", BROWSER))
           .andExpect(status().isNotFound());
  }

  /**
   * Replacing a link kills the old one at once.
   *
   * <p>The whole point of the reset button: a link that outlived its replacement would leave the
   * person it leaked to still reading the schedule.
   */
  @Test
  void replacingTheLinkRetiresTheOldOne() throws Exception {
    String first = issueToken();
    assertEquals(200, fetch(first).getStatus());

    String second = issueToken();

    assertFalse(first.equals(second), "a new token, not the same one back");
    assertEquals(404, fetch(first).getStatus(), "the old link must stop working");
    assertEquals(200, fetch(second).getStatus(), "and the new one work");
  }

  /**
   * A calendar app sends no User-Agent, which BotBlockingFilter calls a bot.
   *
   * <p>Without the exemption every subscriber gets a silent 403 for ever and the calendar simply
   * stays empty, with nothing anywhere saying why.
   *
   * <p>The filter has to be registered on this MockMvc explicitly - the shared one in these tests
   * does not run it, which is why the rest of them can send a browser User-Agent and never notice.
   * The first assertion proves the filter really is live here, so the second means something.
   */
  @Test
  void aCalendarAppWithNoUserAgentIsNotTreatedAsABot() throws Exception {
    MockMvc filtered = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                                      .addFilters(new com.gledhills.calendar.config.BotBlockingFilter())
                                      .build();

    assertEquals(403,
                 filtered.perform(get("/MonthCalendar").param("resort", RESORT))
                         .andReturn().getResponse().getStatus(),
                 "an ordinary page with no User-Agent is blocked - so the filter is live here");

    String token = issueToken();
    assertEquals(200,
                 filtered.perform(get("/feed/" + RESORT + "/" + token + ".ics"))
                         .andReturn().getResponse().getStatus(),
                 "but the feed path is exempt, or no calendar app could ever read it");
  }

  private String issueToken() {
    String[] token = new String[1];
    withPatrolData((log, patrolData) ->
      token[0] = CalendarFeedToken.issue(log, patrolData.getConnection(), MEMBER));
    assertNotNull(token[0], "a token should have been issued");
    return token[0];
  }

  private String feed(String token) throws Exception {
    return fetch(token).getContentAsString();
  }

  private org.springframework.mock.web.MockHttpServletResponse fetch(String token) throws Exception {
    return mockMvc.perform(get("/feed/" + RESORT + "/" + token + ".ics")
                             .header("User-Agent", BROWSER))
                  .andReturn().getResponse();
  }

  /** The one event's worth of text, for a readable failure message. */
  private String eventIn(String ics) {
    int at = ics.indexOf("BEGIN:VEVENT");
    return at < 0 ? "(no events)" : ics.substring(at, Math.min(ics.length(), at + 400));
  }

  private int count(String text, String needle) {
    return text.split(needle, -1).length - 1;
  }

  private void writeShift(String date, String label, String start, String end, int count) {
    withPatrolData((log, patrolData) -> {
      run(patrolData, "DELETE FROM assignments WHERE `Date` = '" + date + "'");
      run(patrolData, "INSERT INTO assignments (`Date`,`Label`,`StartTime`,`EndTime`,`EventName`,"
                        + "`ShiftType`,`Count`,`P0`) VALUES ('" + date + "','" + label + "','"
                        + start + "','" + end + "','',0," + count + ",'" + MEMBER + "')");
    });
  }

  private void run(PatrolData patrolData, String sql) {
    try (java.sql.Statement statement = patrolData.getConnection().createStatement()) {
      statement.executeUpdate(sql);
    }
    catch (Exception e) {
      throw new AssertionError("failed: " + sql, e);
    }
  }

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(new MockHttpSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(CalendarFeedTest.class, javaxRequest, "GET", RESORT, Logger.INFO);
    PatrolData patrolData = new PatrolData(RESORT, new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
    }
    finally {
      patrolData.close();
    }
  }
}
