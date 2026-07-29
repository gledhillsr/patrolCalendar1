package com.gledhills.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable DTO for EmailForm POST request.
 * Binds form data with validation annotations.
 */
public class EmailCompositionForm {

    /** Attachments on one email may total this much; enforced by EmailFormController. */
    public static final long MAX_ATTACHMENTS_TOTAL_BYTES = 2L * 1024 * 1024;

    @NotBlank(message = "Subject is required")
    @Size(min = 2, message = "Subject must be at least 2 characters")
    private String subject;

    @NotBlank(message = "Message is required")
    @Size(min = 4, message = "Message must be at least 4 characters")
    private String message;

    @NotEmpty(message = "At least one recipient must be selected")
    private List<String> patrollerIds;

    private List<MultipartFile> attachments;

    private String resort;
    private String fromPatrollerId;

    // Getters and setters
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<String> getPatrollerIds() {
        return patrollerIds != null ? new ArrayList<>(patrollerIds) : null;
    }
    public void setPatrollerIds(List<String> patrollerIds) {
        this.patrollerIds = patrollerIds != null ? new ArrayList<>(patrollerIds) : null;
    }

    /** Files the user picked, empty entries (an untouched file input posts one) removed. */
    public List<MultipartFile> getAttachments() {
        List<MultipartFile> chosen = new ArrayList<>();
        if (attachments != null) {
            for (MultipartFile file : attachments) {
                if (file != null && !file.isEmpty()) {
                    chosen.add(file);
                }
            }
        }
        return chosen;
    }
    public void setAttachments(List<MultipartFile> attachments) {
        this.attachments = attachments != null ? new ArrayList<>(attachments) : null;
    }

    public long getAttachmentsTotalBytes() {
        return getAttachments().stream().mapToLong(MultipartFile::getSize).sum();
    }

    public String getResort() { return resort; }
    public void setResort(String resort) { this.resort = resort; }

    public String getFromPatrollerId() { return fromPatrollerId; }
    public void setFromPatrollerId(String fromPatrollerId) { this.fromPatrollerId = fromPatrollerId; }
}
