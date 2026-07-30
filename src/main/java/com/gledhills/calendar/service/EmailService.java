package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.EmailCompositionForm;
import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.ResortData;
import com.gledhills.calendar.utils.StaticUtils;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for composing and sending emails asynchronously.
 */
@Service
public class EmailService {
    private static final String FALLBACK_FROM_ADDRESS = "steve@gledhills.com";

    private final JavaMailSender mailSender;

    // Self-injection via @Lazy gives us the Spring proxy so @Async on sendBatch actually fires asynchronously
    @Autowired @Lazy
    private EmailService self;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Hand the composed message to the background mailer.
     *
     * @param form Email composition form data
     * @param patrolData Patrol data accessor
     * @param logger Logger instance
     * @return number of recipients queued for delivery
     */
    public int sendEmailsAsync(EmailCompositionForm form, PatrolData patrolData, Logger logger) {
        List<EmailRecipient> recipients = resolveRecipients(form, patrolData);

        // Call via self (the proxied instance) so @Async takes effect
        self.sendBatch(recipients, buildBatch(form, patrolData), logger);

        return recipients.size();
    }

    /**
     * Compose a batch exactly as {@link #sendBatch} would and describe it, without sending
     * anything. Used by the demo resort, where delivery is disabled.
     *
     * @return a multi-line description: sender, recipients, subject and the finished body
     */
    public String describeUnsentBatch(EmailCompositionForm form, PatrolData patrolData, Logger logger) {
        List<EmailRecipient> recipients = resolveRecipients(form, patrolData);
        Batch batch = buildBatch(form, patrolData);
        String footer = buildFooter(batch, PatrolData.getResortFullName(batch.resort(), logger));

        StringBuilder description = new StringBuilder();
        description.append("From: ").append(batch.fromEmail());
        if (batch.replyToEmail() != null) {
            description.append("\nReply-To: ").append(batch.replyToEmail());
        }
        description.append("\nSubject: ").append(batch.subject())
                   .append("\nTo (").append(recipients.size()).append(" recipients):");
        for (EmailRecipient recipient : recipients) {
            description.append("\n  ").append(recipient.getFullNameLastFirst())
                       .append(" <").append(recipient.getEmailAddress()).append(">");
        }

        for (Attachment attachment : batch.attachments()) {
            description.append("\nAttachment: ").append(attachment.filename())
                       .append(" (").append(attachment.contentType())
                       .append(", ").append(attachment.content().length).append(" bytes)");
        }

        if (recipients.isEmpty()) {
            description.append("\nBody:\n").append(normalizeSmartPunctuation(batch.messageTemplate()));
        } else {
            EmailRecipient first = recipients.get(0);
            description.append("\nBody, as ").append(first.getFullNameLastFirst()).append(" would receive it:\n")
                       .append(personalize(batch.messageTemplate(), first));
        }
        return description.append(footer).toString();
    }

    private List<EmailRecipient> resolveRecipients(EmailCompositionForm form, PatrolData patrolData) {
        List<EmailRecipient> recipients = new ArrayList<>();
        if (form.getPatrollerIds() == null) {
            return recipients;
        }
        for (String memberId : form.getPatrollerIds()) {
            Roster member = patrolData.getMemberByID(memberId);
            if (member != null && StaticUtils.isValidEmailAddress(member.getEmailAddress())) {
                recipients.add(new EmailRecipient(
                    member.getID(),
                    member.getFirst(),
                    member.getLast(),
                    member.getEmailAddress(),
                    true
                ));
            }
        }
        return recipients;
    }

    private Batch buildBatch(EmailCompositionForm form, PatrolData patrolData) {
        Roster fromMember = patrolData.getMemberByID(form.getFromPatrollerId());
        boolean hasValidReturnEmail = fromMember != null &&
            StaticUtils.isValidEmailAddress(fromMember.getEmailAddress());

        return new Batch(
            sanitizeEmailHeader(form.getSubject()),
            form.getMessage(),
            verifiedFromAddress(form.getResort()),
            hasValidReturnEmail ? fromMember.getEmailAddress() : null,
            fromMember,
            hasValidReturnEmail,
            form.getResort(),
            readAttachments(form));
    }

    /**
     * The From address has to be one this server is authorised to send for, or the message
     * fails SPF/DKIM alignment and the receiver scores it as spam — Verizon/AOL/Yahoo reject
     * outright ("554 5.7.1 [VI-101]"), and an attachment is usually what tips it over. The
     * member's own address goes in Reply-To instead, the same split MailMan has always used.
     */
    private String verifiedFromAddress(String resort) {
        ResortData resortInfo = PatrolData.getResortInfo(resort);
        return hasDirectorsVerifiedEmail(resort)
            ? resortInfo.getDirectorsVerifiedEmail()
            : FALLBACK_FROM_ADDRESS;
    }

    /**
     * Read the uploaded files into memory on the request thread. The temp files behind a
     * MultipartFile are deleted once the request completes, which is before the @Async
     * send runs, so the bytes have to be copied now.
     */
    private List<Attachment> readAttachments(EmailCompositionForm form) {
        List<Attachment> attachments = new ArrayList<>();
        for (MultipartFile file : form.getAttachments()) {
            try {
                attachments.add(new Attachment(
                    sanitizeEmailHeader(StringUtils.getFilename(file.getOriginalFilename())),
                    // the browser does not always send one, and a null here blows up the DataSource
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream",
                    file.getBytes()));
            } catch (IOException e) {
                throw new IllegalStateException("Could not read attachment " + file.getOriginalFilename(), e);
            }
        }
        return attachments;
    }

