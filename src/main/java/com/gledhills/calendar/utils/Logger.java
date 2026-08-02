package com.gledhills.calendar.utils;

import java.text.SimpleDateFormat;
import java.util.*;

import javax.servlet.http.HttpServletRequest;

public class Logger {
  public static final int DEBUG = 0;
  public static final int INFO = 1;
  public static final int WARN = 2;
  public static final int ERROR = 3;

  private static final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");

  private final String className;
  @SuppressWarnings("unused")
  //  private String ip;
  private String agent;
  private String methodType;
  private String resort;
  private String id;
  private HttpServletRequest request; //temporary, just save elements to display
  private final int minLogLevel;

  public Logger(Class<?> clazz, int minLogLevel) {  //todo get rid of this
    this.className = clazz.getSimpleName();
    //    ip = "";
    agent = "";
    this.minLogLevel = minLogLevel;
  }

  //  public Logger(final Class<?> aClass, Logger parentLogger, String resort, int minLogLevel) {
  //    this(aClass,
  //         parentLogger != null ? parentLogger.getRequest() : null,
  //         "",
  //         (resort == null && parentLogger != null) ? parentLogger.getResort() : resort,
  //         minLogLevel);
  //  }

  public Logger(final Class<?> aClass, final HttpServletRequest request, final String methodType, String resort,
                int minLogLevel) {
    this.request = request;
    this.minLogLevel = minLogLevel;
    this.methodType = methodType;
    this.className = aClass != null ? aClass.getSimpleName() : "WIP - ERROR: Class was null in Logger constructor";

    if (aClass == null) {
      error("Class was 'null' in Logger constructor");
      //      Thread.dumpStack();
    }
    id = "UNKNON";
    this.resort = resort;
    if (request != null) {
      String ID = request.getParameter("ID");
      if (ID != null && !ID.isEmpty()) {
        id = ID;
      }
      if (resort == null) {
        this.resort = request.getParameter("resort");
      }
    }
    if (request == null) {
      if (!"DailyReminder".equals(methodType)) {
        error("Null request in Logger, parent NotYetImplemented.");  //todo 1/1/2020
        Thread.dumpStack();  //usually called
        //        at java.lang.Thread.dumpStack(Thread.java:1336)
        //        at com.gledhills.calendar.misc.Logger.<init>(Logger.java:37)
        //        at com.gledhills.calendar.misc.Logger.<init>(Logger.java:27)
        //        at com.gledhills.calendar.misc.ValidateCredentials.<init>(ValidateCredentials.java:26)
        //        at com.gledhills.calendar.resources.OuterWebResource.SubList$InnerSubList.<init>(SubList.java:46)
        //        at com.gledhills.calendar.resources.OuterWebResource.SubList.doGet(SubList.java:27)
      }
      //      ip = "<<null request>>";
      agent = "";
    }
    else {
      //      ip = request.getHeader("x-forwarded-for"); //x-forwarded-for: 216.49.181.51
      agent = request.getHeader("user-agent");//user-agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_1)
      if (agent == null) {
        agent = "";
      }
      //get 'resort' the hard way
      //      String referer = request.getHeader("referer");
      //todo, fails of logout
      //      if (referer != null) {
      //        int startIndex = referer.indexOf("resort=");
      //        if (startIndex >= 0) {
      //          startIndex += 7;
      //          int posNextField = referer.substring(startIndex).indexOf("&");
      //          int endIndex = posNextField == -1 ? referer.length() : posNextField;
      //          if (endIndex > referer.length()) {
      //            endIndex = referer.length();
      //          }
      //          resort = " resort=" + referer.substring(startIndex, endIndex) + " ";
      //        }
      //      }
    }
    String[] split = agent.split(" ");
    if (split.length > 1) {
      agent = split[0];
    }
  }

  public String getResort() {
    return resort == null ? "undefined" : resort;
  }

  /**
   * Name the member these log lines belong to. The constructor can only find them when the
   * request carries an "ID" parameter, which POSTed forms generally do not - they name the
   * field whatever suits the form. Call this once the member is known.
   */
  public void setId(String id) {
    if (id != null && !id.isEmpty()) {
      this.id = id;
    }
  }

