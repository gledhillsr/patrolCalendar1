package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.EmailCompositionForm;
import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.service.EmailService;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * What the site-wide director POST actually hands to the mailer.
 *
 * EmailService is mocked, which is the only reason these tests may post a *valid* message: the
 * real one would mail every director at every resort. What is asserted is the thing that broke in
 * production - which of the two sendEmailsAsync overloads was chosen, and with whom.
 *
 * The bug this exists for: the audience was posted twice, once in the form action's query string
 * and once as a hidden field. Spring joins duplicate parameter values with a comma, so the
 * controller saw "siteDirectors,siteDirectors", took the ordinary path, and tried to resolve
 * "Brighton:192443" against Brighton's roster - "0 of 0 emails sent", with nothing on the page to
 * say the recipient list had been swapped.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class SiteDirectorsSendTest {

    private static final String OWNER_RESORT = "Brighton";
    private static final String OWNER_ID = "192443";
    private static final String BROWSER =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private EmailService emailService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /** The whole point: the selected director is mailed, by the overload that knows their resort. */
    @Test
    void theSelectedDirectorIsHandedToTheMailer() throws Exception {
        send(post().param("audience", "siteDirectors")
                   .param("patrollerIds", "Brighton:" + OWNER_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailRecipient>> recipients = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendEmailsAsync(any(EmailCompositionForm.class), any(PatrolData.class),
                                             any(Logger.class), recipients.capture());
        verify(emailService, never()).sendEmailsAsync(any(), any(), any());

        assertEquals(1, recipients.getValue().size(), "exactly the one selected director");
        EmailRecipient only = recipients.getValue().get(0);
        assertEquals("Brighton:" + OWNER_ID, only.getSelectionKey(), "resolved to the right person");
        assertTrue(only.hasValidEmail(), "and with a usable address");
    }

    /**
     * The production failure, pinned.
     *
     * A browser submitting the audience twice must not silently become an ordinary send. Before
     * the fix this returned 200, mailed nobody, and told the user it had queued the batch.
     */
    @Test
    void aDuplicatedAudienceIsRefusedRatherThanDowngraded() throws Exception {
        int status = send(post().param("audience", "siteDirectors", "siteDirectors")
                                .param("patrollerIds", "Brighton:" + OWNER_ID));

        assertEquals(400, status,
                     "a doubled audience must not fall back to this resort's roster");
        verify(emailService, never()).sendEmailsAsync(any(), any(), any());
        verify(emailService, never()).sendEmailsAsync(any(), any(), any(), any());
    }

    /** Anything else unrecognised is refused for the same reason. */
    @Test
    void anUnknownAudienceIsRefused() throws Exception {
        assertEquals(400, send(post().param("audience", "everyoneEverywhere")
                                     .param("patrollerIds", "Brighton:" + OWNER_ID)),
                     "an audience this controller does not know must not be guessed at");
        verify(emailService, never()).sendEmailsAsync(any(), any(), any(), any());
    }

    /**
     * An id the owner did not get from the page is dropped, not mailed.
     *
     * The list is re-derived on the server and the request may only narrow it - otherwise the form
     * would be a way to post an arbitrary address and have the site send to it.
     */
    @Test
    void anIdThatIsNotADirectorsIsDropped() throws Exception {
        send(post().param("audience", "siteDirectors")
                   .param("patrollerIds", "Brighton:" + OWNER_ID)
                   .param("patrollerIds", "Brighton:999999")
                   .param("patrollerIds", "NoSuchResort:1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailRecipient>> recipients = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendEmailsAsync(any(EmailCompositionForm.class), any(PatrolData.class),
                                             any(Logger.class), recipients.capture());

        assertEquals(1, recipients.getValue().size(),
                     "only the real director should have survived: " + recipients.getValue());
    }

    /** An ordinary resort email still takes the ordinary path - no audience, no change. */
    @Test
    void anOrdinaryEmailIsUnaffected() throws Exception {
        send(post().param("patrollerIds", OWNER_ID));

        verify(emailService).sendEmailsAsync(any(EmailCompositionForm.class), any(PatrolData.class),
                                             any(Logger.class));
        verify(emailService, never()).sendEmailsAsync(any(), any(), any(), any());
    }

    private MockMultipartHttpServletRequestBuilder post() {
        return multipart("/EmailForm")
            .param("resort", OWNER_RESORT)
            .param("fromPatrollerId", OWNER_ID)
            .param("subject", "Scheduled maintenance")
            .param("message", "The site will be down briefly on Tuesday.");
    }

    private int send(MockMultipartHttpServletRequestBuilder builder) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loggedInUserId", OWNER_ID);
        session.setAttribute("loggedInResort", OWNER_RESORT);

        return mockMvc.perform(builder.header("User-Agent", BROWSER).session(session))
            .andReturn().getResponse().getStatus();
    }
}
