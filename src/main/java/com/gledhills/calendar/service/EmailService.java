package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.EmailCompositionForm;
import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.ResortData;
import com.gledhills.calendar.utils.SiteIdentity;
import com.gledhills.calendar.utils.StaticUtils;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service for composing and sending emails asynchronously.
 */
@Service
public class EmailService {
    private static final String FALLBACK_FROM_ADDRESS = SiteIdentity.FROM_EMAIL;
    /** Where a batch that did not go out cleanly gets reported, alongside the director. */
    private static final String ADMIN_NOTIFICATION_ADDRESS = SiteIdentity.ADMIN_EMAIL;

    private final JavaMailSender mailSender;

    // Self-injection via @Lazy gives us the Spring proxy so @Async on sendBatch actually fires asynchronously
    @Autowired @Lazy
    private EmailService self;

    /**
     * Where a batch's progress and outcome are left for the director to find.
     *
     * <p>Initialised as well as injected: EmailServiceSmtpConnectionTest constructs this service
     * directly to count SMTP logins, so field injection never runs there. A throwaway log records
     * into nothing, which is right for that test and saves a null check at every use.
     */
    @Autowired
    private EmailBatchLog batchLog = new EmailBatchLog();

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Hand the composed message to the background mailer.
     *
     * @param form Email composition form data
     * @param patrolData Patrol data accessor
     * @param logger Logger instance
     * @return the registered batch, so the page that follows can report its progress. Never null
     *         in production; a mocked EmailService returns null and callers must allow for it.
     */
    public EmailBatchLog.Send sendEmailsAsync(EmailCompositionForm form, PatrolData patrolData,
                                              Logger logger) {
        List<EmailRecipient> recipients = resolveRecipients(form, patrolData);
        Batch batch = buildBatch(form, patrolData, false);

        //registered on THIS thread, before the async dispatch, so the confirmation page rendered
        //next can already find the batch and start reporting on it
        EmailBatchLog.Send send = batchLog.start(batch.resort(), senderDescription(batch),
                                                 batch.subject(), recipients.size());
        self.sendBatch(recipients, batch, logger, send);

        return send;
    }

    /**
     * Send to a recipient list the caller has already worked out.
     *
     * The site-wide director mailing needs this: its people come from every resort's database,
     * and {@link #resolveRecipients} looks each id up in the one resort the form was posted from.
     * That would not merely miss them - Brighton has a member 192443 too, so an id belonging to
     * another resort's director could quietly resolve to a different person and mail them instead.
     *
     * The list must be one the caller derived itself, never one built from what the browser sent:
     * addresses arriving in a request and being mailed as-is is the definition of an open relay.
     *
     * @param recipients who to send to; the form's patrollerIds are ignored
     */
    public EmailBatchLog.Send sendEmailsAsync(EmailCompositionForm form, PatrolData patrolData,
                                              Logger logger, List<EmailRecipient> recipients) {
        // Call via self (the proxied instance) so @Async takes effect.
        // siteWide=true because this overload exists for exactly one caller - the cross-resort
        // director mailing. Should anything else ever need a pre-resolved list, this has to
        // become a parameter rather than stay an assumption.
        Batch batch = buildBatch(form, patrolData, true);
        EmailBatchLog.Send send = batchLog.start(batch.resort(), senderDescription(batch),
                                                 batch.subject(), recipients.size());
        self.sendBatch(recipients, batch, logger, send);

        return send;
    }

