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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for WebSitePreferencesController.
 * Verifies authentication, authorization, form display, and preferences update.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class WebSitePreferencesControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        session = new MockHttpSession();
    }

    /**
     * Test that accessing WebSitePreferences without authentication redirects to login.
     */
    @Test
    void testWebSitePreferencesRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/WebSitePreferences")
                .param("resort", "Sample")
                .param("ID", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> {
                    String redirectUrl = result.getResponse().getRedirectedUrl();
                    assert redirectUrl != null && redirectUrl.contains("MemberLogin")
                        && redirectUrl.contains("resort=Sample")
                        : "Expected redirect to MemberLogin with resort=Sample, got: " + redirectUrl;
                });
    }

    /**
     * Test that non-director users see "not authorized" message.
     */
    @Test
    void testNonDirectorSeesNotAuthorizedMessage() throws Exception {
        // Set up session with non-director user
        session.setAttribute("loggedInUserId", "12345");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/WebSitePreferences")
                .param("resort", "Sample")
                .param("ID", "12345")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Sorry, you are -not- authorized for this menu")));
    }

    /**
     * Test that director users see the preferences form.
     * Note: Uses actual database user 123456 (Steve) who is a director.
     */
    @Test
    void testDirectorSeesPreferencesForm() throws Exception {
        // Set up session with director user
        session.setAttribute("loggedInUserId", "123456");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/WebSitePreferences")
                .param("resort", "Sample")
                .param("ID", "123456")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Email Settings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Calendar Permissions")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Season Dates")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Blackout Dates")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Save Preferences")));
    }

    /**
     * Test that director can update preferences.
     * Note: This test modifies actual database data - verify state after running.
     */
    @Test
    void testDirectorCanUpdatePreferences() throws Exception {
        // Set up session with director user
        session.setAttribute("loggedInUserId", "123456");
        session.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(post("/WebSitePreferences")
                .param("resort", "Sample")
                .param("emailPatrollers", "true")
                .param("emailReminder", "true")
                .param("reminderDays", "3")
                .param("emailAll", "true")
                .param("onlyDirectors", "false")
                .param("removeAccess", "2")
                .param("startDay", "1")
                .param("startMonth", "11")
                .param("endDay", "30")
                .param("endMonth", "4")
                .param("useBlackOut", "false")
                .param("blackOutStartDay", "1")
                .param("blackOutStartMonth", "1")
                .param("blackOutStartYear", "2026")
                .param("blackOutEndDay", "31")
                .param("blackOutEndMonth", "12")
                .param("blackOutEndYear", "2026")
                .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/WebSitePreferences?resort=Sample&ID=123456&success=true"));
    }

  /**
   * A director's time zone choice is stored and comes back selected.
   *
   * <p>The zone changes nothing a director sees on this site - every time here is read by somebody
   * at the mountain - so a save that quietly did nothing would look exactly like one that worked.
   * Restores whatever the resort had, since Sample's settings are not this test's to change.
   */
  @Test
  void theTimeZoneIsSavedAndComesBack() throws Exception {
    String before = storedTimeZone();
    try {
      submitTimeZone("America/New_York");
      assertEquals("America/New_York", storedTimeZone(), "the choice should be stored");
      assertTrue(preferencesPage().contains("value=\"America/New_York\" selected"),
                 "and come back selected on the page");

      submitTimeZone("America/Chicago");
      assertEquals("America/Chicago", storedTimeZone(), "and be changeable again");
    }
    finally {
      submitTimeZone(before.isEmpty() ? "America/Denver" : before);
    }
  }

  /** Post the preferences form with everything as it is, changing only the zone. */
  /**
   * "How many days before the shift" is disabled on the page while reminders are off, and a
   * disabled input is not posted - so the save has to read an absent value as "leave it alone".
   *
   * <p>It used to default to 0, which meant every save made with reminders switched off quietly
   * rewrote the stored number to zero. Nobody would see it until they turned reminders back on
   * and found their 3 days had become 0.
   */
  @Test
  void theReminderDaysSurviveASaveMadeWithRemindersOff() throws Exception {
    int before = storedReminderDays();
    try {
      submitPreferences("emailReminder", "true", "reminderDays", "4");
      assertEquals(4, storedReminderDays(), "the choice should be stored");

      //reminders off, so the browser disables the days box and posts no value at all
      submitPreferences("reminderDays", null);
      assertEquals(4, storedReminderDays(),
                   "turning reminders off must not throw away how many days were chosen");

      submitPreferences("emailReminder", "true", "reminderDays", "7");
      assertEquals(7, storedReminderDays(), "and it is still changeable");
    }
    finally {
      submitPreferences("emailReminder", "true", "reminderDays", String.valueOf(before));
    }
  }

  /** Reminders on with the box emptied is not a saveable setting, and is said so rather than kept. */
  @Test
  void remindersOnWithNoNumberIsRefused() throws Exception {
    int before = storedReminderDays();
    try {
      String page = postPreferences("emailReminder", "true", "reminderDays", "")
        .andReturn().getResponse().getContentAsString();

      assertTrue(page.contains("Say how many days before a shift the reminder should go out"),
                 "the page should say what is missing: " + page);
      assertEquals(before, storedReminderDays(), "and nothing should have been written");
    }
    finally {
      submitPreferences("emailReminder", "true", "reminderDays", String.valueOf(before));
    }
  }

  /** The two settings are one row now, with the number indented under the switch that governs it. */
  @Test
  void theReminderDaysAreGroupedUnderTheirSwitch() throws Exception {
    String page = preferencesPage();

    assertTrue(page.contains("id=\"reminderDaysRow\"") && page.contains("dependent-setting"),
               "the days field should be drawn as a setting dependent on the checkbox");
    //same <td> as the checkbox, so it sits on the one line rather than wrapping under it
    int checkbox = page.indexOf("id=\"emailReminderBox\"");
    int days = page.indexOf("id=\"reminderDaysRow\"");
    assertTrue(checkbox > 0 && days > checkbox
                 && page.indexOf("</td>", checkbox) > days,
               "the days control belongs in the same cell as the checkbox, on the same line");
    assertTrue(page.contains("onchange=\"updateReminderDays()\""),
               "and the checkbox should enable and disable it");
    assertFalse(page.contains("Number of days before shift to send reminder"),
                "the old free-standing row should be gone");
  }

  /** The preferences form with everything valid, overriding the named fields. */
  private void submitPreferences(String... overrides) throws Exception {
    postPreferences(overrides).andExpect(status().is3xxRedirection());
  }

  private org.springframework.test.web.servlet.ResultActions postPreferences(String... overrides)
    throws Exception {
    session.setAttribute("loggedInUserId", "123456");
    session.setAttribute("loggedInResort", "Sample");

    java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
    fields.put("emailPatrollers", "true");
    fields.put("emailReminder", "false");
    fields.put("reminderDays", "3");
    fields.put("emailAll", "true");
    fields.put("onlyDirectors", "false");
    fields.put("removeAccess", "2");
    fields.put("startDay", "1");
    fields.put("startMonth", "11");
    fields.put("endDay", "30");
    fields.put("endMonth", "4");
    fields.put("useBlackOut", "false");
    fields.put("blackOutStartDay", "1");
    fields.put("blackOutStartMonth", "1");
    fields.put("blackOutStartYear", "2026");
    fields.put("blackOutEndDay", "31");
    fields.put("blackOutEndMonth", "12");
    fields.put("blackOutEndYear", "2026");
    for (int i = 0; i < overrides.length; i += 2) {
      //a null value means the field is not posted at all, which is what a disabled input does
      if (overrides[i + 1] == null) {
        fields.remove(overrides[i]);
      }
      else {
        fields.put(overrides[i], overrides[i + 1]);
      }
    }

    var request = post("/WebSitePreferences").param("resort", "Sample").session(session);
    for (var field : fields.entrySet()) {
      request = request.param(field.getKey(), field.getValue());
    }
    return mockMvc.perform(request);
  }

  /** Read the column directly, so the assertion does not lean on the code that wrote it. */
  private int storedReminderDays() {
    org.springframework.mock.web.MockHttpServletRequest request =
      new org.springframework.mock.web.MockHttpServletRequest();
    request.setSession(session);
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    com.gledhills.calendar.utils.Logger log =
      new com.gledhills.calendar.utils.Logger(WebSitePreferencesControllerTest.class, javaxRequest,
                                              "GET", "Sample",
                                              com.gledhills.calendar.utils.Logger.INFO);
    com.gledhills.calendar.utils.PatrolData patrolData =
      new com.gledhills.calendar.utils.PatrolData(
        "Sample", new com.gledhills.calendar.utils.SessionData(javaxRequest, null, log), log);
    try {
      return patrolData.readDirectorSettings().getReminderDays();
    }
    finally {
      patrolData.close();
    }
  }

  private void submitTimeZone(String zone) throws Exception {
    session.setAttribute("loggedInUserId", "123456");
    session.setAttribute("loggedInResort", "Sample");

    mockMvc.perform(post("/WebSitePreferences")
                      .param("resort", "Sample")
                      .param("emailPatrollers", "true")
                      .param("emailReminder", "true")
                      .param("reminderDays", "3")
                      .param("emailAll", "true")
                      .param("onlyDirectors", "false")
                      .param("removeAccess", "2")
                      .param("timeZone", zone)
                      .param("startDay", "1").param("startMonth", "11")
                      .param("endDay", "30").param("endMonth", "4")
                      .param("useBlackOut", "false")
                      .param("blackOutStartDay", "1").param("blackOutStartMonth", "1")
                      .param("blackOutStartYear", "2026")
                      .param("blackOutEndDay", "31").param("blackOutEndMonth", "12")
                      .param("blackOutEndYear", "2026")
                      .session(session))
           .andExpect(status().is3xxRedirection());
  }

  private String preferencesPage() throws Exception {
    session.setAttribute("loggedInUserId", "123456");
    session.setAttribute("loggedInResort", "Sample");
    return mockMvc.perform(get("/WebSitePreferences").param("resort", "Sample")
                                                     .param("ID", "123456")
                                                     .session(session))
                  .andReturn().getResponse().getContentAsString();
  }

  /** Read the column directly, so the assertion does not lean on the code that wrote it. */
  private String storedTimeZone() {
    org.springframework.mock.web.MockHttpServletRequest request =
      new org.springframework.mock.web.MockHttpServletRequest();
    request.setSession(session);
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    com.gledhills.calendar.utils.Logger log =
      new com.gledhills.calendar.utils.Logger(WebSitePreferencesControllerTest.class, javaxRequest,
                                              "GET", "Sample",
                                              com.gledhills.calendar.utils.Logger.INFO);
    com.gledhills.calendar.utils.PatrolData patrolData =
      new com.gledhills.calendar.utils.PatrolData(
        "Sample", new com.gledhills.calendar.utils.SessionData(javaxRequest, null, log), log);
    try (java.sql.Statement statement = patrolData.getConnection().createStatement();
         java.sql.ResultSet rows = statement.executeQuery(
           "SELECT `" + com.gledhills.calendar.store.DirectorSettings.TIME_ZONE_FIELD
             + "` FROM directorsettings")) {
      return rows.next() ? rows.getString(1) : "";
    }
    catch (Exception e) {
      throw new AssertionError("could not read the stored time zone", e);
    }
    finally {
      patrolData.close();
    }
  }
}
