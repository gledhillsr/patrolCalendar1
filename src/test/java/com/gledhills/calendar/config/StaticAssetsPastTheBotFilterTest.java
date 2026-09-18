package com.gledhills.calendar.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A stylesheet, script or icon is served whatever asks for it; a page is not.
 *
 * <p>Real members were getting a 403 for {@code Injury_icon.svg}, which is the site's favicon.
 * Browsers fetch a favicon through a background icon loader that often sends <b>no User-Agent</b>,
 * and {@code isRequestFromBot} reads a missing User-Agent as a script - correctly, for a page.
 * The tab simply showed no icon and nothing said why.
 *
 * <p>The missing-User-Agent rule itself is deliberately left alone: it is what keeps scanners off
 * the application, and weakening it to rescue an icon would be the wrong trade. Assets are let
 * past instead, which costs nothing - a crawler reading a stylesheet is harmless, and a search
 * engine needs the CSS and JS to render the page it is indexing.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class StaticAssetsPastTheBotFilterTest {

  private static final String CRAWLER = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

  @Autowired
  private WebApplicationContext webApplicationContext;

  @Autowired
  private BotBlockingFilter botBlockingFilter;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    //webAppContextSetup does NOT pick up Boot's registered filters, so without this every
    //assertion here passes whatever the filter does - which is the one way this test could be
    //worthless. pagesAreStillBlocked is what catches its absence.
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                             .addFilters(botBlockingFilter).build();
  }

  /** The reported failure: the favicon, fetched with no User-Agent at all. */
  @Test
  void theFaviconIsServedToARequestWithNoUserAgent() throws Exception {
    mockMvc.perform(get("/Injury_icon.svg")).andExpect(status().isOk());
  }

  /**
   * The icons live at the root of the static folder, so no directory prefix would have caught
   * them - which is why the rule is on the extension.
   */
  @Test
  void theOtherIconsAreServedToo() throws Exception {
    mockMvc.perform(get("/favicon-32.png")).andExpect(status().isOk());
    mockMvc.perform(get("/apple-touch-icon.png")).andExpect(status().isOk());
  }

  @Test
  void stylesheetsAndScriptsAreServedWithNoUserAgent() throws Exception {
    mockMvc.perform(get("/css/header.css")).andExpect(status().isOk());
    mockMvc.perform(get("/js/shiftTime.js")).andExpect(status().isOk());
  }

  /** An asset is served even to something that plainly is a crawler; it is not worth blocking. */
  @Test
  void anAssetIsServedEvenToACrawler() throws Exception {
    mockMvc.perform(get("/Injury_icon.svg").header("User-Agent", CRAWLER))
           .andExpect(status().isOk());
  }

  /**
   * The other half, and the one that matters: letting assets past must not let a crawler at a
   * page. These are the requests the filter exists for.
   */
  @Test
  void pagesAreStillBlocked() throws Exception {
    mockMvc.perform(get("/MonthCalendar").param("resort", "Sample").header("User-Agent", CRAWLER))
           .andExpect(status().isForbidden());

    //no User-Agent at all, which is what a script looks like
    mockMvc.perform(get("/MonthCalendar").param("resort", "Sample"))
           .andExpect(status().isForbidden());
  }
}
