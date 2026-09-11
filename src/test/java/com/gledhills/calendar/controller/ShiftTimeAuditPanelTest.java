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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The panel on Edit Shift Templates that lists shifts whose times cannot be read.
 *
 * <p>Rendered rather than asserted on the model, because the failure this guards against is a
 * Thymeleaf one: an expression that compiles fine and throws at render time takes the whole page
 * down, and the page it takes down is the one a director uses to fix shifts.
 *
 * <p>The scratch template is written straight to the database on purpose. The editor refuses to
 * <em>save</em> an end time that is not a time, which is right - but rows like this already exist
 * in every resort's history, and this panel is the thing that finds them, so the test has to be
 * able to make one the same way history did.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ShiftTimeAuditPanelTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String SCRATCH = "ZZAuditPanel";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @AfterEach
  void removeScratchTemplate() {
    withPatrolData((log, patrolData) -> {
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().startsWith(SCRATCH)) {
          patrolData.deleteShift(shift);
        }
      }
    });
  }

  /**
   * A shift whose end nobody can read is named, with the value it actually holds.
   *
   * <p>Quoting the value matters: "the end cannot be read" sends a director looking, where
   * {@code the end "Close" cannot be read as a time} tells them what to replace.
   */
  @Test
  void anUnreadableEndTimeIsListedOnThePage() throws Exception {
    writeScratchShift("8:00", "Close");

    String panel = panelOf(openPage());

    assertTrue(panel.contains("cannot be read"),
               "the panel should announce there is something to fix");
    assertTrue(panel.contains(SCRATCH), "and name the template: " + SCRATCH);
    assertTrue(panel.contains("the end &quot;Close&quot; cannot be read as a time")
               || panel.contains("the end \"Close\" cannot be read as a time"),
               "and quote the value it cannot read: " + panel);
  }

  /** A shift with no end at all is a different fix and gets a different sentence. */
  @Test
  void aMissingEndTimeIsListedToo() throws Exception {
    writeScratchShift("8:00", "");

    String panel = panelOf(openPage());

    assertTrue(panel.contains("no end time"),
               "a blank end should be named as missing, not unreadable: " + panel);
    assertTrue(panel.contains(SCRATCH));
  }

  /**
   * An untidy but readable end is <b>not</b> listed.
   *
   * <p>This is the assertion the panel earns its place with. {@code 1600} is a time; the site-wide
   * cleanup rewrites it without anybody being asked. Listing it would put hundreds of rows in front
   * of a director who can do nothing useful about them, and hide the handful that need them.
   */
  @Test
  void aReadableEndTimeIsNotListed() throws Exception {
    writeScratchShift("0800", "1600");

    String panel = panelOf(openPage());

    assertFalse(panel.contains(SCRATCH),
                "a row the cleanup can fix must not be put in front of a director: " + panel);
  }

  /**
   * Just the problems panel, not the whole page.
   *
   * <p>The template's name is in the selector drop-down as well, so a plain
   * {@code page.contains(name)} passes whether or not the panel mentions it - which is exactly
   * backwards for the test that matters here, the one asserting a row is <b>absent</b>.
   *
   * <p>Empty when the panel is not drawn at all, which is the clean case.
   */
  private String panelOf(String page) {
    int start = page.indexOf("<details");
    if (start < 0) {
      return "";
    }
    int end = page.indexOf("</details>", start);
    return end < 0 ? page.substring(start) : page.substring(start, end);
  }

  private String openPage() throws Exception {
    return mockMvc.perform(get("/EditShiftTemplates")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .header("User-Agent", BROWSER)
                             .session(directorSession()))
                  .andExpect(status().isOk())
                  .andReturn().getResponse().getContentAsString();
  }

  /** Straight to the database, the way twenty years of these rows got there. */
  private void writeScratchShift(String start, String end) {
    withPatrolData((log, patrolData) -> {
      ShiftDefinitions shift =
        new ShiftDefinitions(ShiftDefinitions.createShiftName(SCRATCH, 0), start, end, 2, 0, "", log);
      patrolData.writeShift(shift);
    });
  }

  private void withPatrolData(BiConsumer<Logger, PatrolData> work) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(directorSession());
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger log = new Logger(ShiftTimeAuditPanelTest.class, javaxRequest, "GET", "Sample", Logger.INFO);
    PatrolData patrolData = new PatrolData("Sample", new SessionData(javaxRequest, null, log), log);
    try {
      work.accept(log, patrolData);
    }
    finally {
      patrolData.close();
    }
  }

  private MockHttpSession directorSession() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");
    return session;
  }
}
