package com.gledhills.calendar.utils;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Look at QueryParameters 'ID', and 'resort'
 * if ID is empty, goto login in page!
 * if resort is empty, goto login in page!
 * if resort is different that logged in resort, goto login in page!
 *
 * @author Steve Gledhill
 */
public class ValidateCredentialsRedirectIfNeeded {

  public final static String NSP_TOKEN_NAME = "nspToken";
  private final static int DAYS_IN_SECONDS = 60 * 60 * 24;
  private final static int COOKIE_MAX_AGE_IN_SECONDS = DAYS_IN_SECONDS * 14;

  @SuppressWarnings("FieldCanBeLocal")
  private final String resort;
  private final String token;  //new todo implement for long lived credentials
  private final Logger LOG;

  private boolean hasInvalidCredentials;

  @SuppressWarnings("UnusedParameters")
  public ValidateCredentialsRedirectIfNeeded(SessionData sessionData, HttpServletRequest request,
                                             HttpServletResponse response, String parent, PatrolData patrolData,
                                             final Logger parentLogger) {
    this.resort = request.getParameter("resort");
    this.token = request.getParameter("token");
    //    LOG = new Logger(this.getClass(), parentLogger, resortParameter, LOG_LEVEL);
    LOG = parentLogger;
    //  public Logger(final Class<?> aClass, final HttpServletRequest request, final String methodType, String resort, int minLogLevel) //todo 1/1/2020 srg, consider using something like
    String idParameter = request.getParameter("ID"); //NOT REQUIRED (keep it that way)
    String idLoggedIn = sessionData.getLoggedInUserId();
    initAndRedirectIfNeeded(sessionData, request, response, parent, patrolData, idParameter, idLoggedIn);
  }

  //  public ValidateCredentials(SessionData sessionData, String resort, String id) {
  //    this.resortParameter = resort;
  //    init(sessionData, null, null, id, null);
  //  }

  private void initAndRedirectIfNeeded(SessionData sessionData, HttpServletRequest request,
                                       HttpServletResponse response, String parent, PatrolData patrolData,
                                       String idParameter, String idLoggedIn) {
    init(sessionData, parent, idParameter);
    attemptLoginViaCookie(sessionData, request, response, patrolData, idLoggedIn);

    if (hasInvalidCredentials) {
      //      if (token != null && token.isEmpty()) {
      //        LOG.error("token is empty, loggedInUserId is NOT" + parent);
      //        //todo do fix this (1/1/2021)
      //      }
      try {
        sessionData.setLoggedInResort(null);
        sessionData.setLoggedInUserId(null);
        debugOut("RESETTING logged in resort/userId to null");
        if (StaticUtils.isNotEmpty(parent)) {
          String newLoc = PatrolData.SERVLET_URL + "MemberLogin?resort=" + resort + "&NSPgoto=" + parent;
          debugOut("calling sendRedirect(" + newLoc + ")");
          response.sendRedirect(newLoc);
        }
      }
      catch (Exception e) {
        errorOut(e.getMessage());
      }
    }
    else {
      debugOut("validCredentials.  id=" + idLoggedIn + ", parent=" + parent + ", resort=" + resort);
    }
  }

  private void attemptLoginViaCookie(SessionData sessionData, HttpServletRequest request, HttpServletResponse response,
                                     PatrolData patrolData, String idLoggedIn) {
    String cookieValue = null;
    String cookieResort = null;
    String cookiePatrollerId = null;
    Cookie[] cookies = request.getCookies();
    if (cookies != null) { //no cookies exist
      for (Cookie cookie : request.getCookies()) {
        if (NSP_TOKEN_NAME.equals(cookie.getName())) {
          cookieValue = cookie.getValue();
          try {
            // Decode URL-encoded cookie value
            String decodedValue = URLDecoder.decode(cookieValue, StandardCharsets.UTF_8.name());
            //the signature proves this server issued the cookie; without this check anyone could
            //type a member id into their browser and be logged in as that member
            AuthCookie.Verified verified = AuthCookie.verify(sessionData, LOG, decodedValue);
            if (verified != null && PatrolData.isValidResort(verified.resort()) &&
              patrolData.getMemberByID(verified.memberId()) != null) {
              cookieResort = verified.resort();
              cookiePatrollerId = verified.memberId();
            }
            LOG.debug("cookie FOUND, accepted=" + (cookiePatrollerId != null));
          } catch (Exception e) {
            LOG.warn("Failed to decode cookie value: " + e.getMessage());
          }
        }
      }

      if (!hasInvalidCredentials) {
        //user had good credentials, so just update the cookie TTL
        writeLoginCookie(sessionData, request, response, resort, idLoggedIn);
      }
      else if (cookiePatrollerId != null) {
        //not logged in, but good cookie, so use VALID cookie to login
        sessionData.setLoggedInResort(cookieResort);
        sessionData.setLoggedInUserId(cookiePatrollerId);
        hasInvalidCredentials = false;
        writeLoginCookie(sessionData, request, response, cookieResort, cookiePatrollerId);
        LOG.warn("using cookie for login to resort=" + cookieResort + ", as user=" + cookiePatrollerId);
      }
      else if (cookieValue != null) { //signature, expiry, resort or patrollerId did not check out
        //not logged in, but bad cookie, so delete cookie
        try {
          response.addHeader("Set-Cookie", buildSetCookieHeader(request.isSecure(), "", 0));
          //never echo the rejected value back into the log
          LOG.warn("deleting cookie because it was unsigned, expired or named an unknown member.  resort=" + resort);
        }
        catch (Exception e) {
          LOG.error("failed to delete cookie, message=" + e.getMessage());
        }
      }
      //else no credentials, and no 'nspToken' cookie
    }
    //else no cookies exist
  }

