package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Cookie;

import java.util.Enumeration;
import java.util.Collections;

/**
 * Adapter to bridge between jakarta.servlet (Spring Boot 3.x) and javax.servlet (legacy code).
 * This allows Spring Boot controllers to work with legacy utility classes during migration.
 *
 * Note: Only implements methods required by legacy SessionData, ValidateCredentialsRedirectIfNeeded,
 * and Logger classes. Servlet API 2.5 compatibility (many newer methods don't exist in 2.5).
 */
public class ServletAdapter {

  /**
   * Wraps a Jakarta HttpServletRequest to implement Javax HttpServletRequest interface.
   * Only implements methods used by legacy code (Servlet API 2.5 compatible).
   */
  public static javax.servlet.http.HttpServletRequest toJavaxRequest(
    final HttpServletRequest jakartaRequest) {

    return new javax.servlet.http.HttpServletRequest() {
      @Override
      public String getParameter(String name) {
        return jakartaRequest.getParameter(name);
      }

      @Override
      public String[] getParameterValues(String name) {
        return jakartaRequest.getParameterValues(name);
      }

      @Override
      public Enumeration<String> getParameterNames() {
        return jakartaRequest.getParameterNames();
      }

      @Override
      public java.util.Map<String, String[]> getParameterMap() {
        return jakartaRequest.getParameterMap();
      }

      @Override
      public javax.servlet.http.HttpSession getSession(boolean create) {
        HttpSession jakartaSession = jakartaRequest.getSession(create);
        return jakartaSession != null ? toJavaxSession(jakartaSession) : null;
      }

      @Override
      public javax.servlet.http.HttpSession getSession() {
        return getSession(true);
      }

      @Override
      public javax.servlet.http.Cookie[] getCookies() {
        Cookie[] jakartaCookies = jakartaRequest.getCookies();
        if (jakartaCookies == null) {
          return null;
        }

        javax.servlet.http.Cookie[] javaxCookies = new javax.servlet.http.Cookie[jakartaCookies.length];
        for (int i = 0; i < jakartaCookies.length; i++) {
          Cookie jc = jakartaCookies[i];
          javaxCookies[i] = new javax.servlet.http.Cookie(jc.getName(), jc.getValue());
          javaxCookies[i].setMaxAge(jc.getMaxAge());
          javaxCookies[i].setPath(jc.getPath());
          if (jc.getDomain() != null) {
            javaxCookies[i].setDomain(jc.getDomain());
          }
          javaxCookies[i].setSecure(jc.getSecure());
          // isHttpOnly() and setHttpOnly() not in Servlet API 2.5
        }
        return javaxCookies;
      }

      @Override
      public String getMethod() {
        return jakartaRequest.getMethod();
      }

      @Override
      public String getRequestURI() {
        return jakartaRequest.getRequestURI();
      }

      @Override
      public String getRemoteAddr() {
        return jakartaRequest.getRemoteAddr();
      }

      @Override
      public String getHeader(String name) {
        return jakartaRequest.getHeader(name);
      }

      @Override
      public Enumeration<String> getHeaders(String name) {
        return jakartaRequest.getHeaders(name);
      }

      @Override
      public Enumeration<String> getHeaderNames() {
        return jakartaRequest.getHeaderNames();
      }

      // Stub methods - only Servlet API 2.5 methods
      @Override
      public Object getAttribute(String name) {
        return jakartaRequest.getAttribute(name);
      }

      @Override
      public Enumeration getAttributeNames() {
        return jakartaRequest.getAttributeNames();
      }

      @Override
      public void setAttribute(String name, Object o) {
        jakartaRequest.setAttribute(name, o);
      }

      @Override
      public void removeAttribute(String name) {
        jakartaRequest.removeAttribute(name);
      }

      @Override
      public String getCharacterEncoding() {
        return jakartaRequest.getCharacterEncoding();
      }

      @Override
      public void setCharacterEncoding(String env) throws java.io.UnsupportedEncodingException {
        jakartaRequest.setCharacterEncoding(env);
      }

      @Override
      public int getContentLength() {
        return jakartaRequest.getContentLength();
      }

      @Override
      public String getContentType() {
        return jakartaRequest.getContentType();
      }

      @Override
      public javax.servlet.ServletInputStream getInputStream() throws java.io.IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public String getProtocol() {
        return jakartaRequest.getProtocol();
      }

      @Override
      public String getScheme() {
        return jakartaRequest.getScheme();
      }

      @Override
      public String getServerName() {
        return jakartaRequest.getServerName();
      }

      @Override
      public int getServerPort() {
        return jakartaRequest.getServerPort();
      }

      @Override
      public java.io.BufferedReader getReader() throws java.io.IOException {
        return jakartaRequest.getReader();
      }

      @Override
      public String getRemoteHost() {
        return jakartaRequest.getRemoteHost();
      }

      @Override
      public java.util.Locale getLocale() {
        return jakartaRequest.getLocale();
      }

      @Override
      public Enumeration getLocales() {
        return jakartaRequest.getLocales();
      }

      @Override
      public boolean isSecure() {
        return jakartaRequest.isSecure();
      }

      @Override
      public javax.servlet.RequestDispatcher getRequestDispatcher(String path) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String getRealPath(String path) {
        return null;
      } // Deprecated

      @Override
      public int getRemotePort() {
        return jakartaRequest.getRemotePort();
      }

      @Override
      public String getLocalName() {
        return jakartaRequest.getLocalName();
      }

      @Override
      public String getLocalAddr() {
        return jakartaRequest.getLocalAddr();
      }

      @Override
      public int getLocalPort() {
        return jakartaRequest.getLocalPort();
      }

      @Override
      public String getAuthType() {
        return jakartaRequest.getAuthType();
      }

      @Override
      public String getPathInfo() {
        return jakartaRequest.getPathInfo();
      }

      @Override
      public String getPathTranslated() {
        return jakartaRequest.getPathTranslated();
      }

      @Override
      public String getContextPath() {
        return jakartaRequest.getContextPath();
      }

      @Override
      public String getQueryString() {
        return jakartaRequest.getQueryString();
      }

      @Override
      public String getRemoteUser() {
        return jakartaRequest.getRemoteUser();
      }

      @Override
      public boolean isUserInRole(String role) {
        return jakartaRequest.isUserInRole(role);
      }

      @Override
      public java.security.Principal getUserPrincipal() {
        return jakartaRequest.getUserPrincipal();
      }

      @Override
      public String getRequestedSessionId() {
        return jakartaRequest.getRequestedSessionId();
      }

      @Override
      public StringBuffer getRequestURL() {
        return jakartaRequest.getRequestURL();
      }

      @Override
      public String getServletPath() {
        return jakartaRequest.getServletPath();
      }

      @Override
      public boolean isRequestedSessionIdValid() {
        return jakartaRequest.isRequestedSessionIdValid();
      }

      @Override
      public boolean isRequestedSessionIdFromCookie() {
        return jakartaRequest.isRequestedSessionIdFromCookie();
      }

      @Override
      public boolean isRequestedSessionIdFromURL() {
        return jakartaRequest.isRequestedSessionIdFromURL();
      }

      @Override
      public boolean isRequestedSessionIdFromUrl() {
        return jakartaRequest.isRequestedSessionIdFromURL();
      } // Deprecated

      @Override
      public int getIntHeader(String name) {
        return jakartaRequest.getIntHeader(name);
      }

      @Override
      public long getDateHeader(String name) {
        return jakartaRequest.getDateHeader(name);
      }
    };
  }

