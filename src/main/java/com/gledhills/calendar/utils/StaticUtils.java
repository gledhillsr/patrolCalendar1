package com.gledhills.calendar.utils;


import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Steve Gledhill
 */
public final class StaticUtils {

  @SuppressWarnings("RegExpRedundantEscape")
  private static final String EMAIL_PATTERN =
    "^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@"
      + "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";

  public static final String[] szMonthsFull = {
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
  };
  public static final String[] szDays =
    { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "error" };


  public static boolean isEmpty(String str) {
    return str == null || str.isEmpty();
  }

  public static boolean isNotEmpty(String str) {
    return str != null && !str.isEmpty();
  }

  public static boolean isValidEmailAddress(String emailAddress) {
    Pattern pattern = Pattern.compile(EMAIL_PATTERN);
    Matcher matcher = pattern.matcher(emailAddress);
    return matcher.matches();
  }


  //lowercase substrings found in crawler/scraper/scanner user agents but never in real browsers
  private static final String[] BOT_SIGNATURES = {
    "bot", "spider", "crawl", "slurp",                          //search engines (googlebot, bingbot, baiduspider, yahoo slurp, ...)
    "curl", "wget", "python", "scrapy", "go-http-client",       //scripting/CLI tools
    "libwww", "httpclient", "java/",                            //programmatic http libraries
    "headless", "phantomjs",                                    //headless browsers
    "facebookexternalhit", "bingpreview", "ia_archiver",        //link preview / archive fetchers
    "censys", "shodan", "zgrab", "masscan", "nikto", "sqlmap", "nuclei" //security scanners
  };

  public static boolean isRequestFromBot(String userAgent) {
    if (StaticUtils.isEmpty(userAgent)) {
      return true;  //every real browser sends a User-Agent; missing one means a script
    }
    String lowerUserAgent = userAgent.toLowerCase();
    for (String signature : BOT_SIGNATURES) {
      if (lowerUserAgent.contains(signature)) {
        return true;
      }
    }
    return false;
  }

  public static void buildAndLogErrorResponse(HttpServletResponse response, int status, String errString, Logger LOG) {
    LOG.error("Response Error: " + status + ": " + errString);
    response.setStatus(status);
    response.addHeader("X-Reason", errString);
  }

  public static int convertToInt(String szNumber) {
    if (isEmpty(szNumber)) {
      return 0;
    }
    try {
      return Integer.parseInt(szNumber);
    }
    catch (NumberFormatException e) {
      System.out.println("NumberFormatException on [" + szNumber + "]");
      return 0;
    }
  }

  public static String sqlStrip(String asciiString) {
    if (asciiString.matches(".*[<>'\"].*")) {
      return asciiString.replaceAll("'", "").replaceAll("\"", "").replaceAll("<", "").replaceAll(">", "");
    }
    return asciiString;
  }

  public static String sqlEncode(String asciiString) {
    if (asciiString.matches(".*[<>'\"].*")) {
      return asciiString.replaceAll("'", "0x27").replaceAll("\"", "0x22").replaceAll("<", "0x3C")
        .replaceAll(">", "0x3E");
    }
    return asciiString;
  }

  public static String sqlDecode(String encodedString) {
    if (encodedString.contains("0x")) {
      return encodedString.replaceAll("0x27", "'").replaceAll("0x22", "\"").replaceAll("0x3C", "<")
        .replaceAll("0x3E", ">");
    }
    return encodedString;
  }
}
