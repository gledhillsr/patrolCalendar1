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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UpdateInfo offers a Cancel back to the Directors page only when a director arrived from it -
 * "Edit Patroller Info ...", "Change ID Number ..." or "Add New Patroller ...". A member on their
 * own My Info tab has the site nav to leave by and no Directors page to return to, so no Cancel.
 *
 * Read-only: the two POSTs here only redirect, and nothing renders past the empty add form.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class UpdateInfoCancelButtonTest {

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
  void editPatrollerInfoOffersCancel() throws Exception {
    String page = fromDirectorsButton("EditInfo");

    assertTrue(page.contains("value=\"Update Information\""), "this should be the edit form");
    assertTrue(hasCancel(page), "Edit Patroller Info should offer Cancel: " + page);
    assertTrue(page.contains("/Directors?resort=Sample&amp;ID=" + DIRECTOR_ID)
                 || page.contains("/Directors?resort=Sample&ID=" + DIRECTOR_ID),
               "and it should return to the Directors page");
  }

  @Test
  void changeIdNumberOffersCancel() throws Exception {
    String page = fromDirectorsButton("setNewID");

    assertTrue(page.contains("value=\"Set New ID\""), "this should be the Change ID Number form");
    assertTrue(hasCancel(page), "Change ID Number should offer Cancel: " + page);
  }

  @Test
  void addNewPatrollerOffersCancel() throws Exception {
    String page = mockMvc.perform(get("/UpdateInfo")
                                    .param("resort", "Sample")
                                    .param("ID", DIRECTOR_ID)
                                    .param("Purpose", "Add_Patroller")
                                    .header("User-Agent", BROWSER)
                                    .session(sessionFor(DIRECTOR_ID)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertTrue(page.contains("value=\"Create New Patroller\""), "this should be the add form");
    assertTrue(hasCancel(page), "Add New Patroller should offer Cancel: " + page);
  }

  /** The My Info tab: a member editing themselves, with nowhere to cancel back to. */
  @Test
  void myInfoHasNoCancel() throws Exception {
    String page = mockMvc.perform(get("/UpdateInfo")
                                    .param("resort", "Sample")
                                    .param("ID", MEMBER_ID)
                                    .header("User-Agent", BROWSER)
                                    .session(sessionFor(MEMBER_ID)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertTrue(page.contains("value=\"Update Information\""), "this should be the My Info form");
    assertFalse(hasCancel(page), "My Info should have no Cancel button: " + page);
  }

  /** A director opening their own My Info from the tab is still the My Info route. */
  @Test
  void aDirectorsOwnMyInfoHasNoCancel() throws Exception {
    String page = mockMvc.perform(get("/UpdateInfo")
                                    .param("resort", "Sample")
                                    .param("ID", DIRECTOR_ID)
                                    .header("User-Agent", BROWSER)
                                    .session(sessionFor(DIRECTOR_ID)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertFalse(hasCancel(page),
                "arriving from the tab rather than the Directors page means no Cancel: " + page);
  }

  /**
   * Press one of the Directors page's roster buttons for real: its form posts the selected name,
   * and the controller looks the id up and redirects to the GET that renders the form. Following
   * that redirect is the only way to cover findPatrollerIdByName too.
   */
  private String fromDirectorsButton(String button) throws Exception {
    String redirect = mockMvc.perform(post("/UpdateInfo")
                                        .param("resort", "Sample")
                                        .param("ID", DIRECTOR_ID)
                                        .param("NameToEdit", nameOf(MEMBER_ID))
                                        .param(button, "go")
                                        .header("User-Agent", BROWSER)
                                        .session(sessionFor(DIRECTOR_ID)))
      .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();

    assertNotNull(redirect, button + " should redirect to the edit form");
    assertFalse(redirect.startsWith("Directors"),
                button + " bounced back to Directors, so the name was not found: " + redirect);

    return mockMvc.perform(get("/" + redirect)
                             .header("User-Agent", BROWSER)
                             .session(sessionFor(DIRECTOR_ID)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  private boolean hasCancel(String page) {
    return page.contains("value=\"Cancel\"");
  }

  /** The Directors roster select posts "Last, First". */
  private String nameOf(String id) {
    List<String> name = new ArrayList<>();
    withPatrolData((log, patrolData) -> {
      Roster member = patrolData.getMemberByID(id);
      name.add(member.getLast() + ", " + member.getFirst());
    });
    return name.get(0);
  }

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(sessionFor(DIRECTOR_ID));
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(UpdateInfoCancelButtonTest.class, javaxRequest, "GET", "Sample",
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
