package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Show Patrollers History ..." on the Directors page: a director picks a name off the roster and
 * reads that patroller's assignment history on the ListAssignments page, with a Done button back.
 *
 * Read-only - nothing here writes, so there is nothing to clean up.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class PatrollerHistoryFromDirectorsTest {

  private static final String DIRECTOR_ID = "123456";
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
  void theDirectorsPageOffersTheHistoryButton() throws Exception {
    String page = mockMvc.perform(get("/Directors")
                                    .param("resort", "Sample")
                                    .param("ID", DIRECTOR_ID)
                                    .header("User-Agent", BROWSER)
                                    .session(sessionFor(DIRECTOR_ID)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertTrue(page.contains("Show assignments history"),
               "the third column should be labelled: " + page);
    assertTrue(page.contains("value=\"Show Patrollers History ...\""),
               "and carry the button");
    // it has to leave the roster select behind in the form, so it can carry the selection
    assertTrue(page.contains("formaction=\"ListAssignments\""),
               "the button should submit to ListAssignments");
    assertTrue(page.contains("formmethod=\"get\""),
               "as a GET, which is what ListAssignments answers");
  }

  @Test
  void aDirectorSeesTheSelectedPatrollersHistory() throws Exception {
    String page = historyPage(DIRECTOR_ID, rosterNameOf(MEMBER_ID));

    assertTrue(page.contains(fullNameOf(MEMBER_ID) + "&#39;s Assignment Schedule")
                 || page.contains(fullNameOf(MEMBER_ID) + "'s Assignment Schedule"),
               "the page should be headed with the selected patroller: " + fullNameOf(MEMBER_ID));
    assertTrue(page.contains("value=\"Done\""), "and offer a Done button back to Directors");
    assertTrue(page.contains("Directors?resort=Sample&amp;ID=" + DIRECTOR_ID)
                 || page.contains("Directors?resort=Sample&ID=" + DIRECTOR_ID),
               "wired back to the Directors page");
  }

  /** Toggling the season must stay on the patroller being looked at. */
  @Test
  void theSeasonToggleKeepsTheSelectedPatroller() throws Exception {
    String page = historyPage(DIRECTOR_ID, rosterNameOf(MEMBER_ID));

    assertTrue(page.contains("NameToEdit="),
               "the toggle link should carry the selection: " + page);
  }

  /** Without the parameter it is the plain page it always was: your own shifts, no Done. */
  @Test
  void aDirectorsOwnPageIsUnchanged() throws Exception {
    String page = mockMvc.perform(get("/ListAssignments")
                                    .param("resort", "Sample")
                                    .param("ID", DIRECTOR_ID)
                                    .header("User-Agent", BROWSER)
                                    .session(sessionFor(DIRECTOR_ID)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertFalse(page.contains("value=\"Done\""),
                "no Done button when nobody was selected");
    assertTrue(page.contains(fullNameOf(DIRECTOR_ID)),
               "and it should still be the director's own schedule");
  }

  /**
   * The button only exists for directors, but the URL it produces is guessable, so a non-director
   * asking for somebody else must simply get their own history back.
   */
  @Test
  void aNonDirectorCannotReadSomebodyElsesHistory() throws Exception {
    String ordinaryMember = aNonDirectorId();
    assertNotNull(ordinaryMember, "the Sample roster should hold a non-director");

    String page = historyPage(ordinaryMember, rosterNameOf(MEMBER_ID));

    assertTrue(page.contains(fullNameOf(ordinaryMember)),
               "a non-director should see their own schedule");
    assertFalse(page.contains(fullNameOf(MEMBER_ID) + "&#39;s Assignment Schedule"),
                "and not the patroller they asked for");
  }

  private String historyPage(String loggedInId, String selectedName) throws Exception {
    return mockMvc.perform(get("/ListAssignments")
                             .param("resort", "Sample")
                             .param("ID", loggedInId)
                             .param("NameToEdit", selectedName)
                             .param("showHistory", "Show Patrollers History ...")
                             .header("User-Agent", BROWSER)
                             .session(sessionFor(loggedInId)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  /** "Last, First" - the format the Directors roster select uses. */
  private String rosterNameOf(String id) {
    List<String> name = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      Roster member = patrolData.getMemberByID(id);
      name.add(member.getLast() + ", " + member.getFirst());
    });
    return name.get(0);
  }

  /** The name the page headline uses. */
  private String fullNameOf(String id) {
    List<String> name = new ArrayList<>();
    withPatrolData((log, patrolData) -> name.add(patrolData.getMemberByID(id).getFullName()));
    return name.get(0);
  }

  private String aNonDirectorId() {
    List<String> found = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      ResultSet rosterResults = patrolData.resetRoster();
      Roster member;

      while ((member = patrolData.nextMember("", rosterResults)) != null) {
        // not the patroller being asked for either, or "saw their own name" and "saw the
        // other patroller's name" would be the same assertion
        if (!member.isDirector() && member.getID() != null
              && !member.getID().equals(DIRECTOR_ID) && !member.getID().equals(MEMBER_ID)) {
          found.add(member.getID());
          return;
        }
      }
    });
    return found.isEmpty() ? null : found.get(0);
  }

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(sessionFor(DIRECTOR_ID));
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(PatrollerHistoryFromDirectorsTest.class, javaxRequest, "GET", "Sample",
                            Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
    }
    finally {
      patrolData.close();
    }
  }

  private MockHttpSession sessionFor(String id) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", id);
    session.setAttribute("loggedInResort", "Sample");
    return session;
  }
}