    /**
     * Compose a batch exactly as {@link #sendBatch} would and describe it, without sending
     * anything. Used by the demo resort, where delivery is disabled.
     *
     * @return a multi-line description: sender, recipients, subject and the finished body
     */
    public String describeUnsentBatch(EmailCompositionForm form, PatrolData patrolData, Logger logger) {
        List<EmailRecipient> recipients = resolveRecipients(form, patrolData);
        Batch batch = buildBatch(form, patrolData, false);
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

    private Batch buildBatch(EmailCompositionForm form, PatrolData patrolData, boolean siteWide) {
        Roster fromMember = patrolData.getMemberByID(form.getFromPatrollerId());
        boolean hasValidReturnEmail = fromMember != null &&
            StaticUtils.isValidEmailAddress(fromMember.getEmailAddress());

        return new Batch(
            sanitizeEmailHeader(form.getSubject()),
            form.getMessage(),
            verifiedFromAddress(form.getResort(), siteWide),
            hasValidReturnEmail ? fromMember.getEmailAddress() : null,
            fromMember,
            hasValidReturnEmail,
            form.getResort(),
            readAttachments(form),
            siteWide);
    }

    /**
     * The From address has to be one this server is authorised to send for, or the message
     * fails SPF/DKIM alignment and the receiver scores it as spam — Verizon/AOL/Yahoo reject
     * outright ("554 5.7.1 [VI-101]"), and an attachment is usually what tips it over. The
     * member's own address goes in Reply-To instead, the same split MailMan has always used.
     */
    String verifiedFromAddress(String resort, boolean siteWide) {
        // A site-wide mailing is not from a patrol, it is from the site - and the SMTP session
        // authenticates as exactly this address (hostEmailUser in credentials.properties), so it
        // is the best-aligned From available rather than a compromise. It also means replies land
        // with the site administrator, which is the whole point of sending one.
        if (siteWide) {
            return FALLBACK_FROM_ADDRESS;
        }
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
                 List<Attachment> attachments, boolean siteWide) {
    }

    /** An uploaded file, already read into memory so the async send can use it. */
    record Attachment(String filename, String contentType, byte[] content) {
    }

    @Async("emailTaskExecutor")
    void sendBatch(List<EmailRecipient> recipients, Batch batch, Logger logger,
                   EmailBatchLog.Send send) {
        String footer = buildFooter(batch, PatrolData.getResortFullName(batch.resort(), logger));

        boolean multipart = !batch.attachments().isEmpty();

        // MULTIPART_MODE_MIXED, not the boolean overload's MIXED_RELATED: a multipart/related
        // wrapping nothing but the plain-text body is the shape bulk mailers produce, and spam
        // filters score it. Plain body + files is exactly what multipart/mixed is for.
        int multipartMode = multipart
            ? MimeMessageHelper.MULTIPART_MODE_MIXED
            : MimeMessageHelper.MULTIPART_MODE_NO;

        BatchOutcome outcome = new BatchOutcome(recipients.size());

        try (SmtpConnection connection = SmtpConnection.open(mailSender)) {
            for (EmailRecipient recipient : recipients) {
                try {
                    connection.send(compose(recipient, batch, footer, multipartMode));
                    outcome.recordSent();
                    //raised as we go, so the confirmation page can count up live
                    batchLog.recordSent(send);

                    logger.info("Email sent to " + recipient.getFullNameLastFirst() +
                               " at " + recipient.getEmailAddress() +
                               (multipart ? " with " + batch.attachments().size() + " attachment(s)" : "") + " From: " + batch.fromEmail);
                } catch (MessagingException | MailException e) {
                    logger.logException("Failed to send email to " + recipient.getEmailAddress(), e);
                    outcome.recordFailure(recipient, e);

                    if (isFatalToTheBatch(e)) {
                        // The server is refusing this sender, not this address. Carrying on would
                        // just repeat the same rejection once per remaining member, burying the one
                        // fact that matters under a hundred identical errors.
                        outcome.stopEarly(firstLineOf(e));
                        logger.error("Stopping the batch after " + outcome.attempted() + " of "
                                     + recipients.size() + " - the mail server is rejecting this "
                                     + "sender: " + firstLineOf(e));
                        break;
                    }
                }
            }
        } catch (MessagingException e) {
            logger.logException("Could not open an SMTP connection - none of the "
                                + recipients.size() + " emails were sent", e);
            outcome.stopEarly(firstLineOf(e));
        }

        logger.info("Batch complete: " + outcome.sent() + " of " + recipients.size() + " emails sent");

        //Marked finished where the director can see it. The confirmation page is rendered while
        //this batch is still queued, so it cannot report an outcome itself - and the failure
        //summary below cannot get out when the mail server is the fault.
        batchLog.finish(send, outcome.stopReason());

        if (outcome.needsReporting()) {
            reportOutcome(batch, outcome, logger);
        }
    }

    /**
     * Is this the mail server refusing the sender rather than one address?
     *
     * Gmail answers "454-4.7.0 Too many login attempts" when it is throttling logins and
     * "550 5.4.5 Daily user sending limit exceeded" when the day's quota is gone. Both apply to
     * every remaining message in the batch. A rejection of one bad address must NOT match here,
     * or one dead mailbox would abandon everybody after it - so this matches deliberately narrow
     * signatures rather than anything that merely looks like a failure.
     */
    boolean isFatalToTheBatch(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthenticationFailedException) {
                return true;
            }
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("too many login attempts")
                || lower.contains("5.4.5")
                || lower.contains("daily user sending")
                || lower.contains("sending quota exceeded")
                || lower.contains("account has exceeded")) {
                return true;
            }
        }
        return false;
    }

    /** SMTP rejections run to several lines; the first one carries the code and the reason. */
    private static String firstLineOf(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String firstLine = message.split("\\R", 2)[0].trim();
        return firstLine.length() > 200 ? firstLine.substring(0, 200) + "..." : firstLine;
    }

    /**
     * Mail the director who pressed Send, and the site admin, when a batch did not go out cleanly.
     *
     * Without this nobody finds out: the confirmation page reports how many recipients were
     * *queued*, and the failures only ever reached the server log.
     */
    /** Whoever pressed Send, for the record EmailForm shows. Falls back to the From address. */
    private static String senderDescription(Batch batch) {
        if (batch.fromMember() != null) {
            String name = batch.fromMember().getFullName();
            if (name != null && !name.isBlank()) {
                return name.trim();
            }
        }
        return batch.fromEmail() == null ? "(unknown)" : batch.fromEmail();
    }

    private void reportOutcome(Batch batch, BatchOutcome outcome, Logger logger) {
        List<String> to = new ArrayList<>();
        to.add(ADMIN_NOTIFICATION_ADDRESS);
        if (batch.hasValidReturnEmail()
            && !ADMIN_NOTIFICATION_ADDRESS.equalsIgnoreCase(batch.replyToEmail())) {
            to.add(batch.replyToEmail());
        }

        String resortName = PatrolData.getResortFullName(batch.resort(), logger);
        try {
            MimeMessage report = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(report, false, "utf-8");
            helper.setFrom(batch.fromEmail());
            helper.setTo(to.toArray(new String[0]));
            helper.setSubject(resortName + " email: " + outcome.sent() + " of " + outcome.total()
                              + " sent, " + outcome.notDelivered() + " not delivered");
            helper.setText(outcome.describe(batch, resortName), false);

            // a fresh connection on purpose - the batch's is closed, and may be why we are here
            mailSender.send(report);
            logger.info("Failure summary sent to " + String.join(", ", to));
        } catch (MessagingException | MailException e) {
            // If the mail server is the thing that is broken this cannot get through either. The
            // log is the fallback, and it already holds every individual failure.
            logger.logException("Could not send the failure summary to " + String.join(", ", to), e);
        }
    }

    /** What became of one batch: how much went out, what did not, and whether it stopped early. */
    static final class BatchOutcome {

        private final int total;
        private int sent;
        private final List<String> failures = new ArrayList<>();
        private String stopReason;

        BatchOutcome(int total) {
            this.total = total;
        }

        void recordSent() {
            sent++;
        }

        void recordFailure(EmailRecipient recipient, Exception e) {
            failures.add(recipient.getFullNameLastFirst() + " <" + recipient.getEmailAddress() + ">"
                         + "\n      " + firstLineOf(e));
        }

        void stopEarly(String reason) {
            stopReason = reason;
        }

        String stopReason() {
            return stopReason;
        }

        int total() {
            return total;
        }

        int sent() {
            return sent;
        }

        int attempted() {
            return sent + failures.size();
        }

        /** Everyone who did not get it: the outright failures plus anyone never attempted. */
        int notDelivered() {
            return total - sent;
        }

        boolean needsReporting() {
            return notDelivered() > 0;
        }

        String describe(Batch batch, String resortName) {
            StringBuilder report = new StringBuilder();
            report.append("Subject: ").append(batch.subject())
                  .append("\nSent from the ").append(resortName).append(" scheduling web site.\n\n")
                  .append(sent).append(" of ").append(total).append(" emails were delivered.\n");

            if (stopReason != null) {
                int neverTried = total - attempted();
                report.append("\nThe send STOPPED EARLY - the mail server rejected this sender:\n  ")
                      .append(stopReason).append('\n');
                if (neverTried > 0) {
                    report.append("\n").append(neverTried)
                          .append(" members were never attempted. Sending again later should reach them.\n");
                }
            }

            if (!failures.isEmpty()) {
                report.append('\n').append(failures.size())
                      .append(failures.size() == 1 ? " address did not go through:\n"
                                                   : " addresses did not go through:\n");
                for (String failure : failures) {
                    report.append("  ").append(failure).append('\n');
                }
                report.append("\nIf an address is wrong, correcting it on the member's record will "
                              + "fix it for next time.\n");
            }

            return report.toString();
        }
    }

    /** Build one recipient's copy of the batch message. */
    private MimeMessage compose(EmailRecipient recipient, Batch batch, String footer, int multipartMode)
            throws MessagingException {
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
        return mimeMessage;
    }

    /**
     * One SMTP connection, reused for a whole batch.
     *
     * {@code JavaMailSenderImpl.send(MimeMessage)} connects, authenticates, sends and disconnects
     * on every call, so sending in a loop meant one Gmail login per recipient. At around 80 logins
     * in two minutes Gmail starts answering "454-4.7.0 Too many login attempts, please try again
     * later" and every remaining message in the batch fails. Logging in once and holding the
     * connection open is what a bulk send is supposed to do.
     */
    private static final class SmtpConnection implements AutoCloseable {

        private final JavaMailSender fallbackSender;
        private final JavaMailSenderImpl sender;
        private Transport transport;

        static SmtpConnection open(JavaMailSender mailSender) throws MessagingException {
            SmtpConnection connection = new SmtpConnection(mailSender);
            if (connection.sender != null) {
                connection.connect();
            }
            return connection;
        }

        private SmtpConnection(JavaMailSender mailSender) {
            this.fallbackSender = mailSender;
            // anything other than the Spring implementation (a test double, say) has no Session to
            // borrow, so those fall back to one connection per message
            this.sender = (mailSender instanceof JavaMailSenderImpl impl) ? impl : null;
        }

        private void connect() throws MessagingException {
            Session session = sender.getSession();
            transport = session.getTransport(protocolOf(session));
            transport.connect(sender.getHost(), sender.getPort(),
                              sender.getUsername(), sender.getPassword());
        }

        /**
         * Mirrors JavaMailSenderImpl.getTransport, which is protected. getProtocol() is only set by
         * setProtocol - AsyncConfig configures the protocol through mail.transport.protocol instead,
         * so reading the getter alone would hand session.getTransport(null) and fail every send.
         */
        private String protocolOf(Session session) {
            String protocol = sender.getProtocol();
            if (protocol == null) {
                protocol = session.getProperty("mail.transport.protocol");
            }
            return protocol != null ? protocol : JavaMailSenderImpl.DEFAULT_PROTOCOL;
        }

        void send(MimeMessage message) throws MessagingException {
            if (transport == null) {
                fallbackSender.send(message);
                return;
            }
            message.saveChanges();
            try {
                transport.sendMessage(message, message.getAllRecipients());
            } catch (MessagingException e) {
                // A server that has dropped the connection - Gmail does this once one has been open
                // a while or carried enough messages - should cost one reconnect, not the rest of
                // the batch. A rejection on a still-live connection is this recipient's problem.
                if (transport.isConnected()) {
                    throw e;
                }
                connect();
                transport.sendMessage(message, message.getAllRecipients());
            }
        }

        @Override
        public void close() {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException ignored) {
                    // the batch is already sent; a noisy disconnect is not worth reporting
                }
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

    /**
     * The sign-off under every message.
     *
     * A site-wide mailing omits the "from <resort>'s online scheduling web site" line. That batch
     * is composed against whichever resort the site owner happened to be on, so the line would
     * tell a director at 46 other patrols that news about the whole site came from Brighton's.
     */
    String buildFooter(Batch batch, String fullPatrolName) {
        Roster fromMember = batch.fromMember();
        StringBuilder footer = new StringBuilder();
        footer.append("\n\n----------------------------------------------\n");

        if (fromMember != null) {
            footer.append("This message sent by: ").append(fromMember.getFullName())
                  .append(" at ( ").append(fromMember.getEmailAddress()).append(" )\n");
        } else {
            footer.append("This message sent by: unknown sender\n");
        }

        if (batch.siteWide()) {
            footer.append("\n");
        }
        else {
            footer.append("from ").append(fullPatrolName).append("'s online scheduling web site.\n\n");
        }

        if (!batch.hasValidReturnEmail()) {
            footer.append("Please Don't respond to this email. SEND any responses\n");
            if (fromMember != null) {
                footer.append("to: ").append(fromMember.getFullName())
                      .append(", who had an invalid email address in the system.\n\n");
            }
        }
        // There was a second branch here, for a resort with no verified From address of its own:
        // "Please Don't respond to this email... I am working on setting this 'From' address to be
        // specific to your patrol." Both halves are obsolete. The promise was kept - resorts that
        // wanted one have it - and the instruction was wrong regardless, because every message
        // whose sender has a usable address carries a Reply-To pointing at them, so replying works
        // and always did. It was telling 31 of the 43 resorts not to do the thing that works.

        //the site's name, not a hand-written copy of it - see SiteIdentity
        footer.append("This was sent from the ").append(SiteIdentity.PATROL_CALENDAR_DISPLAY)
              .append(" auto mailer.\n");
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
