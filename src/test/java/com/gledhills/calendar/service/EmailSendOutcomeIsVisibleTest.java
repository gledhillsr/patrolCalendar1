package com.gledhills.calendar.service;

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

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A director can find out whether their bulk email actually went out.
 *
 * <p>They could not. A send is {@code @Async}, so the confirmation page renders before the batch
 * starts - and it claimed "Emails sent." and "N emails are being delivered." regardless. On
 * 5 November a SoldierMountain director read exactly that while Gmail refused the login with
 * {@code 535-5.7.8 BadCredentials} and <b>0 of 32</b> went out. The failure summary that exists to
 * catch this could not be sent either, because the mail server was the fault. Nobody knew for a
 * day, and the 32 emails were never resent.
 *
 * <p>So: the confirmation page now says only what it knows, and the outcome is recorded where the
 * director will look for it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class EmailSendOutcomeIsVisibleTest {

    private static final String DIRECTOR_ID = "123456";
    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private EmailBatchLog batchLog;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    // ------------------------------------------------- the record itself

    @Test
    void aBatchThatWentOutInFullReadsAsComplete() {
        EmailBatchLog.Entry entry =
            new EmailBatchLog.Entry("Deb Mabbutt", "Season opening", Instant.now(), 35, 35, null);

        assertTrue(entry.wentOutCleanly());
        assertFalse(entry.stoppedEarly());
        assertEquals(0, entry.notDelivered());
    }

    /** The SoldierMountain case: the server refused the sender, so nothing was attempted. */
    @Test
    void aBatchThatNeverLeftReadsAsNothingDelivered() {
        EmailBatchLog.Entry entry =
            new EmailBatchLog.Entry("Dave Ulrich", "Shift signups", Instant.now(), 32, 0,
                                    "535-5.7.8 Username and Password not accepted");

        assertFalse(entry.wentOutCleanly());
        assertTrue(entry.stoppedEarly());
        assertEquals(32, entry.notDelivered(),
                     "all 32 are undelivered, not just the ones attempted");
    }

    /** The time is the resort's, not the server's - a director reads it on their own clock. */
    @Test
    void theTimeIsShownInTheResortsZone() {
        // 2026-01-15 23:19 UTC = 4:19pm in Denver, 5:19pm in Chicago
        EmailBatchLog.Entry entry = new EmailBatchLog.Entry(
            "Someone", "Subject", Instant.parse("2026-01-15T23:19:00Z"), 1, 1, null);

        assertTrue(entry.describeWhen(ZoneId.of("America/Denver")).contains("4:19pm"),
                   entry.describeWhen(ZoneId.of("America/Denver")));
        assertTrue(entry.describeWhen(ZoneId.of("America/Chicago")).contains("5:19pm"),
                   entry.describeWhen(ZoneId.of("America/Chicago")));
    }

    /** Bounded, because this is held in memory for the life of the process. */
    @Test
    void onlyTheMostRecentFewAreKept() {
        String resort = "ZZTestResort";
        for (int i = 0; i < EmailBatchLog.KEPT_PER_RESORT + 7; i++) {
            batchLog.record(resort, new EmailBatchLog.Entry(
                "s", "subject " + i, Instant.now(), 1, 1, null));
        }

        assertEquals(EmailBatchLog.KEPT_PER_RESORT, batchLog.recent(resort).size());
        assertTrue(batchLog.recent(resort).get(0).subject().endsWith(
                     String.valueOf(EmailBatchLog.KEPT_PER_RESORT + 6)),
                   "most recent first");
    }

    @Test
    void anUnknownResortHasNoRecordRatherThanAnError() {
        assertTrue(batchLog.recent("NeverSentAnything").isEmpty());
        assertFalse(batchLog.anyIncomplete("NeverSentAnything"));
        assertTrue(batchLog.recent(null).isEmpty(), "a null resort must not throw");
    }

    // ------------------------------------------------- the pages

    /**
     * The wording that misled a director is gone. "Emails sent." was a claim the page had no way
     * to make.
     */
    @Test
    void theConfirmationPageNoLongerClaimsTheEmailWasSent() throws Exception {
        String raw = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/main/resources/templates/email-confirmation.html")));
        //HTML comments stripped first: the comment explaining the change quotes the old wording,
        //so a bare contains() would fail on the explanation rather than on the page
        String template = raw.replaceAll("(?s)<!--.*?-->", "");

        assertFalse(template.contains("<h2>Emails sent.</h2>"),
                    "the page cannot know this: the send has not started when it renders");
        assertFalse(template.contains("emails are being delivered"),
                    "nor this");
        assertTrue(template.contains("queued"),
                   "it should say what it does know");
        assertTrue(template.contains("Recent sends"),
                   "and point at where the outcome will appear");
    }

    /** The panel is on the form, and says so plainly when it has nothing recorded. */
    @Test
    void theFormShowsTheRecentSendsPanel() throws Exception {
        String page = emailFormPage();

        assertTrue(page.contains("Recent sends"), "the panel should be drawn");
        assertTrue(page.contains("last restarted"),
                   "an empty list must not read as 'nothing was sent': " + panelOf(page));
    }

    /** A batch that did not go out in full is shown, with the reason, and opened by default. */
    @Test
    void anIncompleteBatchIsShownWithItsReason() throws Exception {
        batchLog.record("Sample", new EmailBatchLog.Entry(
            "Test Director", "A subject nobody else uses", Instant.now(), 32, 0,
            "535-5.7.8 Username and Password not accepted"));

        String panel = panelOf(emailFormPage());

        assertTrue(panel.contains("A subject nobody else uses"), panel);
        assertTrue(panel.contains("0 of 32"), "the counts a director needs: " + panel);
        assertTrue(panel.contains("535-5.7.8"), "and why it stopped: " + panel);
        assertTrue(panel.contains("did not go out in full"),
                   "the summary line should flag it so a collapsed panel still warns: " + panel);
    }

    // ------------------------------------------------- helpers

    /** Just the panel, so a word appearing elsewhere on a long page cannot pass the test. */
    private String panelOf(String page) {
        int start = page.indexOf("recent-sends");
        assertTrue(start > 0, "the panel should be on the page");
        int end = page.indexOf("</details>", start);
        return page.substring(start, end < 0 ? page.length() : end);
    }

    private String emailFormPage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", DIRECTOR_ID);
        session.setAttribute("loggedInResort", "Sample");

        return mockMvc.perform(get("/EmailForm")
                                 .param("resort", "Sample")
                                 .param("ID", DIRECTOR_ID)
                                 .header("User-Agent", BROWSER)
                                 .session(session))
                      .andExpect(status().isOk())
                      .andReturn().getResponse().getContentAsString();
    }
}
