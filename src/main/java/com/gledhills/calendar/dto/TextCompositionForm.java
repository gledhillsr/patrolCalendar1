package com.gledhills.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Text Message form posts.
 *
 * <p>Deliberately not {@link EmailCompositionForm} with a flag: a text has no subject and no
 * attachments, and the two things it does have that an email does not - a hard length that costs
 * money, and a recipient who may simply have no number - are the whole of what this page is about.
 * Sharing the DTO would have meant a subject that is required except when it is not.
 */
public class TextCompositionForm {

    /**
     * One SMS segment, and never more than one.
     *
     * <p>A segment is 160 characters of plain GSM-7 text, and every segment past the first is
     * charged again <em>per recipient</em>. Capping the message at exactly 160 <b>and</b> refusing
     * anything outside the plain character set below is what makes "one segment" a fact rather
     * than a hope: a single curly quote or emoji pushes the whole message into UCS-2, where a
     * segment is only <b>70</b> characters, so the same 160 characters would suddenly cost three.
     */
    public static final int SEGMENT_CHARACTERS = 160;
    public static final int MAX_CHARACTERS = SEGMENT_CHARACTERS;

    /**
     * The characters a message may contain: letters, digits, spaces, line breaks and everyday
     * punctuation. Nothing else.
     *
     * <p>Two separate reasons, and both matter:
     *
     * <ul>
     *   <li><b>Anything outside GSM-7 costs triple.</b> An emoji, a curly quote or an em dash -
     *       exactly what arrives when a message is pasted out of Word or Outlook - switches the
     *       encoding to UCS-2 and cuts the segment to 70 characters.</li>
     *   <li><b>Some plain ASCII costs double even in GSM-7.</b> {@code ^ { } [ ] ~ | \} and the
     *       euro sign live in GSM-7's extension table and take <em>two</em> septets each, so a
     *       160-character message containing one of them is two segments. They are excluded for
     *       that reason alone, not because anybody would miss them in a note about a shift.</li>
     * </ul>
     *
     * <p>Everything listed here is a single septet, so 160 of them is exactly one segment.
     */
    public static final String SIMPLE_TEXT_PATTERN =
        "^[A-Za-z0-9 \\r\\n.,?!:;'\"()\\-_/@#$%&*+=<>]*$";

    @NotBlank(message = "Message is required")
    @Size(min = 4, max = MAX_CHARACTERS,
          message = "A text message must be between 4 and " + MAX_CHARACTERS + " characters")
    @Pattern(regexp = SIMPLE_TEXT_PATTERN,
             message = "A text message may contain only letters, numbers, spaces and simple"
                       + " punctuation. Accented letters, curly quotes, emoji and symbols like"
                       + " ^ { } [ ] ~ | are not allowed, because they would split the message"
                       + " into several and multiply what it costs to send.")
    private String message;

    @NotEmpty(message = "At least one recipient must be selected")
    private List<String> patrollerIds;

    private String resort;
    private String fromPatrollerId;

    /**
     * The characters in {@code text} that {@link #SIMPLE_TEXT_PATTERN} refuses, de-duplicated and
     * in the order they appear - so a director is told <em>which</em> character to fix rather than
     * being left to hunt for it in 160 characters of their own writing.
     */
    public static String disallowedCharactersIn(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder found = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            if (!character.matches(SIMPLE_TEXT_PATTERN) && found.indexOf(character) < 0) {
                found.append(character);
            }
        });
        return found.toString();
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<String> getPatrollerIds() {
        return patrollerIds != null ? new ArrayList<>(patrollerIds) : null;
    }
    public void setPatrollerIds(List<String> patrollerIds) {
        this.patrollerIds = patrollerIds != null ? new ArrayList<>(patrollerIds) : null;
    }

    public String getResort() { return resort; }
    public void setResort(String resort) { this.resort = resort; }

    public String getFromPatrollerId() { return fromPatrollerId; }
    public void setFromPatrollerId(String fromPatrollerId) { this.fromPatrollerId = fromPatrollerId; }
}