  public void logRequestParameters() {
    boolean displayHeader = false;
    StringBuilder uriRequestMsg =
      new StringBuilder("logLevel=" + getLogLevelString() + " class=" + className + " method=" + methodType + " ");
    try {
      String uri = request.getHeader("referer"); //referer: http://52.3.5.75/calendar-1/MemberLogin?resort=Afton
      boolean pathFound = true;
      if (uri != null) {
        //noinspection StringConcatenationInsideStringBufferAppend
        uriRequestMsg.append(" path=\"" + uri + "\" ( --Parameters: ");
      }
      else {
        pathFound = false;
        uriRequestMsg.append(" missingPath ( --Parameters: ");
      }
      @SuppressWarnings("unchecked")
      Enumeration<String> parameterNames = request.getParameterNames();
      while (parameterNames.hasMoreElements()) {
        Set<String> foundParam = new HashSet<String>();
        String paramName = parameterNames.nextElement();
        //noinspection StringConcatenationInsideStringBufferAppend
        uriRequestMsg.append(" " + paramName + "=");
        String[] paramValues = request.getParameterValues(paramName);
        String delimiter = paramValues.length > 1 ? ":" : "";
        for (String paramValue : paramValues) {
          if (!foundParam.contains(paramName)) { //exists in
            String showParamValue = paramValue;
            if ("Password".equals(paramName) || "message".equals(paramName)) {
              showParamValue = "ZZZZ";
            }
            uriRequestMsg.append(showParamValue).append(delimiter);
          }
          foundParam.add(paramValue);
        }
        uriRequestMsg.append(parameterNames.hasMoreElements() ? ", " : " ");
      }
      //noinspection StatementWithEmptyBody
      if (displayHeader) {
        String headers = " --Headers: ";
        @SuppressWarnings("unchecked")
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
          String headerName = headerNames.nextElement();
          headers += (" " + headerName + "=\"" + request.getHeader(headerName) + "\"");
        }
        uriRequestMsg.append(headers);
      }
      uriRequestMsg.append(") ");
      //      System.out.flush();
    }
    catch (Exception e) {
      //noinspection StringConcatenationInsideStringBufferAppend
      uriRequestMsg.append(" exception=\"" + e.getMessage() + "\"");
    }
    writeToLogFile(request, uriRequestMsg.toString());
  }

  private String getLogLevelString() {
    if (minLogLevel == DEBUG) {
      return "DEBUG";
    }
    else if (minLogLevel == INFO) {
      return "INFO";
    }
    else if (minLogLevel == WARN) {
      return "WARN";
    }
    return "ERROR";
  }

  public HttpServletRequest getRequest() {
    return request;
  }

  public void debug(String msg) {
    if (minLogLevel == DEBUG) {
      writeToLogFile(request, "logLevel=DEBUG        " + msg);
    }
  }

  public void info(String msg) {
    if (minLogLevel == INFO || minLogLevel == DEBUG) {
      writeToLogFile(request, "logLevel=INFO    " + msg);
    }
  }

  @SuppressWarnings("unused")
  public void warn(String msg) {
    if (minLogLevel == INFO || minLogLevel == DEBUG || minLogLevel == WARN) {
      writeToLogFile(request, "logLevel=WARN  " + msg);
    }
  }

  public void error(String msg) {
    writeToLogFile(request, "logLevel=ERROR " + msg);
  }

  public void logSqlStatement(String qryString) {
    if (qryString.contains("SELECT")) {
      debug(" sql=\"" + qryString + "\"");
    }
    else {
      info("sql=\"" + qryString + "\"");
    }
  }

  /**
   * Audit entry for a completed database write.  Called by {@link SqlWriter} once the statement has run,
   * so the entry always reflects a write that really happened.  The statement has already been redacted -
   * never pass raw SQL here.
   */
  public void logSqlWrite(String operation, String redactedSql, int rowsAffected) {
    info("sqlWrite=\"" + redactedSql + "\" operation=\"" + operation + "\" rowsAffected=" + rowsAffected);
  }

  /**
   * Audit entry for a database write that threw.  The statement has already been redacted.
   */
  public void logSqlWriteFailure(String operation, String redactedSql, Exception e) {
    error("sqlWrite=\"" + redactedSql + "\" operation=\"" + operation + "\" FAILED, reason=\"" + e.getMessage() + "\"");
  }

  private void writeToLogFile(HttpServletRequest request, String msg) {
    _printCommonHeader(request, resort);
    System.out.println(msg);
  }

  public void logException(String msg, Exception e) {
    _printCommonHeader(request, resort);
    System.out.println(
      msg + "exceptionCause=\"" + e.getCause() + "\", exceptionMessage=\"" + e.getMessage() + "\" " + e);
  }

  public String getClassName() {
    return className;
  }

  private void _printCommonHeader(String ip, String resort) {
    //    String strResort = String.format("%-14s", resort);
    System.out.print("[" + getCurrentDateTimeString() + "] " + " ip=" + ip + ", resort=" + resort + ", id=" + id + " ");
  }

  private String getCurrentDateTimeString() {
    //format: 2018-02-18 20:35:41,712
    // java.util.Date currentTime = new java.util.Date(year,month,date);
    Calendar gregorianCalendar = new GregorianCalendar(TimeZone.getDefault());
    return formatter.format(gregorianCalendar.getTime());
  }

  private void _printCommonHeader(HttpServletRequest request, String resort) {
    String fromIp = "undef";
    if (request != null) {
      try {
        fromIp = request.getHeader("x-forwarded-for"); //x-forwarded-for: 216.49.181.51
        if (fromIp != null && fromIp.contains(",")) {
          //header may hold a chain "client, proxy1, proxy2" - the first entry is the real client
          fromIp = fromIp.substring(0, fromIp.indexOf(',')).trim();
        }
        if (fromIp == null || fromIp.isEmpty()) {
          //no forwarding header; RemoteIpValve (forward-headers-strategy=native) has already
          //resolved the client address into getRemoteAddr()
          fromIp = request.getRemoteAddr();
        }
        if (fromIp == null || fromIp.isEmpty()) {
          fromIp = "undef";
        }
      } catch (IllegalStateException e) {
        fromIp = "async";
      }
    }
    _printCommonHeader(fromIp, resort);
  }
}
