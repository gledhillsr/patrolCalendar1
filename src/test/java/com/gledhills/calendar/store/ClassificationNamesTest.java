package com.gledhills.calendar.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The classification codes stored in the roster, and the names a director reads.
 *
 * <p>The codes are what twenty years of rows hold and what every query parameter says; only the
 * names changed when the patrol renamed them. These pin the pairing, because two separate things
 * now depend on it: the roster pages show the name rather than the code, and the nsp.org import
 * resolves nsp.org's class wording back to a code.
 */
class ClassificationNamesTest {

    @Test
    void eachCodeHasTheNameTheResortUses() {
        assertEquals("Alpine", Roster.getClassificationName("BAS"));
        assertEquals("Senior Alpine", Roster.getClassificationName("SR"));
        assertEquals("Senior Patroller", Roster.getClassificationName("SRA"));
        assertEquals("Patroller", Roster.getClassificationName("AUX"));
        assertEquals("Paid", Roster.getClassificationName("PRO"));
        assertEquals("Alumni", Roster.getClassificationName("ALM"));
        assertEquals("Transfer", Roster.getClassificationName("TRA"));
        assertEquals("Candidate", Roster.getClassificationName("CAN"));
        assertEquals("Not Skiing", Roster.getClassificationName("NSK"));
        assertEquals("Other", Roster.getClassificationName("OTH"));
    }

    /**
     * Both directions off one table, so renaming a classification cannot leave the import
     * matching the old word while the pages show the new one.
     */
    @Test
    void everyNameResolvesBackToItsOwnCode() {
        for (String code : List.of("BAS", "SR", "SRA", "ALM", "PRO", "AUX", "TRA", "CAN", "NSK",
                                   "OTH")) {
            assertEquals(code, Roster.getClassificationCode(Roster.getClassificationName(code)),
                         "round trip for " + code);
        }
    }

    /** Two words, so the space inside it has to survive the match. */
    @Test
    void aTwoWordNameResolves() {
        assertEquals("NSK", Roster.getClassificationCode("Not Skiing"));
        assertEquals("NSK", Roster.getClassificationCode("not skiing"));
    }

    @Test
    void aNameIsMatchedWithoutRegardToCaseOrSurroundingSpace() {
        assertEquals("SRA", Roster.getClassificationCode("senior patroller"));
        assertEquals("PRO", Roster.getClassificationCode("  Paid "));
    }

    /** A code handed in where a name was expected is still understood. */
    @Test
    void aCodeIsAcceptedAsItself() {
        assertEquals("BAS", Roster.getClassificationCode("BAS"));
        assertEquals("SRA", Roster.getClassificationCode("sra"));
    }

    /**
     * Null rather than a guess: the caller decides what an unrecognised class becomes, and the
     * nsp.org import needs to know it was unrecognised so it can say so.
     */
    @Test
    void somethingThatIsNeitherGivesNull() {
        assertNull(Roster.getClassificationCode("Nordic"));
        assertNull(Roster.getClassificationCode(""));
        assertNull(Roster.getClassificationCode("   "));
        assertNull(Roster.getClassificationCode(null));
    }

    /**
     * INA is on real rosters and is deliberately not in the table, so it has no name of its own.
     * Showing the code beats showing a blank cell where a member plainly has a classification.
     */
    @Test
    void aCodeWithNoNameShowsAsItself() {
        assertEquals("INA", Roster.getClassificationName("INA"));
        assertEquals("", Roster.getClassificationName(null));
    }
}
