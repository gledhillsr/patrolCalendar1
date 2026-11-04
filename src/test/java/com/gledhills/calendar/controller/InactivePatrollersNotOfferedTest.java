package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.SqlWriter;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Replace/Insert list on ChangeShiftAssignments offers only patrollers who are patrolling.
 *
 * <p>Commitment "0" is Inactive, and {@code Roster.hasActiveCommitment()} is the same test
 * ListPatrollers and the Patrollers tab use, so the three cannot drift.
 *
 * <p>The second test is the one that matters. The roster loop also fills {@code numToName}, which
 * is what resolves whoever currently holds the slot - and {@code posWasEmpty} is derived from it.
 * Filtering that map as well would make an inactive patroller already on a shift come back blank,
 * the page would decide the slot was empty and offer "Insert someone else", and a director could
 * no longer take them off the shift at all. Hiding somebody from a list must not hide them from
 * the shift they are on.
 *
 * <p>Sample has no inactive member, so this makes one: it flips the commitment of whoever occupies
 * a known-occupied slot, and puts it back afterwards.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class InactivePatrollersNotOfferedTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  /** 13 November 2024, shift group 1, slot 1: an ordinary occupied slot. */
  private static final int DAY = 13;
  private static final int POS = 1;
  private static final int INDEX = 1;

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;
  private String occupantId;
  private String occupantCommitment;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    occupantId = withPatrolData(this::idInTheSlot);
    assertNotNull(occupantId, "the fixture slot should be occupied");
    occupantCommitment = withPatrolData(patrolData -> {
      Roster member = patrolData.getMemberByID(occupantId);
      return member == null ? null : member.getCommitment();
    });
    assertNotNull(occupantCommitment, "the occupant should be on the roster");
  }

  @AfterEach
  void putTheCommitmentBack() {
    setCommitment(occupantId, occupantCommitment);
  }

  /** An active patroller is offered, which is what makes the absence below meaningful. */
  @Test
  void anActivePatrollerIsStillOffered() throws Exception {
    setCommitment(occupantId, "2"); //Full Time

    String names = replaceListOf(slotPage());

    assertTrue(names.contains(nameOf(occupantId)),
               "a full-time patroller should be in the Replace list: " + names);
  }

  @Test
  void anInactivePatrollerIsNotOffered() throws Exception {
    setCommitment(occupantId, "0"); //Inactive

    String names = replaceListOf(slotPage());

    assertFalse(names.contains(nameOf(occupantId)),
                "an inactive patroller should not be offered: " + names);
  }

  /**
   * ...but they are still shown as holding the shift they hold, so a director can remove them.
   * This is the half that breaks if numToName is filtered along with the dropdown.
   */
  @Test
  void anInactivePatrollerAlreadyOnAShiftCanStillBeReplaced() throws Exception {
    setCommitment(occupantId, "0");

    String page = slotPage();

    assertTrue(page.contains("<b>Replace</b>"),
               "the slot is occupied, so the page must offer Replace: " + summarise(page));
    assertFalse(page.contains("<b>Insert</b>"),
                "it must not decide the slot is empty: " + summarise(page));
  }

  /** Inactive is commitment "0" specifically - part time must not be caught by it. */
  @Test
  void partTimeIsActive() throws Exception {
    setCommitment(occupantId, "1"); //Part Time

    assertTrue(replaceListOf(slotPage()).contains(nameOf(occupantId)),
               "part time is a patrolling commitment");
  }

  // ---------------------------------------------------------------- helpers

  /** Just the Replace/Insert &lt;select&gt;, so a name elsewhere on the page cannot pass the test. */
  private String replaceListOf(String page) {
    int select = page.indexOf("name=\"listName\"");
    assertTrue(select > 0, "the Replace list should be on the page");
    int end = page.indexOf("</select>", select);
    return page.substring(select, end);
  }

  private String nameOf(String id) {
    return withPatrolData(patrolData -> {
      Roster member = patrolData.getMemberByID(id);
      return member == null ? "(not on roster)" : member.getFullName_lastNameFirst();
    });
  }

  private String idInTheSlot(PatrolData patrolData) {
    List<Assignments> groups = patrolData.readSortedAssignments(2024, 11, DAY);
    if (groups == null || groups.size() < POS) {
      return null;
    }
    String id = groups.get(POS - 1).getPosID(INDEX);
    if (id == null || id.isEmpty() || "0".equals(id)) {
      return null;
    }
    return id.charAt(0) == '-' ? id.substring(1) : id;
  }

  private void setCommitment(String id, String commitment) {
    withPatrolData(patrolData -> {
      try {
        //a targeted UPDATE rather than a round trip through getUpdateSQLString, which would
        //rewrite the member's password columns as a side effect of changing one field
        SqlWriter.executeUpdate(new Logger(InactivePatrollersNotOfferedTest.class, null, "GET",
                                           "Sample", Logger.INFO),
                                patrolData.getConnection(), "testSetCommitment",
                                "UPDATE roster SET Commitment = '" + commitment
                                  + "' WHERE IDNumber = '" + id + "'");
      }
      catch (Exception e) {
        throw new AssertionError("could not set commitment for " + id, e);
      }
      return null;
    });
  }

  private String slotPage() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(get("/ChangeShiftAssignments")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("dayOfWeek", "4")
                             .param("date", String.valueOf(DAY))
                             .param("month", "10")
                             .param("year", "2024")
                             .param("pos", String.valueOf(POS))
                             .param("index", String.valueOf(INDEX))
                             .header("User-Agent", BROWSER)
                             .session(session))
                  .andExpect(status().isOk())
                  .andReturn().getResponse().getContentAsString();
  }

  private String summarise(String page) {
    int select = page.indexOf("name=\"transaction\"");
    return select < 0 ? "(no transaction options)"
                      : page.substring(select, Math.min(page.length(), select + 400));
  }

  private <T> T withPatrolData(Function<PatrolData, T> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    request.setSession(session);
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(InactivePatrollersNotOfferedTest.class, javaxRequest, "GET", "Sample",
                            Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      return work.apply(patrolData);
    }
    finally {
      patrolData.close();
    }
  }
}
