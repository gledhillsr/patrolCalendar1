package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Roster;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pro and Alumni are opt-in on the Patrollers tab, one checkbox each, and the list carries
 * Classification and Commitment columns.
 *
 * The sample roster holds no PRO or ALM member, so one member is reclassified for the duration of
 * each test and put back afterwards - without that the row counts would not move and the tests
 * would prove nothing. Brighton, by contrast, has 25 Pro and 12 Alumni.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class MemberListProAlumniTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String GUINEA_PIG = "200401";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;
  private String originalClassification;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    originalClassification = classificationOf(GUINEA_PIG);
  }

  @AfterEach
  void restore() {
    setClassification(GUINEA_PIG, originalClassification);
    assertEquals(originalClassification, classificationOf(GUINEA_PIG), "the roster must be put back");
  }

  @Test
  void theCheckboxesAreBetweenTheEmailAndToggleButtons() throws Exception {
    String page = page();

    int email = page.indexOf("email THESE patrollers");
    int pro = page.indexOf("name=\"pro\"");
    int alumni = page.indexOf("name=\"alumni\"");
    int toggle = page.indexOf("Show FULL roster");

    assertTrue(email < pro && pro < alumni && alumni < toggle,
               "order should be email, Pro, Alumni, toggle: " + email + "," + pro + "," + alumni
                 + "," + toggle);
  }

  /** Pro starts ticked, so the default view is what it always was. */
  @Test
  void aProIsListedByDefaultAndDroppedWhenUnticked() throws Exception {
    setClassification(GUINEA_PIG, "PRO");

    assertTrue(names(page()).contains(nameOf(GUINEA_PIG)), "a Pro is on the default list");
    assertTrue(names(page("filters", "1", "pro", "true")).contains(nameOf(GUINEA_PIG)),
               "and stays when the box is ticked");
    assertFalse(names(page("filters", "1")).contains(nameOf(GUINEA_PIG)),
                "but goes when it is unticked - the form posts no 'pro' at all then");
  }

  @Test
  void anAlumnusIsHiddenUntilTheAlumniBoxIsTicked() throws Exception {
    setClassification(GUINEA_PIG, "ALM");

    assertFalse(names(page()).contains(nameOf(GUINEA_PIG)), "an Alumnus is off the default list");
    assertTrue(names(page("filters", "1", "alumni", "true")).contains(nameOf(GUINEA_PIG)),
               "and on it once ticked");
  }

  /** The invariant this tab keeps breaking: the email button must offer who is on screen. */
  @Test
  void theEmailListFollowsTheCheckboxes() throws Exception {
    setClassification(GUINEA_PIG, "PRO");

    for (String[] params : new String[][] {{}, {"filters", "1"},
                                           {"filters", "1", "pro", "true"},
                                           {"filters", "1", "alumni", "true"},
                                           {"filters", "1", "pro", "true", "alumni", "true"}}) {
      String page = page(params);
      Set<String> listed = names(page);
      Set<String> mailed = optionText(follow(emailUrl(page)));

      assertEquals(listed, mailed, "with " + String.join("=", params) + ": email should match page"
                     + "\n  listed not mailed: " + minus(listed, mailed)
                     + "\n  mailed not listed: " + minus(mailed, listed));
    }
  }

  @Test
  void theListShowsClassificationAndCommitment() throws Exception {
    String page = page();

    assertTrue(page.contains(">Class</font>"), "there should be a Class column: " + page);
    assertTrue(page.contains(">Commitment</font>"), "and a Commitment column");
    //the name, not the stored code: BAS is what the row holds, Alpine is what a director reads
    assertTrue(page.contains("<td>Alpine</td>"), "with classifications spelled out in it");
    assertFalse(page.contains("<td>BAS</td>"), "and not the three-letter code");
    assertTrue(page.contains("<td>Full Time</td>") || page.contains("<td>Part Time</td>"),
               "and readable commitment labels");
  }

  /** A ticked box has to come back ticked, or the reload loses the choice. */
  /** Pro ticked out of the box; a box's state has to survive the reload it triggers. */
  @Test
  void theBoxesComeBackAsTheyWereLeft() throws Exception {
    String fresh = page();
    assertTrue(Pattern.compile("name=\"pro\"[^>]*checked").matcher(fresh).find(),
               "Pro should start ticked: " + fresh);
    assertFalse(Pattern.compile("name=\"alumni\"[^>]*checked").matcher(fresh).find(),
                "Alumni should start unticked");

    String unticked = page("filters", "1");
    assertFalse(Pattern.compile("name=\"pro\"[^>]*checked").matcher(unticked).find(),
                "Pro should come back unticked once it has been cleared");

    String both = page("filters", "1", "pro", "true", "alumni", "true");
    assertTrue(Pattern.compile("name=\"alumni\"[^>]*checked").matcher(both).find(),
               "Alumni should come back ticked");
  }

  private String page(String... params) throws Exception {
    MockHttpServletRequestBuilder builder = get("/MemberList")
      .param("resort", "Sample").param("ID", DIRECTOR_ID);
    for (int i = 0; i < params.length; i += 2) {
      builder = builder.param(params[i], params[i + 1]);
    }
    return perform(builder);
  }

  private String follow(String url) throws Exception {
    return perform(get("/" + url));
  }

  private String perform(MockHttpServletRequestBuilder builder) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    return mockMvc.perform(builder.header("User-Agent", BROWSER).session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  private String emailUrl(String page) {
    Matcher matcher = Pattern.compile("data-url=\"(EmailForm\\?[^\"]+)\"").matcher(page);
    assertTrue(matcher.find(), "the page should carry the email button");
    return matcher.group(1).replace("&amp;", "&");
  }

  private Set<String> names(String page) {
    Set<String> found = new LinkedHashSet<>();
    Matcher matcher = Pattern.compile("<tr>\\s*<td>([^<]+)</td>").matcher(page);
    while (matcher.find()) {
      found.add(matcher.group(1).trim());
    }
    return found;
  }

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

  private String nameOf(String id) {
    List<String> name = new ArrayList<>();
    withPatrolData((log, patrolData) ->
                     name.add(patrolData.getMemberByID(id).getFullName_lastNameFirst()));
    return name.get(0);
  }

  private String classificationOf(String id) {
    List<String> value = new ArrayList<>();
    withPatrolData((log, patrolData) ->
                     value.add(patrolData.getMemberByID(id).getClassification()));
    return value.get(0);
  }

  private void setClassification(String id, String code) {
    withPatrolData((log, patrolData) -> {
      try (java.sql.Statement statement = patrolData.getConnection().createStatement()) {
        statement.executeUpdate("UPDATE roster SET ClassificationCode = '" + code
                                  + "' WHERE IDNumber = " + id);
      }
      catch (java.sql.SQLException e) {
        throw new IllegalStateException("could not reclassify " + id, e);
      }
    });
  }

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    request.setSession(session);
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(MemberListProAlumniTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
    }
    finally {
      patrolData.close();
    }
  }
}
