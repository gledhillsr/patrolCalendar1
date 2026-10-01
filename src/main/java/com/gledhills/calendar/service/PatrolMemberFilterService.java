package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.EmailFormRequest;
import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.StaticUtils;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.ArrayList;
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
     * @return List of filtered recipients, in roster order
     */
    public List<EmailRecipient> filterMembers(EmailFormRequest request, PatrolData patrolData) {
        List<String> classifications = request.classifications();
        int commitmentMask = request.commitmentMask();
        int instructorMask = request.instructorMask();

        List<EmailRecipient> recipients = new ArrayList<>();

        // Note: ResultSet lifecycle is managed by PatrolData; it is closed via patrolData.close()
        ResultSet rosterResults = patrolData.resetRoster();
        Roster member;
        while ((member = patrolData.nextMember("&nbsp;", rosterResults)) != null) {
            if (member.okToDisplay(request.isEveryBody(), request.isSubList(), request.isAll(),
                                   classifications, commitmentMask,
                                   request.isListDirector(), instructorMask, 0)) {
                recipients.add(toRecipient(member));
            }
        }

        return recipients;
    }

    /**
     * The one place a roster row becomes a recipient, which is why the mobile number is worked out
     * here rather than at either send: every audience this service builds gets both contact
     * details, and neither channel has to remember to look one up.
     */
    private EmailRecipient toRecipient(Roster member) {
        String email = member.getEmailAddress();
        return new EmailRecipient(
            member.getID(),
            member.getFirst(),
            member.getLast(),
            email,
            StaticUtils.isValidEmailAddress(email),
            null,
            StaticUtils.toSmsNumber(member.getCellPhone())
        );
    }
}