  /**
   * Issue (or refresh) the signed login cookie.  Silently issues nothing when no signing key is
   * configured - an unsigned cookie would be worse than no cookie at all.
   */
  private void writeLoginCookie(SessionData sessionData, HttpServletRequest request, HttpServletResponse response,
                                String cookieResort, String patrollerId) {
    try {
      if (StaticUtils.isEmpty(cookieResort) || StaticUtils.isEmpty(patrollerId)) {
        return;
      }
      String signedValue = AuthCookie.create(sessionData, LOG, cookieResort, patrollerId, COOKIE_MAX_AGE_IN_SECONDS);
      if (signedValue == null) {
        return; //already logged
      }
      String encodedValue = URLEncoder.encode(signedValue, StandardCharsets.UTF_8.name());
      response.addHeader("Set-Cookie", buildSetCookieHeader(request.isSecure(), encodedValue, COOKIE_MAX_AGE_IN_SECONDS));
      LOG.debug("update/add cookie TTL for resort=" + cookieResort + ", user=" + patrollerId);
    }
    catch (Exception e) {
      LOG.error("failed to add cookie, message=" + e.getMessage());
    }
  }

  /**
   * Build the Set-Cookie header by hand.  The javax.servlet 2.5 Cookie class this legacy code compiles
   * against predates HttpOnly and SameSite, and ServletAdapter cannot carry flags it cannot express -
   * so the header is written directly to keep the protective attributes.
   * <p>
   * HttpOnly keeps the cookie away from JavaScript, SameSite=Lax stops it riding along on cross site
   * requests, and Secure is set whenever the request itself arrived over HTTPS.
   */
  public static String buildSetCookieHeader(boolean isSecureRequest, String encodedValue, int maxAgeInSeconds) {
    StringBuilder header = new StringBuilder();
    header.append(NSP_TOKEN_NAME).append('=').append(encodedValue);
    header.append("; Max-Age=").append(maxAgeInSeconds);
    header.append("; Path=/");
    header.append("; HttpOnly");
    header.append("; SameSite=Lax");
    if (isSecureRequest) {
      header.append("; Secure");
    }
    return header.toString();
  }

  private void init(SessionData sessionData, String parent, String idParameter) {
    debugOut("parameters  idParameter=" + idParameter + ", resort=" + resort + ", NSPgoto=" + parent);
    //todo this is a HACK that bypasses the login.  remove it 3/4/21
    //    if (StaticUtils.isEmpty(sessionData.getLoggedInUserId()) && doParametersRepresentValidLogin(resort, idParameter, sessionData, LOG)) {
    //      sessionData.setLoggedInUserId(idParameter);
    //      sessionData.setLoggedInResort(resort);
    //    }

    hasInvalidCredentials =
      sessionData.isLoggedIntoAnotherResort(resort) || StaticUtils.isEmpty(sessionData.getLoggedInUserId());
  }

  //  private boolean doParametersRepresentValidLogin(String resortParameter, String idParameter, SessionData sessionData, Logger parentLog) {
  //    if (isNullOrEmpty(resortParameter) ||
  //        isNullOrEmpty(idParameter) ||
  //        !PatrolData.isValidResort(resortParameter)) {
  //      return false;
  //    }
  //    PatrolData patrol = new PatrolData(PatrolData.FETCH_ALL_DATA, resortParameter, sessionData, parentLog); //todo srg, 2/27/21 pass in, usually already available
  //    boolean validId = patrol.getMemberByID(idParameter) != null;
  //    //todo 1/1/2020, put try/catch around getMemberByID, and dump all parameters on error
  //    //seen this error when idParameter was not a number
  //    patrol.close();
  //    debugOut("cheated login, validId (" + idParameter + ") = " + validId);
  //    return validId;
  //  }

  private void errorOut(String msg) {
    // NOSONAR
    LOG.error("ERROR-ValidateCredentials(" + resort + "): " + msg);
  }

  private void debugOut(String msg) {
    // NOSONAR
    LOG.debug("DEBUG-ValidateCredentials(" + resort + "): " + msg);
  }

  public boolean hasInvalidCredentials() {
    return hasInvalidCredentials;
  }
}