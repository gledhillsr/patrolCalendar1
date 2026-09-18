package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A POST that succeeds must redirect with <b>303 See Other</b>, not 302.
 *
 * <p>RFC 9110 lets a client receiving a 302 repeat the original method at the new location.
 * Browsers turn it into a GET by convention, but webviews and proxies do not all follow suit - and
 * the pages these redirects point at (MonthCalendar, MemberLogin) answer GET only, so a repeated
 * POST is a bare <b>405</b> shown to a patroller at the moment they thought they had signed in.
 * 303 removes the choice.
 *
 * <p>The 405 is real and one line away: this pins the POST endpoints returning 405 as well, so
 * nobody "fixes" the symptom by adding a POST mapping to a page that has no business taking one.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class PostRedirectIsSeeOtherTest {

  //the Sample demo credentials, which the login page prints on itself for this resort
  private static final String MEMBER_ID = "111111";
  private static final String PASSWORD = "patrolCalendar";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /** The one that bit: a successful login redirects to a page that only answers GET. */
  @Test
  void aSuccessfulLoginRedirectsWithSeeOther() throws Exception {
    MvcResult result = mockMvc.perform(post("/LoginHelp")
                                         .param("resort", "Sample")
                                         .param("NSPgoto", "MonthCalendar")
                                         .param("ID", MEMBER_ID)
                                         .param("Password", PASSWORD)
                                         .param("submit1", "Login")
                                         .header("User-Agent", BROWSER))
      .andReturn();

    assertEquals(303, result.getResponse().getStatus(),
                 "a POST that succeeded must say See Other, or a client may repeat the POST");

    String location = result.getResponse().getHeader("Location");
    assertNotNull(location, "and it still has to say where to go");
    assertTrue(location.contains("MonthCalendar"), "which is the month calendar: " + location);
  }

  /**
   * Why it matters, stated as a fact about the target rather than left to inference: the page the
   * login redirects to has no POST mapping, so a repeated POST is a 405 with nothing to explain it.
   */
  @Test
  void theRedirectTargetsAnswerGetOnly() throws Exception {
    mockMvc.perform(post("/MonthCalendar").param("resort", "Sample").header("User-Agent", BROWSER))
           .andExpect(status().isMethodNotAllowed());

    mockMvc.perform(post("/MemberLogin").param("resort", "Sample").header("User-Agent", BROWSER))
           .andExpect(status().isMethodNotAllowed());
  }

  /**
   * A redirect out of a GET has no method to preserve, so 302 is right there and is left alone.
   * Asserted so the change does not quietly spread into every redirect in the app.
   */
  @Test
  void aRedirectFromAGetIsStillFound() throws Exception {
    mockMvc.perform(get("/MonthCalendar").param("resort", "Sample").header("User-Agent", BROWSER))
           .andExpect(status().isFound());
  }
}
