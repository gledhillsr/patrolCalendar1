package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.EmailRecipient;
import org.junit.jupiter.api.BeforeEach;
import com.gledhills.calendar.utils.SiteIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the message composition EmailService does before handing a batch
 * to the mail sender. Delivery itself needs a live SMTP host and is not covered here.
 */
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EmailService(mailSender);
    }

    private EmailRecipient recipient(String id, String first, String last) {
        return new EmailRecipient(id, first, last, first + "@example.com", true);
    }

    @Test
    void personalize_replacesEveryDocumentedCode() {
        String message = service.personalize("Hi $first$ $last$, member #$id$.",
                                             recipient("123", "John", "Doe"));

        assertEquals("Hi John Doe, member #123.", message);
    }

    @Test
    void personalize_leavesRetiredCreditCodesAlone() {
        String message = service.personalize("$carryovercredits$ carried over, $credit$ earned",
                                             recipient("123", "John", "Doe"));

        assertEquals("$carryovercredits$ carried over, $credit$ earned", message);
    }

    @Test
    void personalize_leavesTextWithoutCodesAlone() {
        String message = service.personalize("Shifts cost $50 - see you there.", recipient("1", "A", "B"));

        assertEquals("Shifts cost $50 - see you there.", message);
    }

    @Test
    void personalize_treatsSubstitutedNamesAsLiteralText() {
        // A name containing regex/replacement metacharacters must not corrupt the message
        String message = service.personalize("Hello $first$", recipient("1", "$1\\x", "O'Neil"));

        assertEquals("Hello $1\\x", message);
    }

    @Test
    void personalize_toleratesMissingNameValues() {
        EmailRecipient noFirstName = new EmailRecipient("9", null, "Lee", "lee@example.com", true);

        assertEquals("Hello ", service.personalize("Hello $first$", noFirstName));
    }

    @Test
    void normalizeSmartPunctuation_convertsWordCharactersToAscii() {
        String pasted = "\u201Cquoted\u201D \u2014 it\u2019s fine\u2026";

        assertEquals("\"quoted\" - it's fine...", service.normalizeSmartPunctuation(pasted));
    }

    @Test
    void normalizeSmartPunctuation_convertsMisdecodedCp1252Bytes() {
        String misdecoded = "\u0093quoted\u0094 it\u0092s";

        assertEquals("\"quoted\" it's", service.normalizeSmartPunctuation(misdecoded));
    }

    @Test
    void sanitizeEmailHeader_removesCrLfToBlockHeaderInjection() {
        assertEquals("Subject  Bcc: evil@example.com",
                     service.sanitizeEmailHeader("Subject\r\nBcc: evil@example.com"));
    }

    /**
     * A site-wide mailing is composed against whichever resort the owner was on, so naming that
     * resort in the sign-off would tell a director at 46 other patrols that site news came from
     * Brighton's web site.
     */
    @Test
    void buildFooter_omitsTheResortLineForASiteWideBatch() {
        String footer = service.buildFooter(batch(true), "Brighton Ski Patrol");

        assertFalse(footer.contains("online scheduling web site"),
                    "a site-wide send should not name one resort's web site: " + footer);
        assertFalse(footer.contains("Brighton"), "nor the resort at all: " + footer);
        assertTrue(footer.contains("This message sent by:"), "the sender line stays: " + footer);
        assertTrue(footer.contains(SiteIdentity.PATROL_CALENDAR_DISPLAY + " auto mailer"),
                   "and so does the sign-off, naming the site: " + footer);
    }

    /** An ordinary resort email is unchanged - it still says whose web site it came from. */
    @Test
    void buildFooter_keepsTheResortLineForAnOrdinaryBatch() {
        String footer = service.buildFooter(batch(false), "Brighton Ski Patrol");

        assertTrue(footer.contains("from Brighton Ski Patrol's online scheduling web site."),
                   "the resort line belongs on a normal send: " + footer);
    }

    /**
     * A site-wide mailing comes from the site's own address.
     *
     * That is also the account the SMTP session authenticates as, so it is the aligned choice, and
     * it puts replies in the site administrator's inbox - which is why the mailing exists.
     */
    @Test
    void verifiedFromAddress_usesTheSiteAddressForASiteWideSend() {
        assertEquals("steve@gledhills.com", service.verifiedFromAddress("Brighton", true),
                     "a site-wide send should not go out as one resort");
    }

    /** A resort with a verified address of its own still uses it. */
    @Test
    void verifiedFromAddress_usesTheResortAddressForAnOrdinarySend() {
        assertEquals("brightonskipatrol@gmail.com", service.verifiedFromAddress("Brighton", false),
                     "an ordinary Brighton email should come from Brighton");
    }

    /** A resort without one falls back to the site address, as it always has. */
    @Test
    void verifiedFromAddress_fallsBackForAResortWithoutOne() {
        assertEquals("steve@gledhills.com", service.verifiedFromAddress("Afton", false),
                     "a resort with no verified address falls back to the site's");
    }

    /**
     * The stale apology is gone.
     *
     * Afton has no verified From address, which is what used to trigger "I am working on setting
     * this 'From' address to be specific to your patrol" - along with an instruction not to reply
     * that was wrong anyway, since the message carries a Reply-To for the sender.
     */
    @Test
    void buildFooter_doesNotApologiseForTheFromAddress() {
        String footer = service.buildFooter(
            new EmailService.Batch("S", "B", "steve@gledhills.com", "director@example.com",
                                   null, true, "Afton", java.util.List.of(), false),
            "Afton Alps");

        assertFalse(footer.contains("I am working on"), "the promise was kept: " + footer);
        assertFalse(footer.contains("Don't respond"),
                    "and replying works - there is a Reply-To: " + footer);
    }

    /** A batch with a sender who has a usable address, so the footer takes its plain form. */
    private EmailService.Batch batch(boolean siteWide) {
        return new EmailService.Batch("Subject", "Body", "from@example.com", "reply@example.com",
                                      null, true, "Brighton", java.util.List.of(), siteWide);
    }

    @Test
    void sanitizeEmailHeader_treatsNullAsEmpty() {
        assertEquals("", service.sanitizeEmailHeader(null));
    }
}
