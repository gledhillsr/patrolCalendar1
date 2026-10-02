package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.TextCompositionForm;
import com.gledhills.calendar.utils.StaticUtils;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Text Message THESE patrollers" - the page, its gate, and the button that reaches it.
 *
 * <p><b>Nothing here sends anything</b>, and nothing can: there is no SMS provider wired to the
 * application. That is the point of the page as it stands, and the first test pins it - a future
 * change that quietly starts sending would otherwise pass every other assertion here.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class TextFormTest {

  private static final String OWNER_ID = "192443";
  private static final String OWNER_RESORT = "Brighton";
  private static final String DIRECTOR_ID = "123456";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  // ----- what it is -------------------------------------------------------------------------

  @Test
  void thePageSaysPlainlyThatNothingIsSent() throws Exception {
    String page = ownerPage();

    assertTrue(page.contains("Nothing is sent from this page yet"),
               "the banner has to be unmissable: " + page);
    assertTrue(page.contains("Preview - nothing is sent"),
               "and the button must not say Send");
  }

  /** The audience is split by whether the number can actually be dialled, and the page says so. */
  @Test
  void itSeparatesWhoCanBeTextedFromWhoCannot() throws Exception {
    String page = ownerPage();

    assertTrue(page.contains("No mobile number on the roster"), "the second column is named");
    assertTrue(page.contains("have no usable number"),
               "and the count is stated, so a shorter list is never mistaken for everybody");
  }

  // ----- who may see it ---------------------------------------------------------------------

  @Test
  void theSiteOwnerMaySeeIt() throws Exception {
    mockMvc.perform(request(OWNER_RESORT, OWNER_ID)).andExpect(status().isOk());
  }

  /** A director is not the site owner. The feature is being built, not released. */
  @Test
  void anotherDirectorMayNot() throws Exception {
    mockMvc.perform(request("Sample", DIRECTOR_ID)).andExpect(status().isForbidden());
  }

  /** The owner's own id at somebody else's resort is still not the owner. */
  @Test
  void theOwnerIdAtAnotherResortMayNot() throws Exception {
    mockMvc.perform(request("Sample", OWNER_ID)).andExpect(status().isForbidden());
  }

  /**
   * The gate reads the session, never the ID parameter - otherwise it is not a gate, it is a
   * suggestion.
   */
  @Test
  void aTypedIdParameterDoesNotOpenIt() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    mockMvc.perform(get("/TextForm").param("resort", "Sample").param("ID", OWNER_ID)
                      .header("User-Agent", BROWSER).session(session))
           .andExpect(status().isForbidden());
  }

  /** The POST is gated too; a page whose GET is guarded and whose POST is not is not guarded. */
  @Test
  void thePostIsGatedAsWell() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    mockMvc.perform(post("/TextForm").param("resort", "Sample").param("ID", DIRECTOR_ID)
                      .param("message", "anything at all")
                      .header("User-Agent", BROWSER).session(session))
           .andExpect(status().isForbidden());
  }

  // ----- the button on ListPatrollers --------------------------------------------------------

  /**
   * The button is drawn for the owner and for nobody else, and its URL carries the same filters as
   * the e-mail button's - they come from one builder, so the two cannot offer different people.
   */
  @Test
  void theButtonIsDrawnForTheOwnerOnly() throws Exception {
    String ownerList = listPatrollers(OWNER_RESORT, OWNER_ID);
    assertTrue(ownerList.contains("Text Message THESE patrollers"),
               "the site owner should get the button");
    assertTrue(ownerList.contains("TextForm?resort=" + OWNER_RESORT),
               "pointing at the text form");

    assertFalse(listPatrollers("Sample", DIRECTOR_ID).contains("Text Message THESE patrollers"),
                "another resort's director should not see it at all");
  }

  @Test
  void bothButtonsCarryTheSameFilters() throws Exception {
    String page = listPatrollers(OWNER_RESORT, OWNER_ID);

    String emailUrl = urlFrom(page, "EmailForm?");
    String textUrl = urlFrom(page, "TextForm?");

    assertEquals(emailUrl.substring(emailUrl.indexOf('?')), textUrl.substring(textUrl.indexOf('?')),
                 "everything after the ? must be identical, or the two buttons select different"
                   + " people:\n  email=" + emailUrl + "\n  text =" + textUrl);
  }

  // ----- the length cap ----------------------------------------------------------------------

  /**
   * 160 characters - exactly one SMS segment - enforced by the form and rendered as maxlength.
   *
   * <p>Both halves matter: maxlength alone is a browser courtesy and a form can be posted around
   * it, and a server check alone lets somebody type 400 characters before being told.
   */
  @Test
  void aMessageIsCappedAtOneSegment() throws Exception {
    assertEquals(160, TextCompositionForm.MAX_CHARACTERS);
    assertEquals(TextCompositionForm.SEGMENT_CHARACTERS, TextCompositionForm.MAX_CHARACTERS,
                 "the cap IS one segment - that is the rule, not a coincidence");
    assertTrue(ownerPage().contains("maxlength=\"160\""),
               "the compose box should stop at the cap as it is typed");
  }

  /** The counter reads "(13 of 160)", so the number that matters is the one typed so far. */
  @Test
  void theCounterShowsCharactersTypedOfTheLimit() throws Exception {
    assertTrue(ownerPage().contains("'(' + text.length + ' of ' + max + ')'"),
               "the compose box should count as (typed of limit)");
  }

  /**
   * Plain text only, so the message is one segment and stays one segment. Anything outside GSM-7
   * (an emoji, a curly quote, an accented letter) triples the cost by switching to UCS-2; the
   * GSM-7 extension characters double it. Both are refused, and the offending character is named.
   */
  @Test
  void aMessageMayContainOnlySimpleText() throws Exception {
    assertTrue("Shift change: Sat 8am-4pm (50% full). Reply to the patrol number."
                 .matches(TextCompositionForm.SIMPLE_TEXT_PATTERN),
               "an ordinary message about a shift must be allowed");

    for (String rejected : new String[] {"cafe\u0301", "don\u2019t", "5 \u2013 6", "\uD83D\uDE00 hi",
                                         "a^b", "x{y}", "p[q]", "m~n", "a|b", "c\\d", "\u20ac5"}) {
      assertFalse(rejected.matches(TextCompositionForm.SIMPLE_TEXT_PATTERN),
                  "should be refused: " + rejected);
      assertFalse(TextCompositionForm.disallowedCharactersIn(rejected).isEmpty(),
                  "and the offending character should be named: " + rejected);
    }
  }

  /** The server refuses it too, and says which character is the problem. */
  @Test
  void aMessageWithASpecialCharacterIsRefusedAndTheCharacterNamed() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", OWNER_ID);
    session.setAttribute("loggedInResort", OWNER_RESORT);

    String page = mockMvc.perform(post("/TextForm")
                                    .param("resort", OWNER_RESORT).param("ID", OWNER_ID)
                                    .param("message", "Meet at the top \u2014 bring a radio")
                                    .param("patrollerIds", OWNER_ID)
                                    .header("User-Agent", BROWSER).session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertTrue(page.contains("Not allowed in a text message"),
               "the page should name the problem: " + page);
    assertFalse(page.contains("would receive this"), "and must not preview it");
  }

  /** The server has the last word, because the browser's maxlength can be posted around. */
  @Test
  void aMessageOverTheCapIsRefused() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", OWNER_ID);
    session.setAttribute("loggedInResort", OWNER_RESORT);

    String page = mockMvc.perform(post("/TextForm")
                                    .param("resort", OWNER_RESORT).param("ID", OWNER_ID)
                                    .param("message", "X".repeat(TextCompositionForm.MAX_CHARACTERS + 1))
                                    .param("patrollerIds", OWNER_ID)
                                    .header("User-Agent", BROWSER).session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertTrue(page.contains("between 4 and 160 characters"),
               "the form should say what the limit is: " + page);
    assertFalse(page.contains("would receive this"), "and must not preview an over-long message");
  }

  // ----- the number rule ---------------------------------------------------------------------

  /**
   * What counts as a number a text can go to. Anything that is not ten digits is null rather than
   * a guess: a half-recognised number would mean a message going somewhere nobody chose.
   */
  @Test
  void onlyATenDigitNumberCanBeTexted() {
    assertEquals("+18473725936", StaticUtils.toSmsNumber("847-372-5936"));
    assertEquals("+18473725936", StaticUtils.toSmsNumber("(847) 372-5936"));
    assertEquals("+18473725936", StaticUtils.toSmsNumber("8473725936"));
    assertEquals("+18473725936", StaticUtils.toSmsNumber("1-847-372-5936"));

    assertNull(StaticUtils.toSmsNumber("372-5936"), "too short to dial");
    assertNull(StaticUtils.toSmsNumber("ext 4412"));
    assertNull(StaticUtils.toSmsNumber("&nbsp;"), "the roster's empty-field placeholder");
    assertNull(StaticUtils.toSmsNumber(""));
    assertNull(StaticUtils.toSmsNumber(null));
  }

  // ----- helpers -----------------------------------------------------------------------------

  private String urlFrom(String page, String marker) {
    int at = page.indexOf(marker);
    assertTrue(at > 0, marker + " should be on the page");
    int start = page.lastIndexOf('"', at) + 1;
    int end = page.indexOf('"', at);
    return page.substring(start, end).replace("&amp;", "&");
  }

  private String listPatrollers(String resort, String userId) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", userId);
    session.setAttribute("loggedInResort", resort);

    return mockMvc.perform(get("/ListPatrollers")
                             .param("resort", resort).param("ID", userId)
                             .param("BAS", "1").param("SR", "1").param("ALL", "1")
                             .param("FullTime", "1").param("PartTime", "1")
                             .header("User-Agent", BROWSER).session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  private String ownerPage() throws Exception {
    return mockMvc.perform(request(OWNER_RESORT, OWNER_ID))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
    String resort, String userId) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", userId);
    session.setAttribute("loggedInResort", resort);

    return get("/TextForm").param("resort", resort).param("ID", userId)
             .param("BAS", "1").param("SR", "1").param("ALL", "1")
             .param("FullTime", "1").param("PartTime", "1")
             .header("User-Agent", BROWSER).session(session);
  }
}
