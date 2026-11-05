package com.gledhills.calendar.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How each recent bulk email is getting on, and how it ended.
 *
 * <p>A send is {@code @Async}: the confirmation page is rendered before the batch starts, so it
 * cannot know the outcome and used to claim one anyway - "Emails sent. 32 emails are being
 * delivered." On 5 November a SoldierMountain director read exactly that while Gmail refused the
 * login and <b>none</b> of the 32 went out. The failure summary that exists to catch this could
 * not be sent either, because the mail server was the problem. Nobody knew for a day.
 *
 * <p>A batch is registered here <b>before</b> it is dispatched, so the confirmation page rendered
 * next can find it immediately, and the running count is raised as each message is accepted. That
 * is what the live line on the confirmation page counts up from.
 *
 * <p><b>This is a progress indicator, not a record.</b> It is held in memory and bounded, and it
 * exists only for as long as somebody is plausibly watching the page. A batch that fails is
 * reported by the failure summary email instead - see {@code EmailService.reportOutcome} - so
 * nothing here needs to outlive the send.
 */
@Component
public class EmailBatchLog {

    /**
     * How many sends stay findable, across all resorts. Comfortably more than could be in flight
     * at once, and small enough that the map cannot grow without bound over an uptime of months.
     */
    static final int TRACKED = 200;

    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("d MMM, h:mm", Locale.US);

    /**
     * One batch, from dispatch to finish.
     *
     * <p>Mutable on purpose: the send thread raises {@link #sent} as it goes and request threads
     * read it, which is the whole point. {@code sent} is atomic and the two finish fields are
     * volatile, so a reader sees a coherent snapshot without locking.
     */
    public static final class Send {

        private final String id = UUID.randomUUID().toString();
        private final String resort;
        private final String sender;
        private final String subject;
        private final Instant startedAt = Instant.now();
        private final int queued;

        private final AtomicInteger sent = new AtomicInteger();
        private volatile boolean finished;
        private volatile String stopReason;

        Send(String resort, String sender, String subject, int queued) {
            this.resort = resort;
            this.sender = sender;
            this.subject = subject;
            this.queued = queued;
        }

        public String id() {
            return id;
        }

        /** Which resort's send this is, so a status request cannot read another resort's batch. */
        public String resort() {
            return resort;
        }

        public String sender() {
            return sender;
        }

        public String subject() {
            return subject;
        }

        public int queued() {
            return queued;
        }

        public int sent() {
            return sent.get();
        }

        public boolean finished() {
            return finished;
        }

        public int notDelivered() {
            return queued - sent();
        }

        public boolean wentOutCleanly() {
            return notDelivered() == 0;
        }

        /** Stopped early means the server refused the sender, not one address. */
        public boolean stoppedEarly() {
            return stopReason != null && !stopReason.isEmpty();
        }

        public String stopReason() {
            return stopReason;
        }

        /** In the resort's own time zone - a director reading this wants their own clock. */
        public String describeWhen(ZoneId zone) {
            ZoneId inForce = zone != null ? zone : ZoneId.systemDefault();
            return WHEN.format(startedAt.atZone(inForce))
                     + (startedAt.atZone(inForce).getHour() < 12 ? "am" : "pm");
        }

        /**
         * The one sentence a director needs, in whichever of the four states this is.
         *
         * <p>Built here rather than in the template so the live line on the confirmation page and
         * the finished row on EmailForm cannot word the same state differently.
         */
        public String describeStatus() {
            int done = sent();
            if (!finished) {
                return "Email sending in progress - " + done + " of " + queued + " sent so far";
            }
            if (queued == 0) {
                return "Nobody to send to";
            }
            if (done == queued) {
                return queued == 1 ? "Email sent successfully"
                                   : "All " + queued + " emails sent successfully";
            }
            if (done == 0) {
                return queued + " of " + queued + " failed to send. Please try again later";
            }
            return done + " of " + queued + " sent - " + notDelivered() + " failed";
        }
    }

    /**
     * Bounded and insertion-ordered: the oldest send drops out once {@link #TRACKED} is reached,
     * which is all the lifecycle management this needs. Synchronized rather than concurrent
     * because the eviction has to be atomic with the insert.
     */
    private final Map<String, Send> byId = Collections.synchronizedMap(
        new LinkedHashMap<String, Send>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Send> eldest) {
                return size() > TRACKED;
            }
        });

    /**
     * Register a batch about to be dispatched. Called on the request thread, before the async send
     * starts, so the page rendered next can already ask about it.
     */
    Send start(String resort, String sender, String subject, int queued) {
        Send send = new Send(resort, sender, subject, queued);
        byId.put(send.id(), send);
        return send;
    }

    /** One more message accepted by the mail server. Called from the send thread. */
    void recordSent(Send send) {
        if (send != null) {
            send.sent.incrementAndGet();
        }
    }

    /** The batch is over, however it went. */
    void finish(Send send, String stopReason) {
        if (send != null) {
            send.stopReason = stopReason;
            send.finished = true;
        }
    }

    /**
     * One batch by the id the confirmation page was given, or null once it has aged out or the
     * application has restarted. The caller treats null as "no longer being tracked" and stops
     * asking, rather than as an error.
     */
    public Send find(String id) {
        return id == null ? null : byId.get(id);
    }
}