  /**
   * Wraps a Jakarta HttpServletResponse to implement Javax HttpServletResponse interface.
   */
  public static javax.servlet.http.HttpServletResponse toJavaxResponse(
    final HttpServletResponse jakartaResponse) {

    return new javax.servlet.http.HttpServletResponse() {
      @Override
      public void addCookie(javax.servlet.http.Cookie cookie) {
        Cookie jakartaCookie = new Cookie(cookie.getName(), cookie.getValue());
        jakartaCookie.setMaxAge(cookie.getMaxAge());
        if (cookie.getPath() != null) {
          jakartaCookie.setPath(cookie.getPath());
        }
        if (cookie.getDomain() != null) {
          jakartaCookie.setDomain(cookie.getDomain());
        }
        jakartaCookie.setSecure(cookie.getSecure());
        // isHttpOnly() and setHttpOnly() not in Servlet API 2.5
        jakartaResponse.addCookie(jakartaCookie);
      }

      @Override
      public void sendError(int sc, String msg) throws java.io.IOException {
        jakartaResponse.sendError(sc, msg);
      }

      @Override
      public void sendError(int sc) throws java.io.IOException {
        jakartaResponse.sendError(sc);
      }

      @Override
      public void sendRedirect(String location) throws java.io.IOException {
        jakartaResponse.sendRedirect(location);
      }

      @Override
      public void setHeader(String name, String value) {
        jakartaResponse.setHeader(name, value);
      }

      @Override
      public void addHeader(String name, String value) {
        jakartaResponse.addHeader(name, value);
      }

      // Stub methods - only Servlet API 2.5 methods
      @Override
      public String getCharacterEncoding() {
        return jakartaResponse.getCharacterEncoding();
      }

      @Override
      public String getContentType() {
        return jakartaResponse.getContentType();
      }

      @Override
      public javax.servlet.ServletOutputStream getOutputStream() throws java.io.IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.io.PrintWriter getWriter() throws java.io.IOException {
        return jakartaResponse.getWriter();
      }

      @Override
      public void setCharacterEncoding(String charset) {
        jakartaResponse.setCharacterEncoding(charset);
      }

      @Override
      public void setContentLength(int len) {
        jakartaResponse.setContentLength(len);
      }

      @Override
      public void setContentType(String type) {
        jakartaResponse.setContentType(type);
      }

      @Override
      public void setBufferSize(int size) {
        jakartaResponse.setBufferSize(size);
      }

      @Override
      public int getBufferSize() {
        return jakartaResponse.getBufferSize();
      }

      @Override
      public void flushBuffer() throws java.io.IOException {
        jakartaResponse.flushBuffer();
      }

      @Override
      public void resetBuffer() {
        jakartaResponse.resetBuffer();
      }

      @Override
      public boolean isCommitted() {
        return jakartaResponse.isCommitted();
      }

      @Override
      public void reset() {
        jakartaResponse.reset();
      }

      @Override
      public void setLocale(java.util.Locale loc) {
        jakartaResponse.setLocale(loc);
      }

      @Override
      public java.util.Locale getLocale() {
        return jakartaResponse.getLocale();
      }

      @Override
      public boolean containsHeader(String name) {
        return jakartaResponse.containsHeader(name);
      }

      @Override
      public String encodeURL(String url) {
        return jakartaResponse.encodeURL(url);
      }

      @Override
      public String encodeRedirectURL(String url) {
        return jakartaResponse.encodeRedirectURL(url);
      }

      @Override
      public String encodeUrl(String url) {
        return jakartaResponse.encodeURL(url);
      } // Deprecated

      @Override
      public String encodeRedirectUrl(String url) {
        return jakartaResponse.encodeRedirectURL(url);
      } // Deprecated

      @Override
      public void setDateHeader(String name, long date) {
        jakartaResponse.setDateHeader(name, date);
      }

      @Override
      public void addDateHeader(String name, long date) {
        jakartaResponse.addDateHeader(name, date);
      }

      @Override
      public void setIntHeader(String name, int value) {
        jakartaResponse.setIntHeader(name, value);
      }

      @Override
      public void addIntHeader(String name, int value) {
        jakartaResponse.addIntHeader(name, value);
      }

      @Override
      public void setStatus(int sc) {
        jakartaResponse.setStatus(sc);
      }

      @Override
      public void setStatus(int sc, String sm) {
        jakartaResponse.setStatus(sc);
      } // Deprecated
    };
  }

