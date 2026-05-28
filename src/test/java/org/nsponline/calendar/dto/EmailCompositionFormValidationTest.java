package org.nsponline.calendar.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EmailCompositionForm validation annotations.
 */
class EmailCompositionFormValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void validate_withEmptySubject_failsValidation() {
        EmailCompositionForm form = new EmailCompositionForm();
        form.setSubject("");
        form.setMessage("Test message");
        form.setPatrollerIds(List.of("123"));

        Set<ConstraintViolation<EmailCompositionForm>> violations = validator.validate(form);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
            .anyMatch(v -> v.getMessage().contains("Subject is required")));
    }

    @Test
    void validate_withShortSubject_failsValidation() {
        EmailCompositionForm form = new EmailCompositionForm();
        form.setSubject("A");
        form.setMessage("Test message");
        form.setPatrollerIds(List.of("123"));

        Set<ConstraintViolation<EmailCompositionForm>> violations = validator.validate(form);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
            .anyMatch(v -> v.getMessage().contains("at least 2 characters")));
    }

    @Test
    void validate_withEmptyMessage_failsValidation() {
        EmailCompositionForm form = new EmailCompositionForm();
        form.setSubject("Test");
        form.setMessage("");
        form.setPatrollerIds(List.of("123"));

        Set<ConstraintViolation<EmailCompositionForm>> violations = validator.validate(form);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
            .anyMatch(v -> v.getMessage().contains("Message is required")));
    }

    @Test
    void validate_withShortMessage_failsValidation() {
        EmailCompositionForm form = new EmailCompositionForm();
        form.setSubject("Test");
        form.setMessage("Hi");
        form.setPatrollerIds(List.of("123"));

        Set<ConstraintViolation<EmailCompositionForm>> violations = validator.validate(form);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
            .anyMatch(v -> v.getMessage().contains("at least 4 characters")));
    }

    @Test
    void validate_withEmptyPatrollerIds_failsValidation() {
        EmailCompositionForm form = new EmailCompositionForm();
        form.setSubject("Test");
        form.setMessage("Test message");
        form.setPatrollerIds(new ArrayList<>());

        Set<ConstraintViolation<EmailCompositionForm>> violations = validator.validate(form);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
            .anyMatch(v -> v.getMessage().contains("At least one recipient")));
    }

    @Test
    void validate_withValidData_passesValidation() {
        EmailCompositionForm form = new EmailCompositionForm();
        form.setSubject("Test Subject");
        form.setMessage("Test message content");
        form.setPatrollerIds(List.of("123", "456"));

        Set<ConstraintViolation<EmailCompositionForm>> violations = validator.validate(form);

        assertTrue(violations.isEmpty());
    }
}
