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
import static org.junit.jupiter.api.Assertions.assertNull;
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
class EmailSendProgressTest {

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

    /** The four states a director can be shown, in the words they are shown in. */
    @Test
    void aBatchInFlightCountsUp() {
        EmailBatchLog.Send send = batchLog.start("ZZProgress", "Deb Mabbutt", "Season opening", 32);

        assertFalse(send.finished());
        assertEquals("Email sending in progress - 0 of 32 sent so far", send.describeStatus());

        batchLog.recordSent(send);
        batchLog.recordSent(send);
        batchLog.recordSent(send);
        batchLog.recordSent(send);
        batchLog.recordSent(send);

        assertEquals("Email sending in progress - 5 of 32 sent so far", send.describeStatus(),
                     "the phrasing a director reads while it runs");
    }

    @Test
    void aBatchThatWentOutInFullSaysSo() {
        EmailBatchLog.Send send = batchLog.start("ZZClean", "Deb Mabbutt", "Season opening", 35);
        for (int i = 0; i < 35; i++) {
            batchLog.recordSent(send);
        }
        batchLog.finish(send, null);

        assertTrue(send.wentOutCleanly());
        assertFalse(send.stoppedEarly());
        assertEquals(0, send.notDelivered());
        assertEquals("All 35 emails sent successfully", send.describeStatus());
    }

    /** The SoldierMountain case: the server refused the sender, so nothing was attempted. */
    @Test
    void aBatchThatNeverLeftSaysToTryAgain() {
        EmailBatchLog.Send send = batchLog.start("ZZDead", "Dave Ulrich", "Shift signups", 32);
        batchLog.finish(send, "535-5.7.8 Username and Password not accepted");

        assertFalse(send.wentOutCleanly());
        assertTrue(send.stoppedEarly());
        assertEquals(32, send.notDelivered(),
                     "all 32 are undelivered, not just the ones attempted");
        assertEquals("32 of 32 failed to send. Please try again later", send.describeStatus());
    }

    /** A partial failure is its own state, and must not read as either of the other two. */
    @Test
    void aPartialFailureNamesHowManyFailed() {
        EmailBatchLog.Send send = batchLog.start("ZZPartial", "Someone", "Subject", 32);
        for (int i = 0; i < 28; i++) {
            batchLog.recordSent(send);
        }
        batchLog.finish(send, null);

        assertEquals("28 of 32 sent - 4 failed", send.describeStatus());
    }

    /** The time is the resort's, not the server's - a director reads it on their own clock. */
    @Test
    void theTimeIsShownInTheResortsZone() {
        EmailBatchLog.Send send = batchLog.start("ZZZone", "Someone", "Subject", 1);

        //both zones describe the same instant, so they must differ by the zone offset
        String denver = send.describeWhen(ZoneId.of("America/Denver"));
        String chicago = send.describeWhen(ZoneId.of("America/Chicago"));
        assertFalse(denver.equals(chicago),
                    "Denver and Chicago are an hour apart: " + denver + " vs " + chicago);
    }


    /** A batch that aged out is no longer findable by id, so the page stops asking about it. */
    /** Bounded: the map cannot grow without limit over an uptime of months. */
    @Test
    void aBatchThatAgedOutIsNoLongerTracked() {
        String firstId = batchLog.start("ZZAgedOut", "s", "the first one", 1).id();
        for (int i = 0; i < EmailBatchLog.TRACKED; i++) {
            batchLog.start("ZZAgedOut", "s", "later " + i, 1);
        }

        assertNull(batchLog.find(firstId),
                   "the oldest send should have dropped out once TRACKED was reached");
    }

    @Test
    void anUnknownBatchIsNotFoundRatherThanAnError() {
        assertNull(batchLog.find("nothing-with-this-id"));
        assertNull(batchLog.find(null), "a null id must not throw");
    }

    /** An in-progress batch is not a failure, and must not be described as one. */
    @Test
    void aBatchStillRunningIsNotDescribedAsFailed() {
        EmailBatchLog.Send send = batchLog.start("ZZRunning", "s", "still going", 10);

        assertFalse(send.finished());
        assertFalse(send.describeStatus().contains("failed"),
                    "nothing has failed yet: " + send.describeStatus());
    }

    // ------------------------------------------------- the pages    // ------------------------------------------------- the polling endpoint

