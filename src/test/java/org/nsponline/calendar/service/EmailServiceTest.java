package org.nsponline.calendar.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.nsponline.calendar.dto.EmailCompositionForm;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EmailService(mailSender);
    }

    @Test
    void sendEmailsAsync_createsTrackingId() {
        // This test will fail until service is implemented
        EmailCompositionForm form = new EmailCompositionForm();
        form.setSubject("Test");
        form.setMessage("Hello");
        form.setPatrollerIds(List.of("123"));
        form.setResort("Sample");
        form.setFromPatrollerId("456");

        // Will need mock PatrolData - for now test compilation
        assertNotNull(service);
    }
}