    /**
     * Everything about a send that is the same for every recipient.
     */
    record Batch(String subject, String messageTemplate, String fromEmail, String replyToEmail,
                 Roster fromMember, boolean hasValidReturnEmail, String resort,
                 List<Attachment> attachments) {
    }

    /** An uploaded file, already read into memory so the async send can use it. */
    record Attachment(String filename, String contentType, byte[] content) {
    }

    @Async("emailTaskExecutor")
    void sendBatch(List<EmailRecipient> recipients, Batch batch, Logger logger) {
        String footer = buildFooter(batch, PatrolData.getResortFullName(batch.resort(), logger));

        boolean multipart = !batch.attachments().isEmpty();

        // MULTIPART_MODE_MIXED, not the boolean overload's MIXED_RELATED: a multipart/related
        // wrapping nothing but the plain-text body is the shape bulk mailers produce, and spam
        // filters score it. Plain body + files is exactly what multipart/mixed is for.
        int multipartMode = multipart
            ? MimeMessageHelper.MULTIPART_MODE_MIXED
            : MimeMessageHelper.MULTIPART_MODE_NO;

        for (EmailRecipient recipient : recipients) {
            try {
                MimeMessage mimeMessage = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, multipartMode, "utf-8");
                helper.setFrom(batch.fromEmail());
                if (batch.replyToEmail() != null) {
                    helper.setReplyTo(batch.replyToEmail());
                }
                helper.setTo(recipient.getEmailAddress());
                helper.setSubject(batch.subject());
                helper.setText(personalize(batch.messageTemplate(), recipient) + footer, false);

                for (Attachment attachment : batch.attachments()) {
                    helper.addAttachment(attachment.filename(),
                                         new ByteArrayResource(attachment.content()),
                                         attachment.contentType());
                }

                mailSender.send(mimeMessage);

                logger.info("Email sent to " + recipient.getFullNameLastFirst() +
                           " at " + recipient.getEmailAddress() +
                           (multipart ? " with " + batch.attachments().size() + " attachment(s)" : ""));
            } catch (MessagingException | MailException e) {
                logger.logException("Failed to send email to " + recipient.getEmailAddress(), e);
            }
        }
    }

    /** Substitute the $...$ replacement codes documented on the email form. */
    String personalize(String template, EmailRecipient recipient) {
        String message = normalizeSmartPunctuation(template);
        message = message.replace("$first$", nullSafe(recipient.getFirstName()));
        message = message.replace("$last$", nullSafe(recipient.getLastName()));
        message = message.replace("$id$", nullSafe(recipient.getId()));
        return message;
    }

    private String buildFooter(Batch batch, String fullPatrolName) {
        Roster fromMember = batch.fromMember();
        StringBuilder footer = new StringBuilder();
        footer.append("\n\n----------------------------------------------\n");

        if (fromMember != null) {
            footer.append("This message sent by: ").append(fromMember.getFullName())
                  .append(" at ( ").append(fromMember.getEmailAddress()).append(" )\n");
        } else {
            footer.append("This message sent by: unknown sender\n");
        }

        footer.append("from ").append(fullPatrolName).append("'s online scheduling web site.\n\n");

        if (!batch.hasValidReturnEmail()) {
            footer.append("Please Don't respond to this email. SEND any responses\n");
            if (fromMember != null) {
                footer.append("to: ").append(fromMember.getFullName())
                      .append(", who had an invalid email address in the system.\n\n");
            }
        } else if (!hasDirectorsVerifiedEmail(batch.resort())) {
            footer.append("Please Don't respond to this email. SEND any responses\n");
            footer.append("to: ").append(fromMember.getEmailAddress())
                  .append(". I am working on setting this 'From' address to be specific to your patrol.\n\n");
        }

        footer.append("This was sent from the Ski Patrol Web Site Auto Mailer.\n");
        footer.append("--------------------------------------------------------\n");

        return footer.toString();
    }

    private boolean hasDirectorsVerifiedEmail(String resort) {
        ResortData resortInfo = PatrolData.getResortInfo(resort);
        return resortInfo != null && StaticUtils.isNotEmpty(resortInfo.getDirectorsVerifiedEmail());
    }

    /**
     * Replace the curly quotes and dashes that arrive when a message is pasted from
     * Microsoft Word with their plain ASCII equivalents.
     */
    String normalizeSmartPunctuation(String message) {
        StringBuilder plain = new StringBuilder(message.length());
        for (char c : message.toCharArray()) {
            switch (c) {
                // cp1252 bytes that survived a bad decode, plus the real Unicode punctuation
                case '\u0091', '\u0092', '\u2018', '\u2019' -> plain.append('\'');
                case '\u0093', '\u0094', '\u201C', '\u201D' -> plain.append('"');
                case '\u0096', '\u0097', '\u2013', '\u2014' -> plain.append('-');
                case '\u0085', '\u2026' -> plain.append("...");
                default -> plain.append(c);
            }
        }
        return plain.toString();
    }

    /**
     * Strip CR/LF from an email header value to prevent header injection.
     */
    String sanitizeEmailHeader(String header) {
        if (header == null) {
            return "";
        }
        return header.replace("\r", " ").replace("\n", " ");
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
