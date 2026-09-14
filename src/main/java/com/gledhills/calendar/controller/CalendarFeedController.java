package com.gledhills.calendar.controller;

import com.gledhills.calendar.service.CalendarFeedService;
import com.gledhills.calendar.store.CalendarFeedToken;
import com.gledhills.calendar.store.DirectorSettings;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;

/**
 * The subscription feed a patroller's own calendar fetches.
 *
 * <p><b>The only page on this site with no session.</b> A calendar app fetches a URL on a timer -
 * it cannot sign in, cannot be redirected to a login, and cannot be prompted for anything - so the
 * token in the path is the whole of the authentication. Everything here follows from that:
 *
 * <ul>
 *   <li>the resort is in the path, so the token is looked up in <b>one</b> resort's table. Member
 *       ids are unique only within a resort and so are these; searching every resort for a token
 *       would be the same mistake {@code EmailRecipient} exists to avoid, with a worse ending</li>
 *   <li>every failure answers <b>404</b> and nothing else. A 401 would invite retrying, and telling
 *       the difference between "no such resort", "no such token" and "switched off" would turn the
 *       URL into an oracle for guessing tokens</li>
 *   <li>no redirect, ever. A calendar client following one to a login page caches the HTML and the
 *       patroller sees an empty calendar with no explanation</li>
 * </ul>
 *
 * <p>Served only where the feature is switched on: the resort must be cleared for it
 * ({@link PatrolData#maySwitchOnCalendarSync}) <b>and</b> its director must have ticked Enabled.
 * Turning the switch off stops every subscription at that resort on the next fetch.
 *
 * <p>Note {@code BotBlockingFilter} has to let this path through. A calendar client sends no
 * User-Agent, or one containing "java/", both of which that filter treats as a bot - so without the
 * exemption every subscriber gets a silent 403 forever.
 */
@Controller
public class CalendarFeedController {

  /** The path the bot filter exempts. Kept here so the two cannot drift apart. */
  public static final String FEED_PREFIX = "/feed/";

  @GetMapping(FEED_PREFIX + "{resort}/{token}.ics")
  public void feed(@PathVariable String resort,
                   @PathVariable String token,
                   HttpServletRequest request,
                   HttpServletResponse response) throws IOException {

    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(CalendarFeedController.class, javaxRequest, "GET", resort, Logger.INFO);

    if (!PatrolData.isValidResort(resort) || !PatrolData.maySwitchOnCalendarSync(resort)) {
      response.sendError(404);
      return;
    }

    PatrolData patrolData = new PatrolData(resort, new SessionData(javaxRequest, null, LOG), LOG);
    try {
      DirectorSettings settings = patrolData.readDirectorSettings();
      if (settings == null || !settings.getSyncEnabled()) {
        response.sendError(404);
        return;
      }

      String patrollerId =
        CalendarFeedToken.patrollerFor(LOG, patrolData.getConnection(), token);
      if (patrollerId == null) {
        LOG.info("calendar feed: no such token at " + resort);
        response.sendError(404);
        return;
      }

      String ics = CalendarFeedService.buildFeed(resort, patrollerId, patrolData, LOG);

      response.setContentType("text/calendar; charset=UTF-8");
      //Named so a patroller who opens it in a browser gets something recognisable rather than a
      //wall of text, and so a desktop client filing it has a sensible name.
      response.setHeader("Content-Disposition",
                         "inline; filename=\"" + resort + "-patrol-shifts.ics\"");
      //The schedule changes when a director changes it, not on a timer - a cached copy is how a
      //patroller misses the shift they just signed up for.
      response.setHeader("Cache-Control", "no-cache, must-revalidate");
      response.getWriter().write(ics);
    }
    finally {
      patrolData.close();
    }
  }
}
