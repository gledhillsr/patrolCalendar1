package com.gledhills.calendar.config;

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
