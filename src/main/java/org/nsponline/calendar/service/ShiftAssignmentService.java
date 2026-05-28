package org.nsponline.calendar.service;

import org.nsponline.calendar.dto.EmailFormRequest;
import org.nsponline.calendar.dto.EmailRecipient;
import org.nsponline.calendar.store.Assignments;
import org.nsponline.calendar.utils.PatrolData;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.*;

/**
 * Service for calculating shift counts and enriching recipient data.
 */
@Service
public class ShiftAssignmentService {

    /**
     * Enrich recipients with shift counts for the specified date range.
     *
     * @param recipients List of recipients to enrich
     * @param request Request containing date range and shift type flags
     * @param patrolData Patrol data accessor
     * @return Enriched recipients with shift counts, filtered by MinDays if enabled
     */
    public List<EmailRecipient> enrichWithShiftCounts(List<EmailRecipient> recipients,
                                                      EmailFormRequest request,
                                                      PatrolData patrolData) {
        // Create lookup map
        Map<String, MutableRecipientData> recipientMap = new HashMap<>();
        for (EmailRecipient recipient : recipients) {
            recipientMap.put(recipient.getId(), new MutableRecipientData(recipient));
        }

        // Calculate date range
        int startDay = request.getStartDay() != null ? request.getStartDay() : 1;
        int startMonth = request.getStartMonth() != null ? request.getStartMonth() : 0;
        int startYear = request.getStartYear() != null ? request.getStartYear() : 2020;
        int endDay = request.getEndDay() != null ? request.getEndDay() : 31;
        int endMonth = request.getEndMonth() != null ? request.getEndMonth() : 11;
        int endYear = request.getEndYear() != null ? request.getEndYear() : 2030;

        long startMillis = (new GregorianCalendar(startYear, startMonth, startDay)).getTimeInMillis();
        long endMillis = (new GregorianCalendar(endYear, endMonth, endDay)).getTimeInMillis();

        // Read assignments and count shifts
        ResultSet assignmentResults = patrolData.resetAssignments();
        Assignments assignment;
        while ((assignment = patrolData.readNextAssignment(assignmentResults)) != null) {
            long currMillis = (new GregorianCalendar(
                assignment.getYear(),
                assignment.getMonth(),
                assignment.getDay()
            )).getTimeInMillis();

            if (startMillis <= currMillis && currMillis <= endMillis) {
                for (int i = 0; i < Assignments.MAX_ASSIGNMENT_SIZE; ++i) {
                    String memberId = assignment.getPosID(i);
                    MutableRecipientData recipientData = recipientMap.get(memberId);

                    if (recipientData != null) {
                        countShift(assignment, recipientData, request);
                    }
                }
            }
        }

        // Filter by MinDays and convert back to immutable
        int minDays = (Boolean.TRUE.equals(request.getMIN_DAYS()) && request.getMinDays() != null)
            ? request.getMinDays() : 0;

        List<EmailRecipient> enriched = new ArrayList<>();
        for (MutableRecipientData data : recipientMap.values()) {
            if (data.getTotalShiftCount() >= minDays) {
                enriched.add(data.toEmailRecipient());
            }
        }

        return enriched;
    }

    private void countShift(Assignments assignment, MutableRecipientData recipientData, EmailFormRequest request) {
        if (Boolean.TRUE.equals(request.getDAY_CNT()) && assignment.isDayShift()) {
            recipientData.incrementShiftCount("DAY");
        } else if (Boolean.TRUE.equals(request.getSWING_CNT()) && assignment.isSwingShift()) {
            recipientData.incrementShiftCount("SWING");
        } else if (Boolean.TRUE.equals(request.getNIGHT_CNT()) && assignment.isNightShift()) {
            recipientData.incrementShiftCount("NIGHT");
        } else if (Boolean.TRUE.equals(request.getTRAINING_CNT()) && assignment.isTrainingShift()) {
            recipientData.incrementShiftCount("TRAINING");
        } else if (Boolean.TRUE.equals(request.getOTHER_CNT()) && assignment.isOtherShift()) {
            recipientData.incrementShiftCount("OTHER");
        } else if (Boolean.TRUE.equals(request.getHOLIDAY_CNT()) && assignment.isHolidayShift()) {
            recipientData.incrementShiftCount("HOLIDAY");
        }
    }

    /**
     * Mutable wrapper for accumulating shift counts during processing.
     */
    private static class MutableRecipientData {
        private final EmailRecipient original;
        private final Map<String, Integer> shiftCounts;

        MutableRecipientData(EmailRecipient original) {
            this.original = original;
            this.shiftCounts = new HashMap<>();
        }

        void incrementShiftCount(String type) {
            shiftCounts.merge(type, 1, Integer::sum);
        }

        int getTotalShiftCount() {
            return shiftCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        EmailRecipient toEmailRecipient() {
            return new EmailRecipient(
                original.getId(),
                original.getFirstName(),
                original.getLastName(),
                original.getEmailAddress(),
                original.isHasValidEmail(),
                shiftCounts,
                original.getCarryOverCredits(),
                original.getCreditsEarned()
            );
        }
    }
}
