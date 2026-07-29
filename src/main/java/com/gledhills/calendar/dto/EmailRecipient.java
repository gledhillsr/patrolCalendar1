package com.gledhills.calendar.dto;

/**
 * Immutable DTO representing a candidate email recipient.
 */
public class EmailRecipient {
    private final String id;
    private final String firstName;
    private final String lastName;
    private final String emailAddress;
    private final boolean hasValidEmail;

    public EmailRecipient(String id, String firstName, String lastName, String emailAddress,
                         boolean hasValidEmail) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.emailAddress = emailAddress;
        this.hasValidEmail = hasValidEmail;
    }

    public String getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmailAddress() { return emailAddress; }
    public boolean hasValidEmail() { return hasValidEmail; }

    public String getFullNameLastFirst() {
        return lastName + ", " + firstName;
    }
}
