package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.ShiftDefinitions;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Personal Calendar Sync group on Web Site Preferences.
 *
 * <p>It gathers the two things a shift needs before it can leave this site and land correctly in
 * somebody's own calendar - which clock its times are on, and whether the times can be read at all
 * - and gives a director the one button that fixes what a computer can fix.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class PersonalCalendarSyncPanelTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String MEMBER_ID = "12345";
  private static final String SCRATCH = "ZZSyncPanel";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /** Dates far from any fixture month, so nothing this writes can disturb a golden render. */
  private static final String PAST_ROW = "2019-03-07_9";
  private static final String FUTURE_ROW = "2027-03-07_9";

  @AfterEach
  void removeScratchRows() {
    withPatrolData((log, patrolData) -> {
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().startsWith(SCRATCH)) {
          patrolData.deleteShift(shift);
        }
      }
      run(patrolData, "DELETE FROM assignments WHERE `Date` IN ('" + PAST_ROW + "','"
                        + FUTURE_ROW + "')");
    });
  }

  /** The group is on the page, says what it is for, and carries the warning about unusable times. */
  @Test
  void theGroupExplainsItselfAndWarnsAboutUnusableTimes() throws Exception {
    String panel = panel(preferencesPage());

    assertTrue(panel.contains("Enable PatrolCalendar sync to personal calendars"),
               "the group should be titled: " + panel);
    assertTrue(panel.contains("cannot be synced to a personal calendar"),
               "and warn that shifts without usable times are left out");
  }

  /**
   * The Time Zone setting lives <b>inside</b> the group now.
   *
   * <p>Asserted against the panel rather than the page, because the select existed before this
   * change - a page-wide check would pass without it having moved at all, which is the entire
   * thing being tested.
   */
  @Test
  void theTimeZoneSettingIsInsideTheGroup() throws Exception {
    String page = preferencesPage();

    assertTrue(page.contains("timeZone"), "the setting should still exist");
    assertTrue(panel(page).contains("id=\"timeZoneSelect\""),
               "and it should now sit inside the sync group: " + panel(page));
  }

  /** And the button that fixes what a computer can fix. */
  @Test
  void theGroupCarriesTheTidyButton() throws Exception {
    String panel = panel(preferencesPage());

    assertTrue(panel.contains("/WebSitePreferences/tidyShiftTimes"),
               "the tidy button should post to the per-resort job");
    assertTrue(panel.contains("confirmActionDialog"), "and ask first");
    assertTrue(preferencesPage().contains("/js/dialogs.js"),
               "which means the dialog helper has to be loaded, or the confirm silently never runs");
  }

  /** A real dirty row is rewritten, and the page says what happened. */
  @Test
  void theButtonTidiesThisResortsTemplates() throws Exception {
    writeScratchShift("0800", "1600");

    String redirect = mockMvc.perform(post("/WebSitePreferences/tidyShiftTimes")
                                        .param("resort", "Sample")
                                        .header("User-Agent", BROWSER)
                                        .session(directorSession()))
                             .andExpect(status().is3xxRedirection())
                             .andReturn().getResponse().getRedirectedUrl();

    assertTrue(redirect != null && redirect.contains("tidied="),
               "the outcome should come back on the redirect: " + redirect);

    assertEquals("8:00", storedStart(), "the packed start should have been canonicalised");
    assertEquals("16:00", storedEnd(), "and so should the end");
  }

  /**
   * It is director-only.
   *
   * <p>It rewrites every shift template in the resort, which is not an ordinary member's to do
   * even though the change is a safe one.
   */
  @Test
  void anOrdinaryMemberCannotRunIt() throws Exception {
    mockMvc.perform(post("/WebSitePreferences/tidyShiftTimes")
                      .param("resort", "Sample")
                      .header("User-Agent", BROWSER)
                      .session(memberSession()))
           .andExpect(status().isForbidden());
  }

  /** An unknown resort is refused before any database work, like every other page. */
  @Test
  void anUnknownResortIsRejected() throws Exception {
    mockMvc.perform(post("/WebSitePreferences/tidyShiftTimes")
                      .param("resort", "NoSuchResort")
                      .header("User-Agent", BROWSER)
                      .session(directorSession()))
           .andExpect(status().isBadRequest());
  }

  /** The readiness line is what tells a director whether opening the template editor is worth it. */
  @Test
  void theGroupSaysHowManyShiftsAreReady() throws Exception {
    String panel = panel(preferencesPage());

    assertTrue(panel.contains("shift template rows can be synced"),
               "the group should summarise readiness: " + panel);
    assertFalse(panel.contains("syncReadiness"),
                "the expression should have been evaluated, not printed");
  }


  /**
   * The switch is drawn everywhere, but only works where the feature has been proved.
   *
   * <p>Drawn rather than hidden on purpose: a director should be able to see that the feature is
   * coming and that tidying their shifts is worth doing, which the note beside it says.
   */
  @Test
  void theSwitchIsDisabledWhereTheFeatureIsNotReadyYet() throws Exception {
    String checkbox = syncCheckbox(preferencesPage());

    assertTrue(checkbox.contains("disabled"),
               "Sample is not cleared for sync, so the box must be greyed: " + checkbox);
    assertTrue(panel(preferencesPage()).contains("not available yet"),
               "and the page should say why, rather than leaving a dead control");
  }

  /**
   * The rule itself, in the one place it lives.
   *
   * <p>A demo resort can never sync however the rollout widens - its members are invented, the same
   * reason they are never emailed.
   */
  @Test
  void onlyAResortClearedForItMaySwitchItOn() {
    assertTrue(PatrolData.maySwitchOnCalendarSync("Brighton"), "the resort proving the feature");
    assertFalse(PatrolData.maySwitchOnCalendarSync("Afton"), "not yet opened up");
    assertFalse(PatrolData.maySwitchOnCalendarSync("Sample"), "a demo resort never syncs");
    assertFalse(PatrolData.maySwitchOnCalendarSync("SampleBackup"), "nor its master copy");
    assertFalse(PatrolData.maySwitchOnCalendarSync(null));
  }

  /**
   * Posting the switch from a resort that may not have it changes nothing.
   *
   * <p>A disabled checkbox is simply not submitted, which is no obstacle whatever to anyone who
   * wants to submit one - so the save checks the same rule again. This is the half that actually
   * holds.
   */
  @Test
  void aSwitchPostedByHandIsIgnored() throws Exception {
    boolean before = storedSyncEnabled();

    submitPreferences(true);                        //Sample, which is not cleared for it

    assertEquals(before, storedSyncEnabled(),
                 "a posted switch must not turn sync on for a resort that may not have it");
  }

  /**
   * And an ordinary save from such a resort must not turn it <b>off</b> either.
   *
   * <p>The checkbox being disabled means the form posts nothing for it, which binds as false. Were
   * the save to write that, every preferences change at 43 resorts would silently clear a setting
   * they cannot even see.
   */
  @Test
  void anOrdinarySaveDoesNotClearTheStoredSwitch() throws Exception {
    boolean before = storedSyncEnabled();
    //Switched on underneath the controller, because the controller is exactly what will not let
    //this resort switch it on. With it left false the test would pass whatever the save did.
    setStoredSyncEnabled(true);
    try {
      submitPreferences(false);                     //the shape a disabled checkbox posts

      assertTrue(storedSyncEnabled(),
                 "an ordinary preferences save must not clear a switch the form cannot show");
    }
    finally {
      setStoredSyncEnabled(before);
    }
  }

  /**
   * The scheduled-shifts tidy fixes a future day and <b>leaves the past alone</b>.
   *
   * <p>That second half is the whole reason this button is safe to put in front of a director, so
   * it is asserted rather than trusted: a past shift is a record of what somebody worked, and
   * rewriting it helps nobody.
   */
  @Test
  void tidyingScheduledShiftsSparesThePast() throws Exception {
    writeAssignmentRow(PAST_ROW, "0800", "1600");
    writeAssignmentRow(FUTURE_ROW, "0800", "1600");

    mockMvc.perform(post("/WebSitePreferences/tidyScheduledShifts")
                      .param("resort", "Sample")
                      .header("User-Agent", BROWSER)
                      .session(directorSession()))
           .andExpect(status().is3xxRedirection());

    assertEquals("8:00|16:00", assignmentTimes(FUTURE_ROW), "today onwards should be tidied");
    assertEquals("0800|1600", assignmentTimes(PAST_ROW), "history must be left exactly as it was");
  }

  /** And it is director-only, like its sibling. */
  @Test
  void anOrdinaryMemberCannotTidyScheduledShifts() throws Exception {
    mockMvc.perform(post("/WebSitePreferences/tidyScheduledShifts")
                      .param("resort", "Sample")
                      .header("User-Agent", BROWSER)
                      .session(memberSession()))
           .andExpect(status().isForbidden());
  }

  /** Both buttons are in the group, and the second says what it will not touch. */
  @Test
  void theGroupCarriesBothTidyButtons() throws Exception {
    String panel = panel(preferencesPage());

    assertTrue(panel.contains("/WebSitePreferences/tidyShiftTimes"), "templates button");
    assertTrue(panel.contains("/WebSitePreferences/tidyScheduledShifts"), "scheduled days button");
    assertTrue(panel.contains("Only days from today onwards are touched"),
               "and the scheduled one should say what it spares");
  }

  /** Post the preferences form with everything as it is, changing only the switch. */
  private void submitPreferences(boolean syncEnabled) throws Exception {
    var request = post("/WebSitePreferences")
                    .param("resort", "Sample")
                    .param("emailPatrollers", "true").param("emailReminder", "true")
                    .param("reminderDays", "3").param("emailAll", "true")
                    .param("onlyDirectors", "false").param("removeAccess", "2")
                    .param("timeZone", "America/Denver")
                    .param("startDay", "1").param("startMonth", "11")
                    .param("endDay", "30").param("endMonth", "4")
                    .param("useBlackOut", "false")
                    .param("blackOutStartDay", "1").param("blackOutStartMonth", "1")
                    .param("blackOutStartYear", "2026")
                    .param("blackOutEndDay", "31").param("blackOutEndMonth", "12")
                    .param("blackOutEndYear", "2026")
                    .header("User-Agent", BROWSER)
                    .session(directorSession());
    if (syncEnabled) {
      request = request.param("syncEnabled", "true");
    }
    mockMvc.perform(request).andExpect(status().is3xxRedirection());
  }

  /** Straight to the store, under the controller's gate, so the test can set up a real "on". */
  private void setStoredSyncEnabled(boolean enabled) {
    withPatrolData((log, patrolData) -> {
      com.gledhills.calendar.store.DirectorSettings settings = patrolData.readDirectorSettings();
      settings.setSyncEnabled(enabled);
      settings.write(patrolData.getConnection());
    });
  }

  private boolean storedSyncEnabled() {
    boolean[] found = {false};
    withPatrolData((log, patrolData) ->
      found[0] = patrolData.readDirectorSettings().getSyncEnabled());
    return found[0];
  }

  private void writeAssignmentRow(String date, String start, String end) {
    withPatrolData((log, patrolData) -> {
      run(patrolData, "DELETE FROM assignments WHERE `Date` = '" + date + "'");
      run(patrolData, "INSERT INTO assignments (`Date`,`StartTime`,`EndTime`,`EventName`,"
                        + "`ShiftType`,`Count`,`Label`) VALUES ('" + date + "','" + start + "','"
                        + end + "','" + SCRATCH + "',0,2,'')");
    });
  }

  /** Start and end as stored, so the assertion reads the database rather than the code. */
  private String assignmentTimes(String date) {
    String[] found = {"(not found)"};
    withPatrolData((log, patrolData) -> {
      try (java.sql.Statement statement = patrolData.getConnection().createStatement();
           java.sql.ResultSet rows = statement.executeQuery(
             "SELECT StartTime, EndTime FROM assignments WHERE `Date` = '" + date + "'")) {
        if (rows.next()) {
          found[0] = rows.getString(1) + "|" + rows.getString(2);
        }
      }
      catch (Exception e) {
        throw new AssertionError("could not read " + date, e);
      }
    });
    return found[0];
  }

  private void run(PatrolData patrolData, String sql) {
    try (java.sql.Statement statement = patrolData.getConnection().createStatement()) {
      statement.executeUpdate(sql);
    }
    catch (Exception e) {
      throw new AssertionError("failed: " + sql, e);
    }
  }

  /** Just the syncEnabled checkbox tag, so "disabled" cannot be matched from elsewhere. */
  private String syncCheckbox(String page) {
    int at = page.indexOf("syncEnabled");
    if (at < 0) {
      return "(no syncEnabled field on the page)";
    }
    int open = page.lastIndexOf("<input", at);
    int close = page.indexOf(">", at);
    return open < 0 || close < 0 ? "(malformed)" : page.substring(open, close + 1);
  }

  /** Just the sync group, so an assertion cannot pass on something elsewhere on the page. */
  private String panel(String page) {
    int start = page.indexOf("id=\"calendarSyncPanel\"");
    if (start < 0) {
      return "";
    }
    //to the start of the next section, which is the Season Dates heading
    int end = page.indexOf("Season Dates", start);
    return end < 0 ? page.substring(start) : page.substring(start, end);
  }

  private String preferencesPage() throws Exception {
    return mockMvc.perform(get("/WebSitePreferences")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
                  .andExpect(status().isOk())
                  .andReturn().getResponse().getContentAsString();
  }

  private void writeScratchShift(String start, String end) {
    withPatrolData((log, patrolData) -> patrolData.writeShift(
      new ShiftDefinitions(ShiftDefinitions.createShiftName(SCRATCH, 0), start, end, 2, 0, "", log)));
  }

  private String storedStart() {
    return scratchField(true);
  }

  private String storedEnd() {
    return scratchField(false);
  }

  private String scratchField(boolean wantStart) {
    String[] found = {"(not found)"};
    withPatrolData((log, patrolData) -> {
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(SCRATCH)) {
          found[0] = wantStart ? shift.getStartString() : shift.getEndString();
        }
      }
    });
    return found[0];
  }

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(PersonalCalendarSyncPanelTest.class, javaxRequest, "GET", "Sample",
                            Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
    }
    finally {
      patrolData.close();
    }
  }

  private MockHttpSession directorSession() {
    return sessionFor(DIRECTOR_ID);
  }

  private MockHttpSession memberSession() {
    return sessionFor(MEMBER_ID);
  }

  private MockHttpSession sessionFor(String id) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", id);
    session.setAttribute("loggedInResort", "Sample");
    return session;
  }
}
