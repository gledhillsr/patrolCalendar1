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
 * The Patrollers tab toggles between its trimmed list and the whole roster. The button's label
 * says where it goes, not where you are: "Show FULL roster" while trimmed, "Show Trimmed Roster"
 * while full, with a "?" beside it then to say what trimming leaves out.
 *
 * Whichever list is on screen, "email THESE patrollers" has to offer exactly those people - the
 * invariant that made this tab mail inactive members once already.
 *
 * Read-only; nothing here writes.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class MemberListRosterToggleTest {

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

  @Test
  void theTrimmedTabOffersTheFullRosterAndNoHelpDot() throws Exception {
    String page = tab(false);

    assertTrue(page.contains("value=\"Show FULL roster\""), "the toggle should offer the full list");
    assertFalse(page.contains("class=\"help-dot\""),
                "the ? belongs beside Show Trimmed Roster, not here");
    assertTrue(page.contains("fullRoster=true"), "and the button should link to the full list");
  }

  @Test
  void theFullTabOffersTheTrimmedRosterAndExplainsIt() throws Exception {
    String page = tab(true);

    assertTrue(page.contains("value=\"Show Trimmed Roster\""), "the toggle should offer trimming");
    assertTrue(page.contains("class=\"help-dot\""), "with a ? beside it");
    assertTrue(page.contains("Trimmed roster does not show Inactive"),
               "whose popup says what trimming leaves out: " + page);
    assertTrue(page.contains("Paid and Alumni are controlled by the boxes above"),
               "and mentions the boxes by the names they now carry");
  }

  /** The whole point: the full list holds people the trimmed one hides. */
  @Test
  void theFullRosterHoldsMoreThanTheTrimmedOne() throws Exception {
    Set<String> trimmed = names(tab(false));
    Set<String> full = names(tab(true));

    assertTrue(full.size() > trimmed.size(),
               "full should be larger: " + trimmed.size() + " vs " + full.size());
    assertTrue(full.containsAll(trimmed), "and a superset of it");
  }

  @Test
  void theEmailListMatchesWhicheverRosterIsShown() throws Exception {
    for (boolean full : new boolean[] {false, true}) {
      String page = tab(full);
      Set<String> listed = names(page);
      Set<String> mailed = optionText(follow(emailUrl(page)));

      assertEquals(listed, mailed,
                   (full ? "full" : "trimmed") + " roster: the email list should match the page"
                     + "\n  listed not mailed: " + minus(listed, mailed)
                     + "\n  mailed not listed: " + minus(mailed, listed));
    }
  }

  /** Anything but a literal "true" shows the conservative list. */
  @Test
  void aMangledParameterFallsBackToTrimmed() throws Exception {
    String page = perform(get("/MemberList")
                            .param("resort", "Sample")
                            .param("ID", DIRECTOR_ID)
                            .param("fullRoster", "yes please"));

    assertTrue(page.contains("value=\"Show FULL roster\""), "should still be the trimmed list");
  }

  private String tab(boolean full) throws Exception {
    var builder = get("/MemberList").param("resort", "Sample").param("ID", DIRECTOR_ID);
    if (full) {
      builder = builder.param("fullRoster", "true");
    }
    return perform(builder);
  }

  private String emailUrl(String page) {
    Matcher matcher = Pattern.compile("data-url=\"(EmailForm\\?[^\"]+)\"").matcher(page);
    assertTrue(matcher.find(), "the page should carry the email button");
    return matcher.group(1).replace("&amp;", "&");
  }

  private String follow(String url) throws Exception {
    return perform(get("/" + url));
  }

  private String perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
      throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    return mockMvc.perform(b.header("User-Agent", BROWSER).session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  /** "Last, First" from the first cell of each member row. */
  private Set<String> names(String page) {
    Set<String> found = new LinkedHashSet<>();
    Matcher matcher = Pattern.compile("<tr>\\s*<td>([^<]+)</td>").matcher(page);
    while (matcher.find()) {
      found.add(matcher.group(1).trim());
    }
    return found;
  }

  /** Both recipient boxes on EmailForm - valid addresses and the skipped ones. */
  private Set<String> optionText(String page) {
    Set<String> found = new LinkedHashSet<>();
    Matcher matcher = Pattern.compile("<option[^>]*>\\s*([^<]+?)\\s*</option>").matcher(page);
    while (matcher.find()) {
      found.add(matcher.group(1).trim());
    }
    return found;
  }

  private Set<String> minus(Set<String> from, Set<String> other) {
    Set<String> only = new LinkedHashSet<>(from);
    only.removeAll(other);
    return only;
  }
}
