package com.gledhills.calendar.service;

import com.gledhills.calendar.controller.ServletAdapter;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * What the header needs to know about whoever is looking at it.
 *
 * <p>Called straight from the template - {@code ${@headerViewerService.isDirector(resort, id)}} -
 * for the same reason {@link MenuButtonService} is: every page in the app draws this header, so
 * putting it in the model would mean touching every controller and remembering to do it in the
 * twenty-first.
 *
 * <p><b>The answer comes from the roster, not from the request.</b> A member id in a URL is not
 * authority for anything; this decides only what the header draws, and every director-only page
 * checks again for itself before doing any work.
 */
@Service
public class HeaderViewerService {

    /**
     * Whether this member is a director of this resort.
     *
     * <p>Never throws: a header that cannot reach the database draws no director button rather
     * than taking every page in the app down. Costs one short query per page, the same trade
     * MenuButtonService already makes - the alternative is threading a flag through every
     * controller in the application.
     */
    public boolean isDirector(String resort, String patrollerId) {
        if (patrollerId == null || patrollerId.trim().isEmpty()
              || !PatrolData.isValidResort(resort)) {
            return false;
        }

        PatrolData patrolData = null;
        try {
            ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return false;   //no request in flight: a unit test, or DailyReminder
            }
            javax.servlet.http.HttpServletRequest javaxRequest =
                ServletAdapter.toJavaxRequest(attributes.getRequest());
            Logger log = new Logger(HeaderViewerService.class, javaxRequest, "GET", resort,
                                    Logger.INFO);
            SessionData sessionData = new SessionData(javaxRequest, null, log);

            //authority is the session, never the id the page was asked for
            String loggedIn = sessionData.getLoggedInUserId();
            if (loggedIn == null || loggedIn.trim().isEmpty()
                  || !resort.equals(sessionData.getLoggedInResort())) {
                return false;
            }

            patrolData = new PatrolData(resort, sessionData, log);
            Roster member = patrolData.getMemberByID(loggedIn);
            return member != null && member.isDirector();
        }
        catch (Exception e) {
            return false;
        }
        finally {
            if (patrolData != null) {
                try {
                    patrolData.close();
                }
                catch (Exception ignored) {
                    //closing a connection we are finished with cannot break a page render
                }
            }
        }
    }
}
