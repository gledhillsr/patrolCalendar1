package com.gledhills.calendar.utils;

import org.apache.commons.lang3.StringUtils;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;

/**
 * @author Steve Gledhill
 */
public class OuterPage {
  private static final boolean DEBUG = false;

  private final String javaScript;
  private final String loggedInUserId;
  private final ResortData resortData;
  private static final int MAX_BUFFER = 4000;
  private final Logger LOG;

  public OuterPage(ResortData resort, String javaScriptAndStyles, String loggedInUserId, Logger LOG) {
    resortData = resort;
    this.LOG = LOG;
    if (resortData == null) {
      LOG.error("MAJOR ERROR, resortData is null.  This should not happen here");
      Thread.dumpStack();
    }
    this.javaScript = javaScriptAndStyles;
    this.loggedInUserId = loggedInUserId;
  }

  public void printResortHeader(PrintWriter out) {
    String header;
    if ("Brighton".equals(resortData.getResortShortName())) {
      header = readFile("brightonHeader1.html");
    }
    else {
      header = readFile("resortHeader1.html");
    }
    String userIdTag = StringUtils.isBlank(loggedInUserId) ? "" : "&ID=" + loggedInUserId;
    String str1 = header.
      replaceAll("__RESORT_SHORT", resortData.getResortShortName()).
      replaceAll("__RESORT_LONG", resortData.getResortFullName()).
      replaceAll("__USER_ID_TAG", userIdTag).
      replaceAll("__RESORT_URL", resortData.getResortURL()).
      replaceAll("__RESORT_IMG_HEIGHT", "" + resortData.getImageHeight()).
      replaceAll("__RESORT_IMAGE", resortData.getResortImage()).
      replaceAll("__JAVA_SCRIPT", javaScript);
    if (!StringUtils.isBlank(loggedInUserId)) {
      String logout = "<a class='nav-btn nav-btn-logout' href='/calendar-1/MemberLogout?resort="
        + resortData.getResortShortName() + "' target='_self'>Logout</a>";
      str1 = str1.replace("__LOGOUT", logout);
    }
    else {
      str1 = str1.replace("__LOGOUT", "");
    }
    debugOut("processed header=" + str1);
    out.println(str1);
  }

  public void printResortFooter(PrintWriter out) {
    String footer = readFile("resortFooter1.html");
    debugOut("processed footer=" + footer);
    out.println(footer);
  }

  private String readFile(String resourceFile) {
    ClassLoader classLoader = getClass().getClassLoader();
    URL fileResource = classLoader.getResource(resourceFile);
    debugOut("readFile: fileResource=" + fileResource);
    if (fileResource == null) {
      errorOut("ERROR: OuterPage.readFile(" + resourceFile + ") could not find resource!");
      return "";
    }
    String fileName = fileResource.getFile();
    //noinspection StringBufferMayBeStringBuilder
    StringBuffer result = new StringBuffer();
    byte[] buffer = new byte[MAX_BUFFER];
    try {
      FileInputStream inputStream = new FileInputStream(fileName);
      while (inputStream.read(buffer) != -1) {
        result.append(new String(buffer));
      }

      // Always close files.
      inputStream.close();
    }
    catch (FileNotFoundException ex) {
      errorOut("Error, unable to open file '" + fileName + "'");
    }
    catch (IOException ex) {
      errorOut("Error reading file '" + fileName + "'");
    }
    debugOut("readFile: bytes read=" + result.length());
    return result.toString();
  }

  private void errorOut(String msg) {
    LOG.error("ERROR: OuterPage: " + msg);
  }

  private void debugOut(String msg) {
    if (DEBUG) {
      LOG.debug("DEBUG: OuterPage: " + msg);
    }
  }
}
