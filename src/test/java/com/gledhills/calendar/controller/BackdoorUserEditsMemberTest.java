package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The backdoor login has no roster row of its own, so UpdateInfo shows it a friendly stub instead
 * of a form. That stub used to be returned for <em>every</em> UpdateInfo request from a backdoor
 * session, including "Edit_Patroller" on a real member - which rendered "Personal Information for:
 * null" over "Have a great day" and no record at all.
 *
 * It is only right for the backdoor user's own record. getMemberByID hands that id a synthetic
 * director, so the director check already lets it through to edit others.
 *
 * Read-only: every request here is a GET.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class BackdoorUserEditsMemberTest {

  /** Matches backDoorUser in credentials.properties; getMemberByID fakes a director for it. */
  private static final String BACKDOOR_ID = "sgled57";
  private static final String MEMBER_ID = "200401";
  private static final String ORDINARY_MEMBER = "200401";
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
  void editingAMemberShowsThatMembersRecord() throws Exception {
    String page = body(request(BACKDOOR_ID, "Edit_Patroller", MEMBER_ID));

    assertTrue(page.contains("Bailey"), "the member's record should be on the page: " + page);
    assertFalse(page.contains("Have a great day"), "not the backdoor stub");
    assertFalse(page.contains("Personal Information for: null"), "and not a null heading");
  }

  /** The backdoor user is a director as far as this page is concerned, so it edits, not just reads. */
  @Test
  void editingAMemberOffersTheDirectorOnlyFields() throws Exception {
    String page = body(request(BACKDOOR_ID, "Edit_Patroller", MEMBER_ID));

    assertTrue(page.contains("<option value=\"CAN\">Candidate</option>"),
               "the classification dropdown is director-only, and should be editable: " + page);
  }

  @Test
  void changingAMembersIdAlsoReachesTheForm() throws Exception {
    String page = body(request(BACKDOOR_ID, "setNewID", MEMBER_ID));

    assertTrue(page.contains("value=\"Set New ID\""), "the Set New ID form should render: " + page);
    assertFalse(page.contains("Have a great day"), "not the backdoor stub");
  }

  /** Its own record still has nothing to draw a form from, so the stub stays - minus the "null". */
  @Test
  void itsOwnRecordStillShowsTheStub() throws Exception {
    String page = body(request(BACKDOOR_ID, null, null));

    assertTrue(page.contains("Have a great day"), "the stub should still be shown: " + page);
    assertFalse(page.contains("Personal Information for: null"),
                "but the heading should not read null");
  }

  /** The carve-out must not have opened EditID up to everybody. */
  @Test
  void anOrdinaryMemberStillCannotReadSomebodyElsesRecord() throws Exception {
    MvcResult result = request(ORDINARY_MEMBER, "Edit_Patroller", "123456");

    assertEquals(403, result.getResponse().getStatus(),
                 "a non-director using EditID on another member should be refused");
  }

  private MvcResult request(String loggedInId, String purpose, String editId) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", loggedInId);
    session.setAttribute("loggedInResort", "Sample");

    var builder = get("/UpdateInfo")
      .param("resort", "Sample")
      .param("ID", loggedInId)
      .header("User-Agent", BROWSER)
      .session(session);
    if (purpose != null) {
      builder = builder.param("Purpose", purpose);
    }
    if (editId != null) {
      builder = builder.param("EditID", editId);
    }
    return mockMvc.perform(builder).andReturn();
  }

  private String body(MvcResult result) throws Exception {
    assertEquals(200, result.getResponse().getStatus(), "the page should render");
    return result.getResponse().getContentAsString();
  }
}
