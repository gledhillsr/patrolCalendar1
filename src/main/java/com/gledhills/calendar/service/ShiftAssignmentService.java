package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.EmailFormRequest;
import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.utils.PatrolData;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for counting shifts worked in a date range.
 */
@Service
public class ShiftAssignmentService {

    /**
     * Keep only recipients who worked <b>fewer than</b> the requested number of shifts within the
     * requested date range. Only the shift types flagged in the request are counted.
     *
     * The checkbox this comes from reads "Only list if less than N shifts" on SetupListPatrollers,
     * and `RosterQueryService.applyMinDaysFilter` keeps `count < limit` to match. This has to agree
     * with it, or "email THESE patrollers" mails a different set of people than the page listed -
     * in fact the exact complement, which is how the two drifted unnoticed.
     *
     * @param recipients Recipients to filter
     * @param request Request containing date range, shift type flags and the limit
     * @param patrolData Patrol data accessor
     * @return Recipients under the limit, in the order supplied
     */
    public List<EmailRecipient> filterByShiftLimit(List<EmailRecipient> recipients,
                                                   EmailFormRequest request,
                                                   PatrolData patrolData) {
        int shiftLimit = request.minShiftsThreshold();
        if (shiftLimit <= 0) {
            return recipients;
        }

        Map<String, Integer> shiftsById = countShifts(recipients, request, patrolData);
        return recipients.stream()
            .filter(recipient -> shiftsById.getOrDefault(recipient.getId(), 0) < shiftLimit)
            .toList();
    }

    private Map<String, Integer> countShifts(List<EmailRecipient> recipients,
                                             EmailFormRequest request,
                                             PatrolData patrolData) {
        Map<String, Integer> shiftsById = new HashMap<>();
        for (EmailRecipient recipient : recipients) {
            shiftsById.put(recipient.getId(), 0);
        }

        int startDay = request.StartDay() != null ? request.StartDay() : 1;
        int startMonth = request.StartMonth() != null ? request.StartMonth() : 0;
        int startYear = request.StartYear() != null ? request.StartYear() : 2020;
        int endDay = request.EndDay() != null ? request.EndDay() : 31;
        int endMonth = request.EndMonth() != null ? request.EndMonth() : 11;
        int endYear = request.EndYear() != null ? request.EndYear() : 2030;

        long startMillis = new GregorianCalendar(startYear, startMonth, startDay).getTimeInMillis();
        long endMillis = new GregorianCalendar(endYear, endMonth, endDay).getTimeInMillis();

        // Note: ResultSet lifecycle is managed by PatrolData; it is closed via patrolData.close()
        ResultSet assignmentResults = patrolData.resetAssignments();
        Assignments assignment;
        while ((assignment = patrolData.readNextAssignment(assignmentResults)) != null) {
            long shiftMillis = new GregorianCalendar(
                assignment.getYear(), assignment.getMonth(), assignment.getDay()).getTimeInMillis();

            if (shiftMillis < startMillis || endMillis < shiftMillis || !isCountedShift(assignment, request)) {
                continue;
            }

            for (int position = 0; position < Assignments.MAX_ASSIGNMENT_SIZE; ++position) {
                shiftsById.computeIfPresent(assignment.getPosID(position), (id, count) -> count + 1);
            }
        }

        return shiftsById;
    }

    private boolean isCountedShift(Assignments assignment, EmailFormRequest request) {
        return (isSet(request.DAY_CNT()) && assignment.isDayShift())
            || (isSet(request.SWING_CNT()) && assignment.isSwingShift())
            || (isSet(request.NIGHT_CNT()) && assignment.isNightShift())
            || (isSet(request.TRAINING_CNT()) && assignment.isTrainingShift())
            || (isSet(request.OTHER_CNT()) && assignment.isOtherShift())
            || (isSet(request.HOLIDAY_CNT()) && assignment.isHolidayShift());
    }

    private static boolean isSet(Boolean flag) {
        return Boolean.TRUE.equals(flag);
    }
}
