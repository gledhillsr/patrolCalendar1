package com.gledhills.calendar.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the roster a patrol representative downloads from nsp.org and works out which rows are
 * new patrollers this resort does not already have.
 *
 * <p>The file is {@code ResultsGrid_ExportData.csv}: a header row, then one row per member. It is
 * a real CSV - fields are quoted when they contain a comma, and a quote inside a quoted field is
 * doubled - so it cannot be split on commas. Its address column alone
 * ({@code "w5774 North DriveElkhorn, WI 53121UNITED STATES"}) would break that.
 *
 * <p>Nothing here writes anything. It answers "what would be imported, and why is the rest not",
 * so the director can be shown that before deciding.
 */
public final class NspRosterImport {

    /** What nsp.org calls somebody who patrols; other member types are not imported. */
    private static final String PATROLLER = "Patroller";

    /** Every new member is created as a full-time basic patroller who is not a director. */
    public static final String NEW_CLASSIFICATION = "BAS";
    public static final String NEW_COMMITMENT = "2";      //Full Time
    public static final String NEW_DIRECTOR = "no";
    /** No certifications are known from the export; the column is an integer bit field. */
    public static final String NEW_INSTRUCTOR = "0";

    private NspRosterImport() {
    }

    /**
     * One row of the file, and what is to become of it.
     *
     * @param rowNumber   the line in the file, so a director can find it
     * @param importable  whether it will be inserted
     * @param reason      why not, when it will not be; empty when it will
     */
    public record Candidate(int rowNumber, String id, String firstName, String lastName,
                            String email, String cellPhone, String memberType,
                            boolean importable, String reason) {

        public String getFullName() {
            return (firstName + " " + lastName).trim();
        }

        //Thymeleaf reads these off the record through getters
        public int getRowNumber()    { return rowNumber; }
        public String getId()        { return id; }
        public String getFirstName() { return firstName; }
        public String getLastName()  { return lastName; }
        public String getEmail()     { return email; }
        public String getCellPhone() { return cellPhone; }
        public String getMemberType() { return memberType; }
        public boolean isImportable() { return importable; }
        public String getReason()    { return reason; }
    }

    /**
     * Every row of the file, in order, each marked importable or not.
     *
     * @param existingIds the member ids already on this resort's roster
     * @throws IOException if the file cannot be read at all
     */
    public static List<Candidate> read(Reader source, Set<String> existingIds) throws IOException {
        List<List<String>> rows = parse(source);
        List<Candidate> candidates = new ArrayList<>();
        if (rows.isEmpty()) {
            return candidates;
        }

        Map<String, Integer> column = headerIndex(rows.get(0));
        //an id repeated inside the file itself would otherwise be inserted twice
        Set<String> seenInFile = new HashSet<>();

        for (int row = 1; row < rows.size(); row++) {
            List<String> fields = rows.get(row);
            if (isBlank(fields)) {
                continue;
            }
            candidates.add(classify(row + 1, fields, column, existingIds, seenInFile));
        }
        return candidates;
    }

    private static Candidate classify(int rowNumber, List<String> fields, Map<String, Integer> column,
                                      Set<String> existingIds, Set<String> seenInFile) {
        String id = value(fields, column, "ID");
        String first = value(fields, column, "First Name");
        String last = value(fields, column, "Last Name");
        String email = value(fields, column, "Email");
        String memberType = value(fields, column, "Member Type");
        String phone = normalizePhone(value(fields, column, "Phone"));

        String reason = "";
        if (id.isEmpty()) {
            reason = "no ID in the file";
        }
        else if (!id.matches("\\d+")) {
            reason = "ID \"" + id + "\" is not a number";
        }
        else if (!PATROLLER.equalsIgnoreCase(memberType)) {
            reason = "Member Type is \""
                       + (memberType.isEmpty() ? "(blank)" : memberType) + "\", not Patroller";
        }
        else if (existingIds.contains(id)) {
            reason = "already on your roster";
        }
        else if (!seenInFile.add(id)) {
            reason = "appears more than once in this file";
        }
        else if (last.isEmpty() && first.isEmpty()) {
            reason = "no name in the file";
        }

        return new Candidate(rowNumber, id, first, last, email, phone, memberType,
                             reason.isEmpty(), reason);
    }

    /**
     * A phone number as the roster stores them, {@code 999-999-9999}, or empty.
     *
     * <p>nsp.org writes them as {@code (847) 372-5936}. Anything that is not ten digits after the
     * punctuation is dropped rather than guessed at: the field is optional, and a half-recognised
     * number is worse than none - a director cannot tell it was mangled on the way in.
     */
    static String normalizePhone(String raw) {
        if (raw == null) {
            return "";
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);            //a leading country code
        }
        if (digits.length() != 10) {
            return "";
        }
        return digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6);
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i).trim(), i);
        }
        return index;
    }

    private static String value(List<String> fields, Map<String, Integer> column, String name) {
        Integer at = column.get(name);
        if (at == null || at >= fields.size()) {
            return "";
        }
        return fields.get(at).trim();
    }

    private static boolean isBlank(List<String> fields) {
        for (String field : fields) {
            if (!field.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The file as rows of fields.
     *
     * <p>A hand-rolled reader rather than a split on commas: the export quotes any field holding a
     * comma and doubles a quote inside one, and its Edit column is a chunk of HTML. A newline
     * inside a quoted field is handled too, which is why this consumes the whole reader rather
     * than working line by line.
     */
    static List<List<String>> parse(Reader source) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;

        try (BufferedReader reader = new BufferedReader(source)) {
            int next;
            boolean first = true;
            while ((next = reader.read()) != -1) {
                char c = (char) next;
                if (first) {
                    first = false;
                    if (c == '﻿') {          //a byte order mark; Excel writes one
                        continue;
                    }
                }

                if (inQuotes) {
                    if (c == '"') {
                        reader.mark(1);
                        int peek = reader.read();
                        if (peek == '"') {
                            field.append('"');    //a doubled quote is one literal quote
                        }
                        else {
                            inQuotes = false;
                            if (peek != -1) {
                                reader.reset();
                            }
                        }
                    }
                    else {
                        field.append(c);
                    }
                    continue;
                }

                switch (c) {
                    case '"' -> {
                        inQuotes = true;
                        rowHasContent = true;
                    }
                    case ',' -> {
                        fields.add(field.toString());
                        field.setLength(0);
                        rowHasContent = true;
                    }
                    case '\r' -> { /* the \n that follows ends the row */ }
                    case '\n' -> {
                        fields.add(field.toString());
                        field.setLength(0);
                        if (rowHasContent) {
                            rows.add(new ArrayList<>(fields));
                        }
                        fields.clear();
                        rowHasContent = false;
                    }
                    default -> {
                        field.append(c);
                        rowHasContent = true;
                    }
                }
            }
        }

        if (rowHasContent || field.length() > 0) {   //a last row with no newline after it
            fields.add(field.toString());
            rows.add(fields);
        }
        return rows;
    }

    /** The ids in the order they appear, for a caller that wants to look them up. */
    public static Set<String> importableIds(List<Candidate> candidates) {
        Set<String> ids = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (candidate.importable()) {
                ids.add(candidate.id());
            }
        }
        return ids;
    }
}