  /**
   * Wraps a Jakarta HttpSession to implement Javax HttpSession interface (Servlet API 2.5).
   */
  private static javax.servlet.http.HttpSession toJavaxSession(final HttpSession jakartaSession) {
    return new javax.servlet.http.HttpSession() {
      @Override
      public long getCreationTime() {
        return jakartaSession.getCreationTime();
      }

      @Override
      public String getId() {
        return jakartaSession.getId();
      }

      @Override
      public long getLastAccessedTime() {
        return jakartaSession.getLastAccessedTime();
      }

      @Override
      public javax.servlet.ServletContext getServletContext() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void setMaxInactiveInterval(int interval) {
        jakartaSession.setMaxInactiveInterval(interval);
      }

      @Override
      public int getMaxInactiveInterval() {
        return jakartaSession.getMaxInactiveInterval();
      }

      @Override
      public javax.servlet.http.HttpSessionContext getSessionContext() {
        throw new UnsupportedOperationException();
      } // Deprecated

      @Override
      public Object getAttribute(String name) {
        return jakartaSession.getAttribute(name);
      }

      @Override
      public Object getValue(String name) {
        return jakartaSession.getAttribute(name);
      } // Deprecated

      @Override
      public Enumeration getAttributeNames() {
        return jakartaSession.getAttributeNames();
      }

      @Override
      public String[] getValueNames() {
        return Collections.list(jakartaSession.getAttributeNames()).toArray(new String[0]);
      } // Deprecated

      @Override
      public void setAttribute(String name, Object value) {
        jakartaSession.setAttribute(name, value);
      }

      @Override
      public void putValue(String name, Object value) {
        jakartaSession.setAttribute(name, value);
      } // Deprecated

      @Override
      public void removeAttribute(String name) {
        jakartaSession.removeAttribute(name);
      }

      @Override
      public void removeValue(String name) {
        jakartaSession.removeAttribute(name);
      } // Deprecated

      @Override
      public void invalidate() {
        jakartaSession.invalidate();
      }

      @Override
      public boolean isNew() {
        return jakartaSession.isNew();
      }
    };
  }
}
