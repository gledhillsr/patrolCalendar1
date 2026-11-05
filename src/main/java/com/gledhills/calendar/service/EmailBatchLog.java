package com.gledhills.calendar.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What became of the last few bulk emails a resort sent.
 *
 * <p>A bulk send is {@code @Async}: the confirmation page is rendered while the batch is still
 * queued, so it cannot know the outcome and used to claim one anyway - "Emails sent. 32 emails are
 * being delivered." On 5 November a SoldierMountain director saw exactly that while Gmail rejected
 * the login and <b>none</b> of the 32 went out. The failure summary that exists to catch this
 * could not be sent either, because the mail server was the problem. Nobody knew for a day.
 *
 * <p>So the outcome is recorded here when the batch finishes, and EmailForm shows it. This is the
 * answer to "did my email actually go out?", which the director previously had no way to ask.
 *
 * <p><b>Memory only, and deliberately.</b> Persisting it would mean a new table on 45 resorts and
 * a site-wide migration to create it, for information whose value is measured in hours. An empty
 * list after a restart reads as "nothing recorded since the application restarted" rather than as
 * "nothing was sent" - the wording in the template is careful about that distinction.
 */
@Component
public class EmailBatchLog {

    /** Per resort. Enough to cover a director's session without unbounded growth. */
    static final int KEPT_PER_RESORT = 20;

    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("d MMM, h:mm", Locale.US);

    /**
     * One finished batch.
     *
     * @param queued     how many recipients the send was handed
     * @param sent       how many the mail server actually accepted
     * @param stopReason why the batch gave up early, or null if it ran to the end
     */
    public record Entry(String sender, String subject, Instant finishedAt,
                        int queued, int sent, String stopReason) {

        public int notDelivered() {
            return queued - sent;
        }

        public boolean wentOutCleanly() {
            return notDelivered() == 0;
        }

        /** Stopped early means the server refused the sender, not one address. */
        public boolean stoppedEarly() {
            return stopReason != null && !stopReason.isEmpty();
        }

        /** In the resort's own time zone - a director reading this wants their own clock. */
        public String describeWhen(ZoneId zone) {
            ZoneId inForce = zone != null ? zone : ZoneId.systemDefault();
            return WHEN.format(finishedAt.atZone(inForce))
                     + (finishedAt.atZone(inForce).getHour() < 12 ? "am" : "pm");
        }
    }

    private final Map<String, Deque<Entry>> byResort = new ConcurrentHashMap<>();

    /**
     * Called from the async send thread as a batch finishes; read from request threads. The deque
     * is guarded rather than concurrent because a trim has to be atomic with the add.
     */
    void record(String resort, Entry entry) {
        if (resort == null || entry == null) {
            return;
        }
        Deque<Entry> entries = byResort.computeIfAbsent(resort, r -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addFirst(entry);
            while (entries.size() > KEPT_PER_RESORT) {
                entries.removeLast();
            }
        }
    }

    /** Most recent first. Never null; empty when nothing has been sent since the last restart. */
    public List<Entry> recent(String resort) {
        Deque<Entry> entries = resort == null ? null : byResort.get(resort);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /** True when any recorded batch did not go out in full - what the page highlights. */
    public boolean anyIncomplete(String resort) {
        return recent(resort).stream().anyMatch(e -> !e.wentOutCleanly());
    }
}
