package com.gledhills.calendar.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the filter criteria EmailForm hands to Roster.okToDisplay.
 */
class EmailFormRequestTest {

    /** Criteria with nothing set; individual tests build on this with the canonical constructor. */
    private static EmailFormRequest none() {
        return new EmailFormRequest(
            null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null,
            null, null,
            null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null);
    }

    @Test
    void everybody_matchesEveryMemberAndNothingElse() {
        EmailFormRequest request = EmailFormRequest.everybody();

        assertTrue(request.isEveryBody());
        assertFalse(request.isSubList());
        assertFalse(request.isAll());
        assertFalse(request.isListDirector());
        assertEquals(List.of(), request.classifications());
        assertEquals(0, request.commitmentMask());
        assertEquals(0, request.instructorMask());
        assertFalse(request.hasDateRange());
        assertEquals(0, request.minShiftsThreshold());
    }

    @Test
    void classifications_listsOnlyTheFlagsThatAreSet() {
        EmailFormRequest request = new EmailFormRequest(
            null, null,
            true, null, true, null, null, null, null, null, null, true, true,  // BAS, SR, OTH, NSK
            null, null, null,
            null, null,
            null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null);

        assertEquals(List.of("BAS", "SR", "OTH", "NSK"), request.classifications());
    }

    @Test
    void commitmentMask_combinesFullPartAndInactive() {
        assertEquals(0, none().commitmentMask());
        assertEquals(4, withCommitment(true, false, false).commitmentMask());
        assertEquals(2, withCommitment(false, true, false).commitmentMask());
        assertEquals(1, withCommitment(false, false, true).commitmentMask());
        assertEquals(7, withCommitment(true, true, true).commitmentMask());
    }

    @Test
    void instructorMask_combinesOecCprSkiTobogganAndSnowboard() {
        assertEquals(0, none().instructorMask());
        assertEquals(1, withInstructor(true, false, false, false, false).instructorMask());
        assertEquals(2, withInstructor(false, true, false, false, false).instructorMask());
        assertEquals(4, withInstructor(false, false, true, false, false).instructorMask());
        assertEquals(8, withInstructor(false, false, false, true, false).instructorMask());
        assertEquals(16, withInstructor(false, false, false, false, true).instructorMask());
        assertEquals(31, withInstructor(true, true, true, true, true).instructorMask());
    }

    @Test
    void minShiftsThreshold_appliesOnlyWhenMinDaysFlagIsSet() {
        assertEquals(0, none().minShiftsThreshold());
        assertEquals(0, withMinDays(5, false).minShiftsThreshold());
        assertEquals(0, withMinDays(null, true).minShiftsThreshold());
        assertEquals(5, withMinDays(5, true).minShiftsThreshold());
    }

    @Test
    void hasDateRange_followsStartYear() {
        assertFalse(none().hasDateRange());

        EmailFormRequest request = new EmailFormRequest(
            null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null,
            null, null,
            null, null, null, null, null,
            null, null, null, null, null, null,
            1, 0, 2026, 31, 11, 2026,
            null, null);

        assertTrue(request.hasDateRange());
    }

    private static EmailFormRequest withCommitment(boolean fullTime, boolean partTime, boolean inactive) {
        return new EmailFormRequest(
            null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            fullTime, partTime, inactive,
            null, null,
            null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null);
    }

    private static EmailFormRequest withInstructor(boolean oec, boolean cpr, boolean ski,
                                                   boolean toboggan, boolean snowboard) {
        return new EmailFormRequest(
            null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null,
            null, null,
            oec, cpr, ski, toboggan, snowboard,
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null);
    }

    private static EmailFormRequest withMinDays(Integer minDays, boolean minDaysFlag) {
        return new EmailFormRequest(
            null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null,
            null, null,
            null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            minDays, minDaysFlag);
    }
}
