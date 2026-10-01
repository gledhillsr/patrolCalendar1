package com.gledhills.calendar.controller;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "email THESE patrollers" must mail exactly the people the Patrollers page listed.
 *
 * The button carries the page's own filters through in its URL, so this walks the real route: read
 * the page, pull the button's URL out of it, follow it, and compare the two name lists. Two ways
 * they drifted apart:
 *
 * <ul>
 *   <li>the "Only list if less than N shifts" limit was applied as "at least N" on the email side,
 *       which mails the exact complement of what was listed</li>
 *   <li>the limit was only forwarded when a day/swing/night count was shown, so a Training-only or
 *       Holiday-only listing sent EmailForm no limit at all - and no limit means everybody</li>
 * </ul>
 *
 * Read-only; nothing here writes.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class EmailListMatchesPatrollerListTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String MEMBER_ID = "200401";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  /** Every classification and commitment, so the population is the whole roster before filtering. */
  private static final String EVERYBODY =
    "&NAME=LAST&AUX=1&BAS=1&SR=1&SRA=1&TRA=1&CAN=1&PRO=1&ALM=1&INA=1&OTH=1"
      + "&FullTime=1&PartTime=1&Inactive=1&ALL=1";

  /** Wide enough to cover whatever the sample data holds. */
  private static final String WHOLE_RANGE =
    "&StartDay=1&StartMonth=0&StartYear=2000&EndDay=31&EndMonth=11&EndYear=2030";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /** No shift filter at all: the two lists have always agreed here, and must keep agreeing. */
  @Test
  void withNoShiftLimitTheListsMatch() throws Exception {
    assertListsMatch(EVERYBODY);
  }

  /**
   * The "less than N shifts" limit on a day count. Before the fix the email side kept everyone
   * with N or more - the complement of the page - which is what put extra people on the list.
   */
  @Test
  void withADayShiftLimitTheListsMatch() throws Exception {
    assertListsMatch(EVERYBODY + WHOLE_RANGE + "&DAY_CNT=1&MIN_DAYS=1&MinDays=1");
  }

  /**
   * The same limit on a counter the button used to ignore. Before the fix EmailForm received no
   * MIN_DAYS and no date range, so it applied no shift filter and mailed the whole roster.
   */
  @Test
  void withATrainingOnlyShiftLimitTheListsMatch() throws Exception {
    assertListsMatch(EVERYBODY + WHOLE_RANGE + "&TRAINING_CNT=1&MIN_DAYS=1&MinDays=1");
  }

  @Test
  void withAHolidayOnlyShiftLimitTheListsMatch() throws Exception {
    assertListsMatch(EVERYBODY + WHOLE_RANGE + "&HOLIDAY_CNT=1&MIN_DAYS=1&MinDays=2");
  }

  /**
   * The button's URL has to carry the limit for <b>every</b> counter the setup page allows it on,
   * not just day/swing/night. Asserted on the URL rather than on the resulting lists because
   * whether the two lists differ depends on the roster having shifts of that type - and when it
   * has none, both sides return everybody and a comparison proves nothing.
   */
  @Test
  void theEmailUrlCarriesTheShiftLimitForEveryCounter() throws Exception {
    for (String counter : new String[] {"DAY_CNT", "SWING_CNT", "NIGHT_CNT",
                                        "TRAINING_CNT", "OTHER_CNT", "HOLIDAY_CNT"}) {
      String url = emailButtonUrl(
        listPatrollers(EVERYBODY + WHOLE_RANGE + "&" + counter + "=1&MIN_DAYS=1&MinDays=3"));

      assertTrue(url.contains("MIN_DAYS=1"),
                 counter + " listing should pass the limit on: " + url);
      assertTrue(url.contains("MinDays=3"), counter + " should pass the limit's value: " + url);
      assertTrue(url.contains("&" + counter + "=1"),
                 counter + " should say which shifts to count: " + url);
      assertTrue(url.contains("StartYear=2000") && url.contains("EndYear=2030"),
                 counter + " should pass the range the count applies over: " + url);
    }
  }

  /**
   * The Patrollers tab in the site header goes to MemberList, not ListPatrollers, and builds its
   * own email URL from a hardcoded string. That string said Inactive=1 while the page itself skips
   * commitment "0", so the mail went to inactive members the tab had never shown.
   */
  @Test
  void thePatrollersTabEmailListMatchesTheTab() throws Exception {
    String page = follow("MemberList?resort=Sample&ID=" + DIRECTOR_ID);
    Set<String> listed = namesOnTheTab(page);
    assertFalse(listed.isEmpty(), "the Patrollers tab should list somebody: " + page);

    Set<String> mailed = namesToBeMailed(follow(emailButtonUrl(page)));

    assertEquals(listed, mailed,
                 "the email list should hold exactly the patrollers the tab listed."
                   + "\n  listed but not mailed: " + missing(listed, mailed)
                   + "\n  mailed but not listed: " + missing(mailed, listed));
  }

  /**
   * The inactive half of that bug, asserted on the URL: the sample roster happens to hold no
   * inactive member, so comparing the two lists cannot see it. The tab's loop skips commitment
   * "0", so its email URL must not turn Inactive back on. Alumni, Other and the legacy Inactive
   * classification are left off the tab too, and so must be left off its email list.
   */
  @Test
  void thePatrollersTabEmailUrlAsksForOnlyWhatTheTabShows() throws Exception {
    String url = emailButtonUrl(follow("MemberList?resort=Sample&ID=" + DIRECTOR_ID));

    assertFalse(url.contains("Inactive=1"),
                "the tab hides inactive members, so its email list must not include them: " + url);
    assertTrue(url.contains("FullTime=1") && url.contains("PartTime=1"),
               "but it must still ask for the members it does show: " + url);

    for (String hidden : new String[] {"ALM", "OTH", "INA"}) {
      assertFalse(url.contains("&" + hidden + "=1"),
                  hidden + " is not listed on the tab, so it must not be mailed: " + url);
    }
    for (String listed : new String[] {"BAS", "SR", "SRA", "PRO", "AUX", "TRA", "CAN"}) {
      assertTrue(url.contains("&" + listed + "=1"),
                 listed + " is listed on the tab, so it must be mailed: " + url);
    }
  }

  /** The limit has to actually remove somebody, or the tests above prove nothing. */
  @Test
  void theDayShiftLimitIsDoingSomething() throws Exception {
    Set<String> everyone = namesListed(listPatrollers(EVERYBODY));
    Set<String> underTheLimit =
      namesListed(listPatrollers(EVERYBODY + WHOLE_RANGE + "&DAY_CNT=1&MIN_DAYS=1&MinDays=1"));

    assertFalse(everyone.isEmpty(), "the sample roster should list somebody");
    assertTrue(underTheLimit.size() < everyone.size(),
               "'less than 1 day shift' should exclude the patrollers who worked one: "
                 + underTheLimit.size() + " of " + everyone.size());
  }

  private void assertListsMatch(String query) throws Exception {
    String page = listPatrollers(query);
    Set<String> listed = namesListed(page);

    String emailPage = follow(emailButtonUrl(page));
    Set<String> mailed = namesToBeMailed(emailPage);

    assertEquals(listed, mailed,
                 "the email list should hold exactly the patrollers the page listed."
                   + "\n  listed but not mailed: " + missing(listed, mailed)
                   + "\n  mailed but not listed: " + missing(mailed, listed));
  }

  private Set<String> missing(Set<String> from, Set<String> other) {
    Set<String> only = new LinkedHashSet<>(from);
    only.removeAll(other);
    return only;
  }

  private String listPatrollers(String query) throws Exception {
    return follow("ListPatrollers?resort=Sample&ID=" + DIRECTOR_ID + query);
  }

  private String follow(String url) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(get("/" + url).header("User-Agent", BROWSER).session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  /**
   * Whatever "email THESE patrollers" would navigate to. Matched on the URL rather than on the
   * button's label, because th:attr appends data-url after the hand-written attributes.
   */
  private String emailButtonUrl(String page) {
    Matcher matcher = Pattern.compile("data-url=\"(EmailForm\\?[^\"]+)\"").matcher(page);
    assertTrue(matcher.find(), "the page should carry the email button: " + page);
    return unescape(matcher.group(1));
  }

  /** Only the handful of entities Thymeleaf emits here; not worth a dependency. */
  private String unescape(String html) {
    return html.replace("&#39;", "'")
      .replace("&quot;", "\"")
      .replace("&nbsp;", " ")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&amp;", "&");
  }

  /** "Last, First" out of the first cell of every member row. */
  private Set<String> namesListed(String page) {
    Set<String> names = new LinkedHashSet<>();
    Matcher matcher = Pattern.compile("<tr>\\s*<td>([^<]+)</td>").matcher(page);

    while (matcher.find()) {
      names.add(unescape(matcher.group(1)).trim());
    }
    return names;
  }

  /** "Last, First" out of the first cell of every row on the MemberList tab. */
  private Set<String> namesOnTheTab(String page) {
    return namesListed(page);
  }

  /** Both recipient boxes: valid addresses and the skipped ones, which are still "listed". */
  private Set<String> namesToBeMailed(String page) {
    Set<String> names = new LinkedHashSet<>();
    Matcher matcher = Pattern.compile("<option[^>]*>\\s*([^<]+?)\\s*</option>").matcher(page);

    while (matcher.find()) {
      names.add(unescape(matcher.group(1)).trim());
    }
    return names;
  }

  /**
   * The Patrollers tab's "Download to Excel" button must pick the same people as the page, for
   * exactly the reason the email button must: they are two buttons over one list, and they drifted
   * three times when they were built from separate strings.
   *
   * <p>Both URLs are now built from one selection string, so this asserts they still are - the
   * cheapest guard against somebody adding a filter to one and not the other.
   */
  @Test
  void theDownloadButtonSelectsTheSamePeopleAsTheEmailButton() throws Exception {
    for (boolean fullRoster : new boolean[] {false, true}) {
      String page = memberListPage(fullRoster);

      String emailUrl = urlFromButton(page, "EmailForm\\?[^\"]+");
      String downloadUrl = urlFromButton(page, "DownloadRoster\\?[^\"]+");

      //The download asks for the same people AND for columns, which the email has no use for -
      //so this is "starts with" rather than "equals". Insisting they were identical is what left
      //the download asking for no columns at all and producing a sheet of nothing but names.
      String audience = emailUrl.substring(emailUrl.indexOf('&'));
      String downloadQuery = downloadUrl.substring(downloadUrl.indexOf('&'));

      assertTrue(downloadQuery.startsWith(audience),
                 (fullRoster ? "full" : "trimmed")
                   + " roster: the two buttons must select the same members"
                   + "\n  email   =" + audience + "\n  download=" + downloadQuery);

      for (String extra : downloadQuery.substring(audience.length()).split("&amp;")) {
        assertTrue(extra.isEmpty() || COLUMN_PARAMETERS.contains(extra.split("=")[0]),
                   "the download may add columns, nothing else: " + extra);
      }
    }
  }

  /** Column parameters DownloadRoster understands; anything else changes who is selected. */
  private static final List<String> COLUMN_PARAMETERS =
    List.of("CLASS", "COMMIT", "HOME", "WORK", "CELL", "EMAIL", "SHOW_ID", "SPOUSE", "ADDR",
            "CITY", "STATE", "ZIP", "PAGER", "EMERGENCY", "SUBSTITUTE", "INSTRUCTOR", "DIRECTOR",
            "LAST_UPDATED", "COMMENTS", "TEAM_LEAD", "MENTORING", "NAME");

  /**
   * The bug this file exists to catch, in its other half: the spreadsheet must hold the columns
   * the page drew, not just the right people.
   *
   * <p>DownloadRoster builds its sheet from the column parameters in the URL, and MemberList's
   * button sent none - so it selected exactly the right members and gave you a workbook of
   * nothing but their names. The people-match test above passed throughout.
   */
  @Test
  void theDownloadHoldsTheColumnsTheTabShows() throws Exception {
    String page = memberListPage(false);
    String downloadUrl = urlFromButton(page, "DownloadRoster\\?[^\"]+").replace("&amp;", "&");

    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    byte[] xlsx = mockMvc.perform(get("/" + downloadUrl)
                                    .header("User-Agent", BROWSER).session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

    List<String> headings = new ArrayList<>();
    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
      //row 0 is the workbook title, row 1 the headings
      for (Cell cell : workbook.getSheetAt(0).getRow(1)) {
        headings.add(cell.getStringCellValue());
      }
    }

    //the Patrollers tab draws Name, Class, Commitment, Home, Work, Cell, Email - the exporter
    //decides their order, so this checks they are all there rather than what order they are in
    for (String heading : List.of("Name", "Class", "Commitment", "Home", "Work", "Cell", "Email")) {
      assertTrue(headings.contains(heading),
                 "the download is missing the " + heading + " column: " + headings);
    }
  }

  /** The button is there for an ordinary member, not only for a director. */
  @Test
  void everyMemberIsOfferedTheDownload() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", MEMBER_ID);
    session.setAttribute("loggedInResort", "Sample");

    String asMember = mockMvc.perform(get("/MemberList")
                                        .param("resort", "Sample").param("ID", MEMBER_ID)
                                        .header("User-Agent", BROWSER).session(session))
                             .andExpect(status().isOk())
                             .andReturn().getResponse().getContentAsString();

    assertTrue(asMember.contains("Download to Excel"),
               "the Patrollers tab should offer the download to any signed-in member");
  }

  private String memberListPage(boolean fullRoster) throws Exception {
    return follow("MemberList?resort=Sample&ID=" + DIRECTOR_ID
                    + (fullRoster ? "&fullRoster=true" : ""));
  }

  private static String urlFromButton(String page, String pattern) {
    java.util.regex.Matcher found = java.util.regex.Pattern.compile(pattern).matcher(page);
    assertTrue(found.find(), "the page should carry a " + pattern + " button");
    return found.group();
  }
}
