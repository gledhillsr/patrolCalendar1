package com.gledhills.calendar.config;

import com.gledhills.calendar.controller.CalendarFeedController;
import com.gledhills.calendar.controller.LockerRoomApiController;
import com.gledhills.calendar.controller.ServletAdapter;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.StaticUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects requests from web crawlers, scrapers, and scanners before they reach
 * any controller (replaces the bot check the legacy servlets did in ResourceBase,
 * which the migrated Spring controllers no longer call).
 */
@Component
public class BotBlockingFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
    throws ServletException, IOException {
    //The locker-room API authenticates with a shared key, which is a far stronger statement about
    //who is calling than a User-Agent string. Its caller is a PHP script on a Mac at the mountain -
    //exactly the shape this filter exists to reject - so checking it here would only ever produce a
    //confusing 403 for a legitimate client.
    if (request.getRequestURI() != null &&
        request.getRequestURI().contains(LockerRoomApiController.API_PREFIX)) {
      chain.doFilter(request, response);
      return;
    }
    //A calendar subscription is fetched by a calendar app, which sends no User-Agent at all or one
    //containing "java/" - both of which this filter calls a bot. Blocking it would give every
    //subscriber a silent 403 forever, and the patroller's calendar would simply stay empty with
    //nothing anywhere to say why. The token in the path is what guards the feed.
    if (request.getRequestURI() != null &&
        request.getRequestURI().contains(CalendarFeedController.FEED_PREFIX)) {
      chain.doFilter(request, response);
      return;
    }
    String userAgent = request.getHeader("User-Agent");
    if (StaticUtils.isRequestFromBot(userAgent)) {
      Logger LOG = new Logger(BotBlockingFilter.class, ServletAdapter.toJavaxRequest(request),
                              request.getMethod(), request.getParameter("resort"), Logger.INFO);
//      LOG.info("Blocked bot request: uri=" + request.getRequestURI() +
//                 ", query=(" + request.getQueryString() + "), userAgent=(" + userAgent + ")");
      response.setStatus(403);
      response.setContentType("text/plain");
      response.getWriter().write("Automated requests are not allowed.");
      return;
    }
    chain.doFilter(request, response);
  }
}
