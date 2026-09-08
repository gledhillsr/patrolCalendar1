package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.EmailRecipient;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.StaticUtils;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every director at every resort, for the site owner's "tell all the directors" mailing.
 *
 * This is the only recipient list on the site that spans resorts, so it is the only one where a
 * patroller id is not enough to name somebody - see {@link EmailRecipient#getSelectionKey()}.
 *
 * Two things it deliberately does <em>not</em> do:
 *
 * <ul>
 *   <li><b>Sample is skipped.</b> It is the demo resort - its "directors" are made-up people, and
 *       EmailService refuses to send from it anyway.</li>
 *   <li><b>It never throws.</b> A resort in {@code PatrolData.resortMap} may have no database yet;
 *       that resort contributes nobody and the rest of the list still goes out. Losing one resort's
 *       directors from a broadcast is a far smaller problem than losing the ability to send it.</li>
 * </ul>
 *
 * Walking 47 databases is slow enough to notice, which is why nothing calls this on an ordinary
 * page - only the site owner's own two requests.
 */
@Service
public class SiteDirectorsService {

    /**
     * Directors of every resort whose members are real - see {@link PatrolData#isDemoResort},
     * which covers Sample and its nightly master SampleBackup. Their "directors" are invented
     * people with invented addresses.
     *
     * Someone who directs two resorts is listed once, under whichever resort comes first
     * alphabetically: this list exists to notify people, and telling the same person twice about
     * one outage is just noise. Matching is on email address, which is the thing that would
     * receive the duplicate; a director with no usable address cannot collide with anyone and is
     * kept so the page can show them in the "no valid email" column.
     */
    public List<EmailRecipient> allDirectors(SessionData sessionData, Logger LOG) {
        List<EmailRecipient> directors = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        int resortsWithDirectors = 0;

        for (String resort : new TreeSet<>(PatrolData.resortMap.keySet())) {
            if (PatrolData.isDemoResort(resort)) {
                continue;
            }
            List<EmailRecipient> found = directorsAt(resort, seenEmails, sessionData, LOG);
            if (!found.isEmpty()) {
                resortsWithDirectors++;
            }
            directors.addAll(found);
        }
        //resorts that contributed, not resorts asked: most of the map has no database yet, and a
        //count of everything in it would read as though they all answered
        LOG.info("site wide directors: " + directors.size() + " from " + resortsWithDirectors
                   + " resorts");
        return directors;
    }

    /** One resort's directors, or an empty list if it cannot be read. */
    private List<EmailRecipient> directorsAt(String resort, Set<String> seenEmails,
                                             SessionData sessionData, Logger LOG) {
        List<EmailRecipient> found = new ArrayList<>();
        PatrolData patrolData = null;
        try {
            patrolData = new PatrolData(resort, sessionData, LOG);
            if (patrolData.getConnection() == null) {
                LOG.info("site wide directors: no database for " + resort);
                return found;
            }
            ResultSet rosterResults = patrolData.resetRoster();
            Roster member;
            while ((member = patrolData.nextMember("&nbsp;", rosterResults)) != null) {
                if (!member.isDirector()) {
                    continue;
                }
                String email = member.getEmailAddress();
                boolean validEmail = StaticUtils.isValidEmailAddress(email);
                if (validEmail && !seenEmails.add(email.trim().toLowerCase(Locale.ROOT))) {
                    continue; //already directing somewhere earlier in the alphabet
                }
                found.add(new EmailRecipient(member.getID(), member.getFirst(), member.getLast(),
                                             email, validEmail, resort));
            }
        }
        catch (Exception e) {
            //one unreachable resort must not cost the site owner the whole broadcast
            LOG.error("site wide directors: could not read " + resort + " - "
                        + e.getClass().getSimpleName());
        }
        finally {
            if (patrolData != null) {
                try {
                    patrolData.close();
                }
                catch (Exception e) {
                    //nothing useful to do; the members are already collected
                }
            }
        }
        return found;
    }
}
