package com.gledhills.calendar.dto;

/**
 * Immutable DTO representing a candidate email recipient.
 *
 * Normally everyone on an EmailForm comes from the one resort whose page you are on, and a
 * patroller id identifies them on its own. The site-wide director mailing is the exception: it
 * gathers people from every resort at once, and ids are only unique <em>within</em> a resort -
 * Brighton's 192443 and Afton's 192443 are two different people. Such a recipient carries the
 * resort it came from, and {@link #getSelectionKey()} is what the form posts back, so the send
 * can look each one up in the right database rather than resolving them all against Brighton.
 */
public class EmailRecipient {
    private final String id;
    private final String firstName;
    private final String lastName;
    private final String emailAddress;
    private final boolean hasValidEmail;
    /** The resort this person was read from, or null for the ordinary single-resort case. */
    private final String resort;

    public EmailRecipient(String id, String firstName, String lastName, String emailAddress,
                         boolean hasValidEmail) {
        this(id, firstName, lastName, emailAddress, hasValidEmail, null);
    }

    public EmailRecipient(String id, String firstName, String lastName, String emailAddress,
                         boolean hasValidEmail, String resort) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.emailAddress = emailAddress;
        this.hasValidEmail = hasValidEmail;
        this.resort = resort;
    }

    public String getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmailAddress() { return emailAddress; }
    public boolean hasValidEmail() { return hasValidEmail; }
    public String getResort() { return resort; }

    public String getFullNameLastFirst() {
        return lastName + ", " + firstName;
    }

    /**
     * What the recipient picker posts back for this person.
     *
     * A bare id for the ordinary case, so every existing page posts exactly what it always did;
     * "Resort:id" once a resort is attached. Colon is safe as the separator because a resort name
     * is a database name and an id is digits.
     */
    public String getSelectionKey() {
        return (resort == null) ? id : resort + ":" + id;
    }

    /** "Gledhill, Steve" - or "Gledhill, Steve (Brighton)" when the list spans resorts. */
    public String getDisplayName() {
        return (resort == null) ? getFullNameLastFirst() : getFullNameLastFirst() + " (" + resort + ")";
    }
}
