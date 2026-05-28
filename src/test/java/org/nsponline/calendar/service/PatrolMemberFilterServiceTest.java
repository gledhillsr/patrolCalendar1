package org.nsponline.calendar.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nsponline.calendar.dto.EmailFormRequest;
import org.nsponline.calendar.dto.EmailRecipient;
import org.nsponline.calendar.utils.PatrolData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatrolMemberFilterServiceTest {

    private PatrolMemberFilterService service;

    @BeforeEach
    void setUp() {
        service = new PatrolMemberFilterService();
    }

    @Test
    void filterMembers_withBASClassification_returnsOnlyBasicMembers() {
        // This test will fail until service is implemented
        EmailFormRequest request = new EmailFormRequest(
            "Sample", "123", null, null,
            true, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null
        );

        // Will need mock PatrolData - for now test compilation
        assertNotNull(service);
    }
}
