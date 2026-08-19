package com.gledhills.calendar.controller;

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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Display all assignments" heads each season's shifts with a separator naming it, e.g.
 * "2019-2020 Season". A season runs 1 July to 30 June, so shifts either side of New Year belong
 * to the same one - which is the whole reason a plain year would not do.
 *
 * Member 200401 in the sample data has shifts in Nov/Dec 2019, Mar 2020, Mar 2021, Oct/Nov 2022
 * and Feb 2025 - four seasons, one of which straddles a year end. Read-only; nothing here writes.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ListAssignmentsSeasonSeparatorTest {

  private static final String MEMBER_ID = "200401";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /** A season starts on 1 July, so June and July of the same calendar year differ. */
  @Test
  void seasonsRunFromJulyToJune() {
    assertEquals("2024-2025 Season", ListAssignmentsController.seasonLabel(2024, 7),
                 "1 July opens a new season");
    assertEquals("2023-2024 Season", ListAssignmentsController.seasonLabel(2024, 6),
                 "June still belongs to the season that opened the previous July");
    assertEquals("2024-2025 Season", ListAssignmentsController.seasonLabel(2024, 12),
                 "December is the first half of its season");
    assertEquals("2024-2025 Season", ListAssignmentsController.seasonLabel(2025, 1),
                 "and January the second half of the same one");
  }

  @Test
  void everySeasonIsLabelledOnceInDateOrder() throws Exception {
    List<String> seasons = separators(allAssignments());

    assertEquals(List.of("2019-2020 Season", "2020-2021 Season",
                         "2022-2023 Season", "2024-2025 Season"),
                 seasons,
                 "one separator per season, oldest first");
  }

  /** The point of a July cut-off: Nov 2019, Dec 2019 and Mar 2020 are one season, not two. */
  @Test
  void shiftsEitherSideOfNewYearShareASeason() throws Exception {
    String page = allAssignments();

    int firstSeason = page.indexOf("2019-2020 Season");
    int secondSeason = page.indexOf("2020-2021 Season");
    int march2020 = dateIndex(page, "March", 2020);

    assertTrue(march2020 > 0, "the sample data should hold a March 2020 shift");
    assertTrue(march2020 > firstSeason && march2020 < secondSeason,
               "March 2020 belongs under the 2019-2020 heading, not its own");
  }

  /** The separator heads its season's shifts rather than trailing them. */
  @Test
  void aSeparatorComesBeforeTheShiftsItLabels() throws Exception {
    String page = allAssignments();

    assertTrue(page.indexOf("2024-2025 Season") < dateIndex(page, "February", 2025),
               "the 2024-2025 heading should precede its February 2025 shift");
    assertTrue(page.indexOf("2019-2020 Season") < page.indexOf("2020-2021 Season"),
               "and seasons should read oldest first");
  }

  /** Current season only is a single season, so there is nothing to separate. */
  @Test
  void currentSeasonOnlyHasNoSeparators() throws Exception {
    String page = mockMvc.perform(get("/ListAssignments")
                                    .param("resort", "Sample")
                                    .param("ID", MEMBER_ID)
                                    .header("User-Agent", BROWSER)
                                    .session(session()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertTrue(separators(page).isEmpty(),
               "no season headings when only one season is shown: " + separators(page));
  }

  private String allAssignments() throws Exception {
    return mockMvc.perform(get("/ListAssignments")
                             .param("resort", "Sample")
                             .param("ID", MEMBER_ID)
                             .param("DisplayAllAssignments", "true")
                             .header("User-Agent", BROWSER)
                             .session(session()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  /** Where a date first appears. The page writes them as "Sunday, March 8 2020". */
  private int dateIndex(String page, String monthName, int year) {
    Matcher matcher = Pattern.compile(monthName + " \\d+ " + year).matcher(page);
    return matcher.find() ? matcher.start() : -1;
  }

  /** The season headings, in the order the page lists them. */
  private List<String> separators(String page) {
    List<String> found = new ArrayList<>();
    Matcher matcher = Pattern.compile("class=\"season-separator\"[^>]*>([^<]+)<").matcher(page);

    while (matcher.find()) {
      found.add(matcher.group(1).trim());
    }
    return found;
  }

  private MockHttpSession session() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", MEMBER_ID);
    session.setAttribute("loggedInResort", "Sample");
    return session;
  }
}
