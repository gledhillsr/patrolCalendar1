package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.EmailFormRequest;
import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.StaticUtils;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Service for filtering patrol members based on various criteria.
 */
@Service
public class PatrolMemberFilterService {

    /**
     * Filter patrol members based on request criteria.
     *
     * @param request Filter criteria from query parameters
     * @param patrolData Patrol data accessor
     * @return List of filtered recipients
     */
    public List<EmailRecipient> filterMembers(EmailFormRequest request, PatrolData patrolData) {
        List<EmailRecipient> recipients = new ArrayList<>();

        // Build classification filter list
        ArrayList<String> classificationsToDisplay = buildClassificationFilter(request);
        int commitmentToDisplay = buildCommitmentFilter(request);
        int instructorFlags = buildInstructorFlags(request);

        // Iterate roster and filter
        // Note: ResultSet lifecycle is managed by PatrolData; it should be closed via patrolData.close()
        ResultSet rosterResults = patrolData.resetRoster();
        Roster member;
        while ((member = patrolData.nextMember("&nbsp;", rosterResults)) != null) {
            if (member.okToDisplay(
                Boolean.TRUE.equals(request.getEveryBody()),
                Boolean.TRUE.equals(request.getSubList()),
                Boolean.TRUE.equals(request.getALL()),
                classificationsToDisplay,
                commitmentToDisplay,
                Boolean.TRUE.equals(request.getListDirector()),
                instructorFlags,
                0)) {

                recipients.add(convertToEmailRecipient(member));
            }
        }

        return recipients;
    }

    private ArrayList<String> buildClassificationFilter(EmailFormRequest request) {
        ArrayList<String> classifications = new ArrayList<>();
        if (Boolean.TRUE.equals(request.getBAS())) classifications.add("BAS");
        if (Boolean.TRUE.equals(request.getINA())) classifications.add("INA");
        if (Boolean.TRUE.equals(request.getSR())) classifications.add("SR");
        if (Boolean.TRUE.equals(request.getSRA())) classifications.add("SRA");
        if (Boolean.TRUE.equals(request.getALM())) classifications.add("ALM");
        if (Boolean.TRUE.equals(request.getPRO())) classifications.add("PRO");
        if (Boolean.TRUE.equals(request.getAUX())) classifications.add("AUX");
        if (Boolean.TRUE.equals(request.getTRA())) classifications.add("TRA");
        if (Boolean.TRUE.equals(request.getCAN())) classifications.add("CAN");
        if (Boolean.TRUE.equals(request.getOTH())) classifications.add("OTH");
        return classifications;
    }

    private int buildCommitmentFilter(EmailFormRequest request) {
        int commitment = 0;
        if (Boolean.TRUE.equals(request.getFullTime())) commitment += 4;
        if (Boolean.TRUE.equals(request.getPartTime())) commitment += 2;
        if (Boolean.TRUE.equals(request.getInactive())) commitment += 1;
        return commitment;
    }

    private int buildInstructorFlags(EmailFormRequest request) {
        int flags = 0;
        if (Boolean.TRUE.equals(request.getOEC())) flags += 1;
        if (Boolean.TRUE.equals(request.getCPR())) flags += 2;
        if (Boolean.TRUE.equals(request.getSki())) flags += 4;
        if (Boolean.TRUE.equals(request.getToboggan())) flags += 8;
        return flags;
    }

    private EmailRecipient convertToEmailRecipient(Roster member) {
        String email = member.getEmailAddress();
        boolean hasValidEmail = StaticUtils.isValidEmailAddress(email);

        return new EmailRecipient(
            member.getID(),
            member.getFirst(),
            member.getLast(),
            email,
            hasValidEmail,
            new HashMap<>(),  // Empty shift counts initially
            member.getCarryOverCredits(),
            member.getCreditsEarned()
        );
    }
}
