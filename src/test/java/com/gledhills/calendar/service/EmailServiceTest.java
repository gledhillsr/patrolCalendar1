package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.EmailRecipient;
import org.junit.jupiter.api.BeforeEach;
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

    @Test
    void sanitizeEmailHeader_treatsNullAsEmpty() {
        assertEquals("", service.sanitizeEmailHeader(null));
    }
}
