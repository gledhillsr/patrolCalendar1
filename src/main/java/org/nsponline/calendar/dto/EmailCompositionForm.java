package org.nsponline.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Mutable DTO for EmailForm POST request.
 * Binds form data with validation annotations.
 */
public class EmailCompositionForm {
    @NotBlank(message = "Subject is required")
    @Size(min = 2, message = "Subject must be at least 2 characters")
    private String subject;

    @NotBlank(message = "Message is required")
    @Size(min = 4, message = "Message must be at least 4 characters")
    private String message;

    @NotEmpty(message = "At least one recipient must be selected")
    private List<String> patrollerIds;

    private String resort;
    private String fromPatrollerId;
    private Integer originalPatrollerCount;

    // Getters and setters
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<String> getPatrollerIds() { return patrollerIds; }
    public void setPatrollerIds(List<String> patrollerIds) { this.patrollerIds = patrollerIds; }

    public String getResort() { return resort; }
    public void setResort(String resort) { this.resort = resort; }

    public String getFromPatrollerId() { return fromPatrollerId; }
    public void setFromPatrollerId(String fromPatrollerId) { this.fromPatrollerId = fromPatrollerId; }

    public Integer getOriginalPatrollerCount() { return originalPatrollerCount; }
    public void setOriginalPatrollerCount(Integer originalPatrollerCount) {
        this.originalPatrollerCount = originalPatrollerCount;
    }
}
