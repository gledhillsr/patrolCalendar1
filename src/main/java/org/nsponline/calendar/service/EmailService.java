package org.nsponline.calendar.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.nsponline.calendar.dto.EmailCompositionForm;
import org.nsponline.calendar.dto.EmailRecipient;
import org.nsponline.calendar.dto.EmailSendResult;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.Logger;
import org.nsponline.calendar.utils.PatrolData;
import org.nsponline.calendar.utils.ResortData;
import org.nsponline.calendar.utils.StaticUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for composing and sending emails asynchronously.
 */
@Service
public class EmailService {
    private static final String FALLBACK_FROM_ADDRESS = "steve@gledhills.com";

    private final JavaMailSender mailSender;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Send emails asynchronously to selected patrol members.
     *
     * @param form Email composition form data
     * @param patrolData Patrol data accessor
     * @param logger Logger instance
     * @return Email send result with tracking ID
     */
    public EmailSendResult sendEmailsAsync(EmailCompositionForm form, PatrolData patrolData, Logger logger) {
        String trackingId = UUID.randomUUID().toString();

        // Load from member
        Roster fromMember = patrolData.getMemberByID(form.getFromPatrollerId());
        String fromEmail = getFromEmailAddress(fromMember, form.getResort(), logger);
        boolean hasValidReturnEmail = fromMember != null &&
            StaticUtils.isValidEmailAddress(fromMember.getEmailAddress());

        // Load recipients
        List<EmailRecipient> recipients = new ArrayList<>();
        for (String memberId : form.getPatrollerIds()) {
            Roster member = patrolData.getMemberByID(memberId);
            if (member != null && StaticUtils.isValidEmailAddress(member.getEmailAddress())) {
                recipients.add(new EmailRecipient(
                    member.getID(),
                    member.getFirst(),
                    member.getLast(),
                    member.getEmailAddress(),
                    true,
                    new java.util.HashMap<>(),
                    member.getCarryOverCredits(),
                    member.getCreditsEarned()
                ));
            }
        }

        // Detect template variables
        boolean hasTemplateVars = form.getMessage().contains("$first$") ||
                                 form.getMessage().contains("$last$") ||
                                 form.getMessage().contains("$id$") ||
                                 form.getMessage().contains("$carryovercredits$") ||
                                 form.getMessage().contains("$credit$");

        // Send async
        sendBatch(recipients, form.getSubject(), form.getMessage(), fromEmail,
                 fromMember, hasValidReturnEmail, form.getResort(), hasTemplateVars, logger);

        return new EmailSendResult(trackingId, recipients.size(), LocalDateTime.now());
    }

    @Async
    void sendBatch(List<EmailRecipient> recipients, String subject, String messageTemplate,
                   String fromEmail, Roster fromMember, boolean hasValidReturnEmail,
                   String resort, boolean hasTemplateVars, Logger logger) {

        String fullPatrolName = PatrolData.getResortFullName(resort, logger);
        String sanitizedSubject = sanitizeEmailHeader(subject);

        for (EmailRecipient recipient : recipients) {
            try {
                String message = buildMessage(messageTemplate, recipient, fromMember,
                                            hasValidReturnEmail, fullPatrolName, resort, hasTemplateVars);

                MimeMessage mimeMessage = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");
                helper.setFrom(fromEmail);
                helper.setTo(recipient.getEmailAddress());
                helper.setSubject(sanitizedSubject);
                helper.setText(message, false);

                mailSender.send(mimeMessage);

                logger.info("Email sent to " + recipient.getFullNameLastFirst() +
                           " at " + recipient.getEmailAddress());
            } catch (MessagingException e) {
                logger.logException("Failed to send email to " + recipient.getEmailAddress(), e);
            }
        }
    }

    private String getFromEmailAddress(Roster fromMember, String resort, Logger logger) {
        if (fromMember == null) {
            return FALLBACK_FROM_ADDRESS;
        }

        String memberEmail = fromMember.getEmailAddress();
        if (StaticUtils.isValidEmailAddress(memberEmail)) {
            return memberEmail;
        }

        return FALLBACK_FROM_ADDRESS;
    }

    private String buildMessage(String template, EmailRecipient recipient, Roster fromMember,
                               boolean hasValidReturnEmail, String fullPatrolName, String resort,
                               boolean hasTemplateVars) {
        String message = fixMagicCharactersFromMicrosoftWordPaste(template);

        if (hasTemplateVars) {
            message = message.replaceAll("\\$first\\$", recipient.getFirstName());
            message = message.replaceAll("\\$last\\$", recipient.getLastName());
            message = message.replaceAll("\\$id\\$", recipient.getId());
            message = message.replaceAll("\\$carryovercredits\\$", recipient.getCarryOverCredits());
            message = message.replaceAll("\\$credit\\$", recipient.getCreditsEarned());
        }

        message += buildFooter(fromMember, hasValidReturnEmail, fullPatrolName, resort);

        return message;
    }

    private String buildFooter(Roster fromMember, boolean hasValidReturnEmail,
                              String fullPatrolName, String resort) {
        StringBuilder footer = new StringBuilder();
        footer.append("\n\n----------------------------------------------\n");

        if (fromMember != null) {
            footer.append("This message sent by: ").append(fromMember.getFullName())
                  .append(" at ( ").append(fromMember.getEmailAddress()).append(" )\n");
        } else {
            footer.append("This message sent by: unknown sender\n");
        }

        footer.append("from ").append(fullPatrolName).append("'s online scheduling web site.\n\n");

        if (!hasValidReturnEmail) {
            footer.append("Please Don't respond to this email. SEND any responses\n");
            if (fromMember != null) {
                footer.append("to: ").append(fromMember.getFullName())
                      .append(", who had an invalid email address in the system.\n\n");
            }
        } else {
            ResortData resortInfo = PatrolData.getResortInfo(resort);
            String directorsVerifiedEmail = (resortInfo != null &&
                StaticUtils.isNotEmpty(resortInfo.getDirectorsVerifiedEmail())) ?
                resortInfo.getDirectorsVerifiedEmail() : null;

            if (directorsVerifiedEmail == null) {
                footer.append("Please Don't respond to this email. SEND any responses\n");
                if (fromMember != null) {
                    footer.append("to: ").append(fromMember.getEmailAddress())
                          .append(". I am working on setting this 'From' address to be specific to your patrol.\n\n");
                }
            }
        }

        footer.append("This was sent from the Ski Patrol Web Site Auto Mailer.\n");
        footer.append("--------------------------------------------------------\n");

        return footer.toString();
    }

    private String fixMagicCharactersFromMicrosoftWordPaste(String message) {
        char[] chars = message.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 128) {
                if (chars[i] == 146) chars[i] = '\'';
                else if (chars[i] == 147) chars[i] = '"';
                else if (chars[i] == 148) chars[i] = '"';
            }
        }
        return new String(chars);
    }

    /**
     * Sanitize email header to prevent header injection attacks.
     * Removes carriage return and newline characters.
     *
     * @param header Email header value (e.g., subject)
     * @return Sanitized header with CR/LF removed
     */
    private String sanitizeEmailHeader(String header) {
        if (header == null) {
            return "";
        }
        return header.replace("\r", " ").replace("\n", " ");
    }
}
