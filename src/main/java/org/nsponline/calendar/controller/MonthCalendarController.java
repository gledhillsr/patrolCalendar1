package org.nsponline.calendar.controller;

import org.nsponline.calendar.controller.ServletAdapter;
import org.nsponline.calendar.resources.MonthCalendar;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.Locale;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;

/**
 * Spring Boot controller for month calendar view.
 * Migrated from OuterMonthCalendar servlet.
 *
 * Handles GET /MonthCalendar - displays monthly shift schedule calendar
 *
 * IMPLEMENTATION NOTE:
 * MonthCalendar contains complex calendar rendering logic with ~750 lines
 * of HTML generation code. Rather than fully rewriting this logic into Thymeleaf
 * (which would be error-prone and time-consuming), this controller uses a
 * delegation approach:
 *
 * 1. Instantiate the legacy MonthCalendar servlet
 * 2. Capture its HTML output into a StringWriter
 * 3. Pass the rendered HTML to Thymeleaf as a pre-rendered fragment
 *
 * This preserves all existing calendar logic (date calculations, shift rendering,
 * assignment display, season/blackout handling) while moving to Spring Boot infrastructure.
 *
 * Future refactoring could incrementally extract calendar logic into proper
 * model/view separation, but that's beyond the scope of servlet migration.
 */
@Controller
public class MonthCalendarController {

  @GetMapping("/MonthCalendar")
  public String showMonthCalendar(HttpServletRequest request, HttpServletResponse response,
                                  Model model) throws IOException {
    // 1. Read parameters
    String resort = request.getParameter("resort");

    // 2. Use ServletAdapter for legacy code compatibility
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
    Logger LOG = new Logger(MonthCalendarController.class, javaxRequest, "GET", resort, Logger.INFO);

    try {
      // 3. Capture HTML output from legacy servlet
      StringWriter stringWriter = new StringWriter();
      PrintWriter printWriter = new PrintWriter(stringWriter);

      // Create custom response wrapper that captures output
      javax.servlet.http.HttpServletResponse capturingResponse =
        new CapturingHttpServletResponse(javaxResponse, printWriter);

      // 4. Instantiate and run legacy servlet
      MonthCalendar servlet = new MonthCalendar(javaxRequest, capturingResponse, LOG);
      servlet.runner("MonthCalendar");

      printWriter.flush();
      String calendarHtml = stringWriter.toString();

      // 5. Prepare model for template
      model.addAttribute("resort", resort);
      model.addAttribute("calendarHtml", calendarHtml);

      return "month-calendar";

    }
    catch (Exception e) {
      LOG.logException("Error displaying month calendar: ", e);
      response.sendError(500, "Error displaying calendar");
      return null;
    }
  }

    /**
     * HttpServletResponse wrapper that captures PrintWriter output
     */
    private record CapturingHttpServletResponse(javax.servlet.http.HttpServletResponse wrapped,
                                                PrintWriter writer) implements javax.servlet.http.HttpServletResponse {

        @Override
        public PrintWriter getWriter() {
            return writer;
        }

        // Delegate all other methods to wrapped response
        @Override
        public void addCookie(Cookie cookie) {
            wrapped.addCookie(cookie);
        }

        @Override
        public boolean containsHeader(String name) {
            return wrapped.containsHeader(name);
        }

        @Override
        public String encodeURL(String url) {
            return wrapped.encodeURL(url);
        }

        @Override
        public String encodeRedirectURL(String url) {
            return wrapped.encodeRedirectURL(url);
        }

        @Override
        public String encodeUrl(String url) {
            return wrapped.encodeUrl(url);
        }

        @Override
        public String encodeRedirectUrl(String url) {
            return wrapped.encodeRedirectUrl(url);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            wrapped.sendError(sc, msg);
        }

        @Override
        public void sendError(int sc) throws IOException {
            wrapped.sendError(sc);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            wrapped.sendRedirect(location);
        }

        @Override
        public void setDateHeader(String name, long date) {
            wrapped.setDateHeader(name, date);
        }

        @Override
        public void addDateHeader(String name, long date) {
            wrapped.addDateHeader(name, date);
        }

        @Override
        public void setHeader(String name, String value) {
            wrapped.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            wrapped.addHeader(name, value);
        }

        @Override
        public void setIntHeader(String name, int value) {
            wrapped.setIntHeader(name, value);
        }

        @Override
        public void addIntHeader(String name, int value) {
            wrapped.addIntHeader(name, value);
        }

        @Override
        public void setStatus(int sc) {
            wrapped.setStatus(sc);
        }

        @Override
        public void setStatus(int sc, String sm) {
            wrapped.setStatus(sc, sm);
        }

        @Override
        public String getCharacterEncoding() {
            return wrapped.getCharacterEncoding();
        }

        @Override
        public String getContentType() {
            return wrapped.getContentType();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return wrapped.getOutputStream();
        }

        @Override
        public void setCharacterEncoding(String charset) {
            wrapped.setCharacterEncoding(charset);
        }

        @Override
        public void setContentLength(int len) {
            wrapped.setContentLength(len);
        }

        @Override
        public void setContentType(String type) {
            wrapped.setContentType(type);
        }

        @Override
        public void setBufferSize(int size) {
            wrapped.setBufferSize(size);
        }

        @Override
        public int getBufferSize() {
            return wrapped.getBufferSize();
        }

        @Override
        public void flushBuffer() throws IOException {
            wrapped.flushBuffer();
        }

        @Override
        public void resetBuffer() {
            wrapped.resetBuffer();
        }

        @Override
        public boolean isCommitted() {
            return wrapped.isCommitted();
        }

        @Override
        public void reset() {
            wrapped.reset();
        }

        @Override
        public void setLocale(Locale loc) {
            wrapped.setLocale(loc);
        }

        @Override
        public Locale getLocale() {
            return wrapped.getLocale();
        }
    }
}
