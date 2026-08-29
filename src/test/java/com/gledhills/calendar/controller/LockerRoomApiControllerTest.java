package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.gledhills.calendar.config.BotBlockingFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The locker-room API is the one endpoint reached by a script rather than a browser, so the two
 * things worth pinning are that it will not answer without the shared key, and that the bot filter
 * does not eat it first.
 *
 * <p>A request with no User-Agent is exactly what PHP sends by default, and every other path
 * answers that with 403 "Automated requests are not allowed." These tests assert this one does not,
 * because that failure looked like a broken endpoint rather than a filter when it happened.
 *
 * <p>Read-only: no request here reaches the database - they are all turned away before that.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class LockerRoomApiControllerTest {

  private static final String ASSIGNMENTS = "/api/locker/assignments";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private BotBlockingFilter botBlockingFilter;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    //webAppContextSetup() alone installs no servlet filters, so the bot filter has to be added
    //explicitly - without this the two filter tests below pass for the wrong reason
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(botBlockingFilter).build();
  }

  /**
   * Missing key must never fall through to the data. 401 when a key is configured on this server,
   * 503 when one is not - both refuse, which is the property being asserted.
   */
  @Test
  void refusesARequestWithNoKey() throws Exception {
    MvcResult result = mockMvc.perform(get(ASSIGNMENTS)
                                         .param("resort", "Sample")
                                         .header("User-Agent", BROWSER))
      .andReturn();
    int status = result.getResponse().getStatus();
    assertTrue(status == 401 || status == 503,
               "no key must be refused, got " + status + ": " + result.getResponse().getContentAsString());
  }

  @Test
  void refusesAWrongKey() throws Exception {
    MvcResult result = mockMvc.perform(get(ASSIGNMENTS)
                                         .param("resort", "Sample")
                                         .header("User-Agent", BROWSER)
                                         .header("X-Locker-Key", "not-the-key"))
      .andReturn();
    int status = result.getResponse().getStatus();
    assertTrue(status == 401 || status == 503,
               "a wrong key must be refused, got " + status);
  }

  /**
   * PHP's HTTP client sends no User-Agent, which BotBlockingFilter treats as a script and answers
   * with 403 "Automated requests are not allowed." This endpoint is exempt, so the request must
   * reach the controller and be judged on its key instead.
   */
  @Test
  void botFilterDoesNotBlockThisEndpoint() throws Exception {
    MvcResult result = mockMvc.perform(get(ASSIGNMENTS).param("resort", "Sample")).andReturn();
    assertEquals(false, result.getResponse().getStatus() == 403,
                 "the bot filter must let the locker room API through");
    assertTrue(!result.getResponse().getContentAsString().contains("Automated requests"),
               "response came from BotBlockingFilter, not the controller");
  }

  private static final String ROSTER = "/api/locker/roster";
  private static final String SKIHISTORY = "/api/locker/skihistory";

  @Test
  void rosterRefusesARequestWithNoKey() throws Exception {
    MvcResult result = mockMvc.perform(get(ROSTER)
                                         .param("resort", "Sample")
                                         .header("User-Agent", BROWSER))
      .andReturn();
    int status = result.getResponse().getStatus();
    assertTrue(status == 401 || status == 503, "no key must be refused, got " + status);
  }

  /**
   * The upload can delete rows on this server, so an unauthenticated caller must not get as far
   * as the database even with a well formed body.
   */
  @Test
  void skiHistoryUploadRefusesARequestWithNoKey() throws Exception {
    MvcResult result = mockMvc.perform(post(SKIHISTORY)
                                         .param("resort", "Sample")
                                         .header("User-Agent", BROWSER)
                                         .contentType(MediaType.APPLICATION_JSON)
                                         .content("{\"rows\":[]}"))
      .andReturn();
    int status = result.getResponse().getStatus();
    assertTrue(status == 401 || status == 503, "no key must be refused, got " + status);
  }

  /** POST is the only verb that writes; the read endpoints must not accept one. */
  @Test
  void assignmentsDoesNotAcceptAPost() throws Exception {
    MvcResult result = mockMvc.perform(post(ASSIGNMENTS)
                                         .param("resort", "Sample")
                                         .header("User-Agent", BROWSER))
      .andReturn();
    assertEquals(405, result.getResponse().getStatus(),
                 "a read endpoint must not answer POST");
  }

  /** A browser hitting some other path with no User-Agent should still be blocked. */
  @Test
  void botFilterStillBlocksEverythingElse() throws Exception {
    MvcResult result = mockMvc.perform(get("/MonthCalendar").param("resort", "Sample")).andReturn();
    assertEquals(403, result.getResponse().getStatus(),
                 "exempting the API must not have opened up the rest of the site");
  }
}
