package org.nsponline.calendar.dto;

import java.time.LocalDateTime;

/**
 * Immutable DTO representing async email send operation result.
 */
public class EmailSendResult {
    private final String trackingId;
    private final int totalRecipients;
    private final LocalDateTime startTime;

    public EmailSendResult(String trackingId, int totalRecipients, LocalDateTime startTime) {
        this.trackingId = trackingId;
        this.totalRecipients = totalRecipients;
        this.startTime = startTime != null ? startTime : null;
    }

    public String getTrackingId() { return trackingId; }
    public int getTotalRecipients() { return totalRecipients; }
    public LocalDateTime getStartTime() { return startTime; }
}
