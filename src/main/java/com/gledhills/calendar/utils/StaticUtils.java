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

  /**
   * A stored phone number as an SMS provider wants it - {@code +15551234567} - or null.
   *
   * <p>The roster's CellPhone column is free text twenty years deep: {@code 847-372-5936},
   * {@code (847) 372-5936}, {@code 8473725936}, {@code &nbsp;}, {@code none}, an extension, a note.
   * Ten digits after the punctuation is the only shape that can be dialled, and a leading 1 is the
   * country code rather than an eleventh digit. <b>Anything else is null rather than a guess</b>,
   * the same rule NspRosterImport applies on the way in: a half-recognised number cannot be told
   * from a real one later, and here it would mean a message going somewhere nobody chose.
   *
   * <p><b>This says nothing about whether the number is a mobile.</b> Nothing in the roster does -
   * a landline typed into the Cell field looks exactly like this and only the provider will
   * object. That is a reason to surface failures, not to guess here.
   */
  public static String toSmsNumber(String phone) {
    if (phone == null) {
      return null;
    }
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() == 11 && digits.startsWith("1")) {
      digits = digits.substring(1);
    }
    return digits.length() == 10 ? "+1" + digits : null;
  }

  /** Whether this member could be sent a text at all. */
  public static boolean isTextable(String phone) {
    return toSmsNumber(phone) != null;
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
