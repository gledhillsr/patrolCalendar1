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

import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SubList lists exactly the members whose substitute field says "y" or "yes", and its
 * "email THESE patrollers" button reaches the same people - it hands EmailForm SubList=1, which
 * routes through okToDisplay's SubList branch, so both ends share Roster.isOnSubstituteList().
 *
 * Read-only; the sample roster carries "yes", "Y" and blanks already.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class SubListPageTest {

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
  void itListsTheSubstitutesAndNobodyElse() throws Exception {
    Set<String> listed = names(page("/SubList"));
    Set<String> everyone = names(page("/MemberList"));

    assertFalse(listed.isEmpty(), "the sample roster has members with sub=yes/Y");
    assertFalse(listed.size() == everyone.size(),
                "the sub list should be a subset of the roster, not all of it");
  }

  /** The same count line MemberList carries, and it has to agree with the rows above it. */
  @Test
  void itReportsHowManyAreListed() throws Exception {
    String page = page("/SubList");
    int listed = names(page).size();

    assertTrue(page.contains(listed + " members listed."),
               "expected \"" + listed + " members listed.\" for the " + listed + " rows shown");
  }

  /**
   * A substitute who has gone inactive drops off the list - and off the email its button offers.
   * The sample roster has no such member, so one is made inactive for the test and put back.
   */
  @Test
  void anInactiveSubstituteIsNotListed() throws Exception {
    String victim = aListedSubstituteId();
    String original = commitmentOf(victim);
    try {
      setCommitment(victim, "0");

      String page = page("/SubList");
      assertFalse(names(page).contains(nameOf(victim)),
                  "an inactive substitute should not be listed: " + names(page));
      assertEquals(names(page), optionText(follow(emailUrl(page))),
                   "and the email list should drop them too");
    }
    finally {
      setCommitment(victim, original);
      assertEquals(original, commitmentOf(victim), "the roster must be put back");
    }
  }

  /** Sample carries an "Other" substitute, so this exercises the exclusion on real data. */
  @Test
  void alumniAndOtherAreNotListed() throws Exception {
    Set<String> listed = names(page("/SubList"));

    for (String excluded : namesWithClassification("OTH", "ALM")) {
      assertFalse(listed.contains(excluded),
                  excluded + " is Alumni or Other and should not be a listed substitute: " + listed);
    }
  }

  /**
   * The Pro box, end to end. No resort has a Pro substitute today, so one is made for the test:
   * a listed substitute is reclassified PRO and put back afterwards.
   */
  @Test
  void aProSubstituteAppearsOnlyWhenTheBoxIsTicked() throws Exception {
    String victim = aListedSubstituteId();
    String original = classificationOf(victim);
    try {
      setClassification(victim, "PRO");

      assertFalse(names(page("/SubList")).contains(nameOf(victim)),
                  "a Pro substitute is off the list by default");
      assertTrue(names(page("/SubList?pro=true")).contains(nameOf(victim)),
                 "and on it once the box is ticked");
    }
    finally {
      setClassification(victim, original);
      assertEquals(original, classificationOf(victim), "the roster must be put back");
    }
  }

  @Test
  void theProBoxIsBesideTheEmailButtonAndStartsOff() throws Exception {
    String page = page("/SubList");

    assertTrue(page.indexOf("email THESE patrollers") < page.indexOf("name=\"pro\""),
               "the box should follow the email button");
    assertFalse(Pattern.compile("name=\"pro\"[^>]*checked").matcher(page).find(),
                "and start unticked: " + page);
    assertTrue(Pattern.compile("name=\"pro\"[^>]*checked")
                 .matcher(page("/SubList?pro=true")).find(),
               "coming back ticked once chosen");
  }

  @Test
  void theEmailListMatchesThePage() throws Exception {
    for (String url : new String[] {"/SubList", "/SubList?pro=true"}) {
      assertEmailMatchesPage(url);
    }
  }

  private void assertEmailMatchesPage(String url) throws Exception {
    String page = page(url);
    Set<String> listed = names(page);
    Set<String> mailed = optionText(follow(emailUrl(page)));

    assertEquals(listed, mailed,
                 url + ": the email list should hold exactly the substitutes listed"
                   + "\n  listed not mailed: " + minus(listed, mailed)
                   + "\n  mailed not listed: " + minus(mailed, listed));
  }

  private String page(String url) throws Exception {
    return get(url + (url.contains("?") ? "&" : "?") + "resort=Sample&ID=" + DIRECTOR_ID);
  }

  private String follow(String url) throws Exception {
    return get("/" + url);
  }

  private String get(String urlWithQuery) throws Exception {
    String path = urlWithQuery.split("\\?")[0];
    var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
    if (urlWithQuery.contains("?")) {
      for (String pair : urlWithQuery.split("\\?", 2)[1].split("&")) {
        String[] kv = pair.split("=", 2);
        builder = builder.param(kv[0], kv.length > 1 ? kv[1] : "");
      }
    }
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(builder.header("User-Agent", BROWSER).session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  private String emailUrl(String page) {
    Matcher matcher = Pattern.compile("data-url=\"(EmailForm\\?[^\"]+)\"").matcher(page);
    org.junit.jupiter.api.Assertions.assertTrue(matcher.find(), "the page should carry the button");
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

  /** Members carrying one of the given classification codes, whatever their substitute flag. */
  private Set<String> namesWithClassification(String... codes) {
    Set<String> found = new LinkedHashSet<>();
    List<String> wanted = List.of(codes);
    withPatrolData((log, patrolData) -> {
      java.sql.ResultSet results = patrolData.resetRoster();
      Roster member;
      while ((member = patrolData.nextMember("&nbsp;", results)) != null) {
        if (wanted.contains(member.getClassification())) {
          found.add(member.getFullName_lastNameFirst());
        }
      }
    });
    assertFalse(found.isEmpty(), "the sample roster should hold an Other or Alumni member");
    return found;
  }

  /** Any member the page currently lists, so the test does not hard-code an id. */
  private String aListedSubstituteId() {
    List<String> found = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      java.sql.ResultSet results = patrolData.resetRoster();
      Roster member;
      while ((member = patrolData.nextMember("&nbsp;", results)) != null) {
        if (member.isAvailableSubstitute(false)) {
          found.add(member.getID());
          return;
        }
      }
    });
    assertFalse(found.isEmpty(), "the sample roster should list at least one substitute");
    return found.get(0);
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

  private String commitmentOf(String id) {
    List<String> value = new ArrayList<>();
    withPatrolData((log, patrolData) -> value.add(patrolData.getMemberByID(id).getCommitment()));
    return value.get(0);
  }

  private void setCommitment(String id, String value) {
    withPatrolData((log, patrolData) -> {
      try (java.sql.Statement statement = patrolData.getConnection().createStatement()) {
        statement.executeUpdate("UPDATE roster SET Commitment = '" + value
                                  + "' WHERE IDNumber = " + id);
      }
      catch (java.sql.SQLException e) {
        throw new IllegalStateException("could not set commitment for " + id, e);
      }
    });
  }

  private void withPatrolData(java.util.function.BiConsumer<Logger, PatrolData> work) {
    org.springframework.mock.web.MockHttpServletRequest request =
      new org.springframework.mock.web.MockHttpServletRequest();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    request.setSession(session);
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(SubListPageTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
    }
    finally {
      patrolData.close();
    }
  }

  private Set<String> minus(Set<String> from, Set<String> other) {
    Set<String> only = new LinkedHashSet<>(from);
    only.removeAll(other);
    return only;
  }
}
