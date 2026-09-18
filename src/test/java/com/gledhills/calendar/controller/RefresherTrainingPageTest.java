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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Refresher and Training page.
 *
 * <p>Not a test of the prose - that is rewritten every season and pinning it would only mean
 * editing this file too. What is pinned is the part that rots silently: the three standing links a
 * member comes back for all season, the enrollment PDF the page promises, and the fact that the
 * page renders at all. Its expressions include {@code @{/documents/...}} and
 * {@code ${@site.hostedContentBase}}, and a Thymeleaf expression that throws takes the whole page
 * down rather than dropping one line.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class RefresherTrainingPageTest {

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

  @Test
  void itRendersForASignedInMember() throws Exception {
    String page = page();

    assertTrue(page.contains("Refresher and Training information"), "the page should render: " + page);
  }

  /**
   * The three older links that outlive any one season. Each is somebody's reason for opening this
   * page, and a rewrite of the season's notice is exactly when one would get dropped.
   */
  @Test
  void theThreeOlderLinksAreKept() throws Exception {
    String page = page();

    assertTrue(page.contains("OLD online refresher"),
               "the 2021 online refresher should still be offered");
    assertTrue(page.contains("training/index.php?id=" + MEMBER_ID),
               "and its URL is still built for this member, since that is how it tracks progress");

    assertTrue(page.contains("Inspiring Donations"), "the Smith's donations link should be there");
    assertTrue(page.contains("BrightonSmiths_4.20222.pdf"), "pointing at the donation PDF");

    assertTrue(page.contains("Know Before You Go"), "the avalanche awareness link should be there");
    assertTrue(page.contains("xvVSpyec5LM"), "pointing at the video");
  }

  /** A page that promises an attachment and does not serve one is worse than no attachment. */
  @Test
  void theEnrollmentInstructionsAreLinkedAndServed() throws Exception {
    assertTrue(page().contains("/documents/Refresher_Enroll_Instructions.pdf"),
               "the page should link the enrollment instructions");

    mockMvc.perform(get("/documents/Refresher_Enroll_Instructions.pdf").header("User-Agent", BROWSER))
           .andExpect(status().isOk());
  }

  /** A visitor with no session is sent to log in rather than shown a member's personal link. */
  @Test
  void aVisitorIsSentToLogIn() throws Exception {
    mockMvc.perform(get("/RefresherTraining").param("resort", "Sample").header("User-Agent", BROWSER))
           .andExpect(status().is3xxRedirection());
  }

  private String page() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", MEMBER_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(get("/RefresherTraining")
                             .param("resort", "Sample")
                             .param("ID", MEMBER_ID)
                             .header("User-Agent", BROWSER)
                             .session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }
}
