package org.nsponline.calendar.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable DTO representing an email recipient with shift counts.
 */
public class EmailRecipient {
    private final String id;
    private final String firstName;
    private final String lastName;
    private final String emailAddress;
    private final boolean hasValidEmail;
    private final Map<String, Integer> shiftCounts;
    private final String carryOverCredits;
    private final String creditsEarned;

    public EmailRecipient(String id, String firstName, String lastName, String emailAddress,
                         boolean hasValidEmail, Map<String, Integer> shiftCounts,
                         String carryOverCredits, String creditsEarned) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.emailAddress = emailAddress;
        this.hasValidEmail = hasValidEmail;
        this.shiftCounts = new HashMap<>(shiftCounts);
        this.carryOverCredits = carryOverCredits;
        this.creditsEarned = creditsEarned;
    }

    public String getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmailAddress() { return emailAddress; }
    public boolean isHasValidEmail() { return hasValidEmail; }
    public Map<String, Integer> getShiftCounts() { return new HashMap<>(shiftCounts); }
    public String getCarryOverCredits() { return carryOverCredits; }
    public String getCreditsEarned() { return creditsEarned; }

    public String getFullNameLastFirst() {
        return lastName + ", " + firstName;
    }

    public int getTotalShiftCount() {
        return shiftCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