    /** The line counts up because this endpoint reports the running total. */
    @Test
    void theStatusEndpointReportsProgressWhileTheBatchRuns() throws Exception {
        EmailBatchLog.Send send = batchLog.start("Sample", "A Director", "Live subject", 32);
        batchLog.recordSent(send);
        batchLog.recordSent(send);

        String json = statusJson(send.id(), 200);

        assertTrue(json.contains("\"finished\":false"), json);
        assertTrue(json.contains("\"sent\":2"), json);
        assertTrue(json.contains("\"queued\":32"), json);
        assertTrue(json.contains("2 of 32 sent so far"), json);
    }

    /** And says it is done, so the page stops polling instead of running for five minutes. */
    @Test
    void theStatusEndpointSaysWhenTheBatchIsFinished() throws Exception {
        EmailBatchLog.Send send = batchLog.start("Sample", "A Director", "Done subject", 2);
        batchLog.recordSent(send);
        batchLog.recordSent(send);
        batchLog.finish(send, null);

        String json = statusJson(send.id(), 200);

        assertTrue(json.contains("\"finished\":true"), json);
        assertTrue(json.contains("\"cleanly\":true"), json);
    }

    @Test
    void theStatusEndpointCarriesTheReasonABatchStopped() throws Exception {
        EmailBatchLog.Send send = batchLog.start("Sample", "Dave Ulrich", "Shift signups", 32);
        batchLog.finish(send, "535-5.7.8 Username and Password not accepted");

        String json = statusJson(send.id(), 200);

        assertTrue(json.contains("\"cleanly\":false"), json);
        assertTrue(json.contains("535-5.7.8"), json);
        assertTrue(json.contains("failed to send"), json);
    }

    /**
     * A batch the log no longer holds is reported as finished-and-untracked, so the page stops
     * asking rather than polling for five minutes for something that will never appear.
     */
    @Test
    void anUnknownBatchIsReportedAsNoLongerTrackedRatherThanAnError() throws Exception {
        String json = statusJson("not-a-real-batch-id", 200);

        assertTrue(json.contains("\"found\":false"), json);
        assertTrue(json.contains("\"finished\":true"), json);
    }

    /** A subject line and a recipient count are nobody else's business. */
    @Test
    void theStatusEndpointRefusesSomebodyNotSignedIn() throws Exception {
        EmailBatchLog.Send send = batchLog.start("Sample", "A Director", "Private subject", 5);

        mockMvc.perform(get("/EmailSendStatus")
                          .param("resort", "Sample")
                          .param("batch", send.id())
                          .header("User-Agent", BROWSER))
               .andExpect(status().isForbidden());
    }

    /** ...including somebody signed in to a different resort. */
    @Test
    void theStatusEndpointRefusesASessionFromAnotherResort() throws Exception {
        EmailBatchLog.Send send = batchLog.start("Sample", "A Director", "Private subject", 5);

        MockHttpSession elsewhere = new MockHttpSession();
        elsewhere.setAttribute("loggedInUserId", "192443");
        elsewhere.setAttribute("loggedInResort", "Brighton");

        mockMvc.perform(get("/EmailSendStatus")
                          .param("resort", "Sample")
                          .param("batch", send.id())
                          .header("User-Agent", BROWSER)
                          .session(elsewhere))
               .andExpect(status().isForbidden());
    }

    private String statusJson(String batchId, int expectedStatus) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", DIRECTOR_ID);
        session.setAttribute("loggedInResort", "Sample");

        return mockMvc.perform(get("/EmailSendStatus")
                                 .param("resort", "Sample")
                                 .param("batch", batchId)
                                 .header("User-Agent", BROWSER)
                                 .session(session))
                      .andExpect(status().is(expectedStatus))
                      .andReturn().getResponse().getContentAsString();
    }

    /** The id is unguessable, but a leaked one still must not read another resort's subject. */
    @Test
    void theStatusEndpointRefusesABatchBelongingToAnotherResort() throws Exception {
        EmailBatchLog.Send brightonSend =
            batchLog.start("Brighton", "Their Director", "Their subject", 5);

        MockHttpSession sampleSession = new MockHttpSession();
        sampleSession.setAttribute("loggedInUserId", DIRECTOR_ID);
        sampleSession.setAttribute("loggedInResort", "Sample");

        mockMvc.perform(get("/EmailSendStatus")
                          .param("resort", "Sample")
                          .param("batch", brightonSend.id())
                          .header("User-Agent", BROWSER)
                          .session(sampleSession))
               .andExpect(status().isForbidden());
    }

}
