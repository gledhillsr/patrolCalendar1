package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.utils.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bulk send must log in to the SMTP server once, not once per recipient.
 *
 * JavaMailSenderImpl.send(MimeMessage) connects, authenticates and disconnects on every call, so
 * sending in a loop meant a Gmail login per email. Around the 80th, Gmail answers "454-4.7.0 Too
 * many login attempts" and the rest of the batch fails - which is exactly what happened to a
 * Brighton send of 180. Counting logins is the only way to see that from a test.
 */
class EmailServiceSmtpConnectionTest {

    private static final int RECIPIENT_COUNT = 25;

    private StubSmtpServer smtpServer;

    @BeforeEach
    void startServer() throws IOException {
        smtpServer = new StubSmtpServer();
        smtpServer.start();
    }

    @AfterEach
    void stopServer() {
        smtpServer.stop();
    }

    @Test
    @Timeout(60)
    void aBatchLogsInOnceNoMatterHowManyRecipients() throws Exception {
        EmailService service = new EmailService(mailSender());

        service.sendBatch(recipients(RECIPIENT_COUNT), batch(), new Logger(getClass(), Logger.INFO));
        smtpServer.awaitQuiet();

        assertEquals(RECIPIENT_COUNT, smtpServer.messages.get(),
            "every recipient should still get their own message");
        assertEquals(1, smtpServer.logins.get(),
            "the batch should authenticate once - one login per recipient is what Gmail throttles");
        assertEquals(1, smtpServer.connections.get(),
            "the batch should hold a single SMTP connection open");
    }

    /**
     * A "550 5.4.5 Daily user sending limit exceeded" applies to the sender, not the address, so
     * every remaining message would get the same answer. The batch has to stop rather than turn
     * one problem into 150 identical failures.
     */
    @Test
    @Timeout(60)
    void aQuotaRejectionStopsTheBatchInsteadOfRepeatingItPerMember() throws Exception {
        smtpServer.rejectPatrollersFrom(5, "550 5.4.5 Daily user sending limit exceeded");
        EmailService service = new EmailService(mailSender());

        service.sendBatch(recipients(RECIPIENT_COUNT), batch(), new Logger(getClass(), Logger.INFO));
        smtpServer.awaitQuiet();

        assertEquals(4, smtpServer.patrollerMessagesAccepted.get(),
            "the four before the rejection should have gone out");
        assertEquals(5, smtpServer.patrollerMessagesAttempted.get(),
            "the batch should stop at the first quota rejection, not try the remaining 20");
    }

    /** Nobody finds out about a half-sent batch unless it is reported out of the log. */
    @Test
    @Timeout(60)
    void aFailedBatchIsReportedToBothTheDirectorAndTheAdmin() throws Exception {
        smtpServer.rejectPatrollersFrom(5, "550 5.4.5 Daily user sending limit exceeded");
        EmailService service = new EmailService(mailSender());

        service.sendBatch(recipients(RECIPIENT_COUNT), batchFromDirector(), new Logger(getClass(), Logger.INFO));
        smtpServer.awaitQuiet();

        List<String> summaryRecipients = smtpServer.nonPatrollerRecipients();
        assertTrue(summaryRecipients.contains("director@example.com"),
            "the director who pressed Send should be told, got: " + summaryRecipients);
        assertTrue(summaryRecipients.contains("steve@gledhills.com"),
            "the site admin should be told, got: " + summaryRecipients);

        String summary = smtpServer.lastBody();
        assertTrue(summary.contains("4 of 25 emails were delivered"),
            "the summary should say how many got through, got:\n" + summary);
        assertTrue(summary.contains("STOPPED EARLY") && summary.contains("5.4.5"),
            "the summary should say why it stopped, got:\n" + summary);
        assertTrue(summary.contains("20 members were never attempted"),
            "the summary should say who still needs sending, got:\n" + summary);
    }

    /** A single bad mailbox must not abandon everyone behind it in the list. */
    @Test
    @Timeout(60)
    void oneBadAddressDoesNotStopTheRest() throws Exception {
        smtpServer.rejectOnlyPatroller(5, "550 5.1.1 No such user here");
        EmailService service = new EmailService(mailSender());

        service.sendBatch(recipients(RECIPIENT_COUNT), batch(), new Logger(getClass(), Logger.INFO));
        smtpServer.awaitQuiet();

        assertEquals(RECIPIENT_COUNT, smtpServer.patrollerMessagesAttempted.get(),
            "every recipient should still have been tried");
        assertEquals(RECIPIENT_COUNT - 1, smtpServer.patrollerMessagesAccepted.get(),
            "only the one bad address should have failed");
    }

