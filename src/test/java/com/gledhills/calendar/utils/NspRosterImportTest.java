package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the nsp.org roster export.
 *
 * <p>The header and the sample row here are taken verbatim from a real
 * {@code ResultsGrid_ExportData.csv}, because the two things that break a CSV reader are both in
 * it: an Edit column holding HTML with doubled quotes, and an address holding a comma.
 */
class NspRosterImportTest {

    private static final String HEADER =
        "﻿ID,First Name,Last Name,Dues Paid/Not Paid,Member Type,Primary Initial Class,"
          + "Primary Initial Service Type,Paid Through,OEC,OFC Completion Date,Edit,Email,"
          + "Primary Cred Class,Primary Additional Class,Primary Additional Service Type,"
          + "Billing Category,Join Date,CPR Certificate Issue Date,CPR Certificate Provider,"
          + "ICS-100 Completion Date,Youth Protection Exp Date,EMT Certifications,"
          + "Temp Not Patrolling,Militaryleave,Phone,Full Address,Birth Date,Gender";

    /** The real row, quotes and commas and all. */
    private static String row(String id, String first, String last, String memberType,
                              String email, String phone) {
        return id + "," + first + "," + last + ",Paid," + memberType + ",Alpine,V,12/31/2026,CU,,"
                 + "\"<a class=\"\"edit-link\"\" href=\"\"/iparts/PanelEdit.aspx?ID=" + id
                 + "&TemplateType=E\"\" name=\"\"NSP_EditRoster\"\">Edit</a>\","
                 + email + ",,,,,3/9/2023,,,8/25/2025,,,FALSE,FALSE," + phone + ","
                 + "\"w5774 North DriveElkhorn, WI 53121UNITED STATES\",10/20/1975,";
    }

    private static List<NspRosterImport.Candidate> read(String csv, Set<String> existing)
        throws Exception {
        return NspRosterImport.read(new StringReader(csv), existing);
    }

    @Test
    void aNewPatrollerIsImportedWithTheFieldsThatMap() throws Exception {
        String csv = HEADER + "\n"
                       + row("291617", "Chris", "Hauca", "Patroller",
                             "chris.hauca@gmail.com", "(847) 372-5936") + "\n";

        List<NspRosterImport.Candidate> found = read(csv, Set.of());

        assertEquals(1, found.size());
        NspRosterImport.Candidate candidate = found.get(0);
        assertTrue(candidate.importable(), candidate.reason());
        assertEquals("291617", candidate.id());
        assertEquals("Chris", candidate.firstName());
        assertEquals("Hauca", candidate.lastName());
        assertEquals("chris.hauca@gmail.com", candidate.email());
        assertEquals("847-372-5936", candidate.cellPhone(), "the phone is stored our way");
    }

    /** The whole point: somebody already here is left alone. */
    @Test
    void anIdAlreadyOnTheRosterIsSkipped() throws Exception {
        String csv = HEADER + "\n"
                       + row("291617", "Chris", "Hauca", "Patroller", "c@example.com", "") + "\n";

        NspRosterImport.Candidate candidate = read(csv, Set.of("291617")).get(0);

        assertFalse(candidate.importable());
        assertEquals("already on your roster", candidate.reason());
    }

    @Test
    void onlyPatrollersAreImported() throws Exception {
        String csv = HEADER + "\n"
                       + row("100001", "Pat", "Ron", "Patroller", "a@example.com", "") + "\n"
                       + row("100002", "Al", "Umni", "Alumni", "b@example.com", "") + "\n"
                       + row("100003", "Ho", "St", "", "c@example.com", "") + "\n";

        List<NspRosterImport.Candidate> found = read(csv, Set.of());

        assertTrue(found.get(0).importable());
        assertFalse(found.get(1).importable());
        assertTrue(found.get(1).reason().contains("Alumni"), found.get(1).reason());
        assertFalse(found.get(2).importable());
        assertTrue(found.get(2).reason().contains("(blank)"), found.get(2).reason());
    }

    @Test
    void theIdMustBeThereAndNumeric() throws Exception {
        String csv = HEADER + "\n"
                       + row("", "No", "Id", "Patroller", "a@example.com", "") + "\n"
                       + row("AB123", "Not", "Numeric", "Patroller", "b@example.com", "") + "\n";

        List<NspRosterImport.Candidate> found = read(csv, Set.of());

        assertEquals("no ID in the file", found.get(0).reason());
        assertTrue(found.get(1).reason().contains("not a number"), found.get(1).reason());
    }

    /** Two rows for one person would otherwise be inserted twice. */
    @Test
    void anIdRepeatedInTheFileIsImportedOnce() throws Exception {
        String csv = HEADER + "\n"
                       + row("100001", "Pat", "Ron", "Patroller", "a@example.com", "") + "\n"
                       + row("100001", "Pat", "Ron", "Patroller", "a@example.com", "") + "\n";

        List<NspRosterImport.Candidate> found = read(csv, Set.of());

        assertTrue(found.get(0).importable());
        assertFalse(found.get(1).importable());
        assertTrue(found.get(1).reason().contains("more than once"), found.get(1).reason());
    }

    /**
     * The two things a naive comma-split gets wrong, both present in a real export: the Edit
     * column is HTML with doubled quotes, and the address holds a comma.
     */
    @Test
    void quotedFieldsAndDoubledQuotesAreReadCorrectly() throws Exception {
        String csv = HEADER + "\n"
                       + row("291617", "Chris", "Hauca", "Patroller", "chris@example.com",
                             "(847) 372-5936") + "\n";

        NspRosterImport.Candidate candidate = read(csv, Set.of()).get(0);

        //email sits after the HTML column, so getting it right proves the quoting was handled
        assertEquals("chris@example.com", candidate.email());
        assertEquals("847-372-5936", candidate.cellPhone());
    }

    @Test
    void phoneNumbersAreNormalisedOrDropped() {
        assertEquals("847-372-5936", NspRosterImport.normalizePhone("(847) 372-5936"));
        assertEquals("847-372-5936", NspRosterImport.normalizePhone("847.372.5936"));
        assertEquals("847-372-5936", NspRosterImport.normalizePhone("1-847-372-5936"));
        assertEquals("847-372-5936", NspRosterImport.normalizePhone("8473725936"));

        assertEquals("", NspRosterImport.normalizePhone("372-5936"), "too short to be trusted");
        assertEquals("", NspRosterImport.normalizePhone("ext 4412"));
        assertEquals("", NspRosterImport.normalizePhone(""));
        assertEquals("", NspRosterImport.normalizePhone(null));
    }

    @Test
    void aFileWithNoDataRowsImportsNothing() throws Exception {
        assertTrue(read(HEADER + "\n", Set.of()).isEmpty());
        assertTrue(read("", Set.of()).isEmpty());
    }

    /** Trailing blank lines are ordinary in an export and must not become empty patrollers. */
    @Test
    void blankLinesAreIgnored() throws Exception {
        String csv = HEADER + "\n"
                       + row("100001", "Pat", "Ron", "Patroller", "a@example.com", "") + "\n"
                       + "\n\n";

        assertEquals(1, read(csv, Set.of()).size());
    }

    @Test
    void everyNewMemberIsAFullTimeBasicPatrollerWhoIsNotADirector() {
        assertEquals("BAS", NspRosterImport.NEW_CLASSIFICATION);
        assertEquals("2", NspRosterImport.NEW_COMMITMENT, "2 is Full Time in the roster");
        assertFalse(NspRosterImport.NEW_DIRECTOR.toLowerCase().startsWith("y"),
                    "isDirector reads the first letter, so this must not begin with Y");
    }
}