    private JavaMailSenderImpl mailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(smtpServer.port());
        sender.setUsername("patrol");
        sender.setPassword("secret");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        // the stub speaks plain SMTP; TLS is AsyncConfig's business, not this test's
        props.put("mail.smtp.starttls.enable", "false");
        return sender;
    }

    private List<EmailRecipient> recipients(int count) {
        List<EmailRecipient> recipients = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            recipients.add(new EmailRecipient(
                String.valueOf(100000 + i), "First" + i, "Last" + i, "patroller" + i + "@example.com", true));
        }
        return recipients;
    }

    /** A batch shaped like the one that failed: small attachment, no valid return address. */
    private EmailService.Batch batch() {
        List<EmailService.Attachment> attachments = List.of(new EmailService.Attachment(
            "shift-policy.txt", "text/plain", "the updated shift policy".getBytes(StandardCharsets.UTF_8)));

        return new EmailService.Batch(
            "Season kickoff", "Hello $first$, please read the attached.",
            "patrol@example.com", null, null, false, "Sample", attachments);
    }

    /**
     * As {@link #batch()}, but from a director with a working address of their own - so the
     * summary has somebody besides the admin to go to. Brighton because it is a resort with a
     * verified sending address, which is the shape the real failing send had.
     */
    private EmailService.Batch batchFromDirector() {
        return new EmailService.Batch(
            "Season kickoff", "Hello $first$, please read the attached.",
            "brightonskipatrol@gmail.com", "director@example.com", null, true, "Brighton", List.of());
    }

    /**
     * Enough of SMTP to let Jakarta Mail deliver: greeting, EHLO, AUTH PLAIN, MAIL/RCPT/DATA, QUIT.
     * Only PLAIN is advertised so the client sends its credentials in one line and there is no
     * challenge/response to model.
     */
    private static final class StubSmtpServer {

        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger logins = new AtomicInteger();
        final AtomicInteger messages = new AtomicInteger();
        final AtomicInteger patrollerMessagesAttempted = new AtomicInteger();
        final AtomicInteger patrollerMessagesAccepted = new AtomicInteger();

        private final List<String> otherRecipients = Collections.synchronizedList(new ArrayList<>());
        private volatile String lastBody = "";

        /** 1-based index of the first patroller message to reject, or 0 to accept them all. */
        private volatile int rejectFrom;
        private volatile boolean rejectOnlyThatOne;
        private volatile String rejectResponse;

        private ServerSocket serverSocket;
        private Thread acceptThread;
        private volatile boolean running;

        /** Reject this patroller message and every one after it, the way a quota rejection behaves. */
        void rejectPatrollersFrom(int index, String response) {
            this.rejectFrom = index;
            this.rejectOnlyThatOne = false;
            this.rejectResponse = response;
        }

        /** Reject exactly one message, the way a single dead mailbox behaves. */
        void rejectOnlyPatroller(int index, String response) {
            this.rejectFrom = index;
            this.rejectOnlyThatOne = true;
            this.rejectResponse = response;
        }

        /** Addresses of messages that were not part of the patroller batch - i.e. the summary. */
        List<String> nonPatrollerRecipients() {
            synchronized (otherRecipients) {
                return new ArrayList<>(otherRecipients);
            }
        }

        String lastBody() {
            return lastBody;
        }

        void start() throws IOException {
            serverSocket = new ServerSocket(0);
            serverSocket.setSoTimeout(500);
            running = true;
            acceptThread = new Thread(this::acceptLoop, "stub-smtp");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        /** Let any in-flight disconnect land before the assertions read the counters. */
        void awaitQuiet() throws InterruptedException {
            Thread.sleep(250);
        }

        void stop() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }

        private void acceptLoop() {
            while (running) {
                try (Socket socket = serverSocket.accept()) {
                    connections.incrementAndGet();
                    converse(socket);
                } catch (IOException expectedWhileIdleOrClosing) {
                    // accept timeout, or the socket closed on stop()
                }
            }
        }

        private void converse(Socket socket) throws IOException {
            socket.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            out.print("220 localhost stub SMTP\r\n");
            out.flush();

            List<String> envelopeTo = new ArrayList<>();

            String line;
            while ((line = in.readLine()) != null) {
                String command = line.toUpperCase();
                if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                    out.print("250-localhost\r\n250 AUTH PLAIN\r\n");
                } else if (command.startsWith("AUTH")) {
                    logins.incrementAndGet();
                    out.print("235 2.7.0 Authentication successful\r\n");
                } else if (command.startsWith("MAIL FROM")) {
                    envelopeTo.clear();
                    out.print("250 2.1.0 OK\r\n");
                } else if (command.startsWith("RCPT TO")) {
                    envelopeTo.add(addressIn(line));
                    out.print("250 2.1.5 OK\r\n");
                } else if (command.startsWith("DATA")) {
                    out.print("354 End data with <CR><LF>.<CR><LF>\r\n");
                    out.flush();

                    StringBuilder body = new StringBuilder();
                    while ((line = in.readLine()) != null && !line.equals(".")) {
                        body.append(line).append('\n');
                    }
                    messages.incrementAndGet();
                    out.print(responseFor(envelopeTo, body.toString()));
                } else if (command.startsWith("QUIT")) {
                    out.print("221 2.0.0 Bye\r\n");
                    out.flush();
                    return;
                } else if (command.startsWith("RSET") || command.startsWith("NOOP")) {
                    out.print("250 2.0.0 OK\r\n");
                } else {
                    out.print("500 5.5.1 Unrecognized command\r\n");
                }
                out.flush();
            }
        }

        /**
         * Accept or reject a finished message. Patroller messages are the batch under test;
         * anything else is the failure summary, which is recorded rather than counted.
         */
        private String responseFor(List<String> envelopeTo, String body) {
            boolean toPatrollers = envelopeTo.stream().anyMatch(to -> to.startsWith("patroller"));
            if (!toPatrollers) {
                otherRecipients.addAll(envelopeTo);
                lastBody = body;
                return "250 2.0.0 OK\r\n";
            }

            int index = patrollerMessagesAttempted.incrementAndGet();
            boolean reject = rejectFrom > 0
                && (rejectOnlyThatOne ? index == rejectFrom : index >= rejectFrom);
            if (reject) {
                return rejectResponse + "\r\n";
            }
            patrollerMessagesAccepted.incrementAndGet();
            return "250 2.0.0 OK\r\n";
        }

        /** "RCPT TO:<someone@example.com>" -> "someone@example.com" */
        private String addressIn(String line) {
            int open = line.indexOf('<');
            int close = line.lastIndexOf('>');
            return (open >= 0 && close > open) ? line.substring(open + 1, close) : line;
        }
    }
}
