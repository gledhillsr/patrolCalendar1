package org.nsponline.calendar.controller;

import org.nsponline.calendar.store.*;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.*;

@Controller
public class ProcessChangeShiftAssignmentsController {

  private static final boolean PAUSE_ON_THIS_SCREEN = false; // set true to debug without redirect

  private static final int ERROR = 0;
  private static final int INSERT = 1;
  private static final int REPLACE = 2;
  private static final int REMOVE = 3;
  private static final int MISSED_SHIFT = 5;
  private static final int NEEDS_REPLACEMENT = 6;
  private static final int NO_REPLACEMENT_NEEDED = 7;

  private static final String[] TRANS_NAMES = {
    "Error", "Insert Patroller", "Replace Patroller", "Remove Patroller",
    "Trade Days with Patroller", "Missed Shift", "Needs a Replacement", "No Replacement Needed"
  };

  @PostMapping("/ProcessChangeShiftAssignments")
  public String processChangeShiftAssignments(HttpServletRequest request,
                                              HttpServletResponse response,
                                              Model model) throws IOException {
    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

    String resort = request.getParameter("resort");
    Logger LOG = new Logger(ProcessChangeShiftAssignmentsController.class, javaxRequest, "POST", resort, Logger.INFO);

    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Resort not found: " + resort);
      return null;
    }

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    ValidateCredentialsRedirectIfNeeded credentials =
      new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                             "ProcessChangeShiftAssignmentsController", patrolData, LOG);
    if (credentials.hasInvalidCredentials()) {
      patrolData.close();
      return null;
    }

    String szMyID = sessionData.getLoggedInUserId();
    String submitterID = request.getParameter("submitterID");
    String transaction = request.getParameter("transaction");
    String selectedID = request.getParameter("selectedID");
    String szdate1 = request.getParameter("date1");
    String pos1Str = request.getParameter("pos1");
    String index1Str = request.getParameter("index1");
    String listName = request.getParameter("listName");

    int nPos1 = parseIntParam(pos1Str, 1);
    int nIndex1AsNum = PatrolData.StringToIndex(index1Str);

    // Parse date components from szdate1 (format: yyyy-mm-dd_X)
    int date1 = Integer.parseInt(szdate1.substring(8, 10));
    int month1 = Integer.parseInt(szdate1.substring(5, 7));
    int year1 = Integer.parseInt(szdate1.substring(0, 4));

    Calendar calendarToday = new GregorianCalendar(TimeZone.getDefault());
    calendarToday.set(year1, month1 - 1, date1);
    int dayOfWeek0based = calendarToday.get(Calendar.DAY_OF_WEEK) - 1;

    // Read or create the assignment record
    Assignments night1 = patrolData.readAssignment(szdate1);
    if (night1 == null) {
      String today = szdate1.substring(0, szdate1.indexOf("_") + 1);
      int cnt = 1;
      for (ShiftDefinitions shift : patrolData.readShiftDefinitions()) {
        if (shift.parsedEventName().equals(StaticUtils.szDays[dayOfWeek0based])) {
          Assignments assign = new Assignments(today + cnt, shift, LOG);
          patrolData.writeAssignment(assign);
          if (cnt == nPos1) {
            night1 = assign;
            night1.setExisted();
          }
          cnt++;
        }
      }
    }

    Roster submitter = patrolData.getMemberByID(submitterID);
    if (submitter == null || night1 == null) {
      LOG.error("submitter or assignment not found: submitterID=" + submitterID + " szdate1=" + szdate1);
      patrolData.close();
      return redirectToCalendar(resort, month1, year1, szMyID);
    }

    String szSubmitterName = submitter.getFullName();
    LOG.info("submitter=" + szSubmitterName + " selectedID=" + selectedID + " trans=" + transaction
               + " date1=" + szdate1 + " pos1=" + pos1Str + " index1=" + index1Str);

    // Process the transaction
    boolean dupError = false;
    boolean err = false;
    int transNumber = ERROR;
    String newID = null;
    String newName = null;
    Roster member1 = null;
    Roster member2 = null;
    HashMap<String, NewIndividualAssignment> monthNewAssignments = new HashMap<>();

    if ("insertMyName".equals(transaction)) {
      transNumber = INSERT;
      newID = submitterID;
      newName = szSubmitterName;
      if (night1.getPosIndex(newID) != -1) {
        dupError = true;
      } else {
        night1.insertAt(resort, nIndex1AsNum, newID);
      }

    } else if ("ReplaceWithMyName".equals(transaction)) {
      transNumber = REPLACE;
      newID = submitterID;
      newName = szSubmitterName;
      if (night1.getPosIndex(newID) != -1) {
        dupError = true;
      } else {
        night1.insertAt(resort, nIndex1AsNum, newID);
      }

    } else if ("replaceWithSomeoneElse".equals(transaction)) {
      member1 = patrolData.getMemberByName2(listName);
      if (member1 == null) {
        LOG.error("replaceWithSomeoneElse: member not found: " + listName);
        patrolData.close();
        return redirectToCalendar(resort, month1, year1, szMyID);
      }
      transNumber = REPLACE;
      newID = member1.getID();
      newName = listName;
      String oldID = night1.getPosID(nIndex1AsNum);
      if (!"0".equals(oldID)) {
        member2 = patrolData.getMemberByID(oldID);
      }
      if (night1.getPosIndex(newID) != -1) {
        dupError = true;
      } else {
        night1.insertAt(resort, nIndex1AsNum, newID);
      }

    } else if ("removeName".equals(transaction)) {
      member1 = patrolData.getMemberByID(selectedID);
      if (member1 == null) {
        LOG.error("removeName: member not found: " + selectedID);
        patrolData.close();
        return redirectToCalendar(resort, month1, year1, szMyID);
      }
      transNumber = REMOVE;
      newID = selectedID;
      newName = member1.getFullName_lastNameFirst();
      night1.remove(resort, nIndex1AsNum);

    } else if ("missedShift".equals(transaction)) {
      transNumber = MISSED_SHIFT;
      String cleanID = night1.getPosID(nIndex1AsNum);
      boolean alreadyMissed = !cleanID.isEmpty() && cleanID.charAt(0) == '-';
      if (alreadyMissed) {
        newID = selectedID;
        cleanID = newID;
      } else {
        cleanID = selectedID;
        newID = "-" + selectedID;
      }
      member1 = patrolData.getMemberByID(cleanID);
      if (member1 == null) {
        LOG.error("missedShift: member not found: " + cleanID);
        patrolData.close();
        return redirectToCalendar(resort, month1, year1, szMyID);
      }
      newName = member1.getFullName_lastNameFirst();
      night1.insertAt(resort, nIndex1AsNum, newID);

    } else if (transaction != null && transaction.startsWith("trade_")) {
      LOG.error("Trade transaction is not supported");
      patrolData.close();
      return redirectToCalendar(resort, month1, year1, szMyID);

    } else if ("needsReplacement".equals(transaction)) {
      transNumber = NEEDS_REPLACEMENT;
      newID = selectedID;
      member1 = patrolData.getMemberByID(selectedID);
      if (member1 != null) newName = member1.getFullName();
      monthNewAssignments = patrolData.readNewIndividualAssignments(year1, month1, date1);

    } else if ("noReplacementNeeded".equals(transaction)) {
      transNumber = NO_REPLACEMENT_NEEDED;
      newID = selectedID;
      member1 = patrolData.getMemberByID(selectedID);
      if (member1 != null) newName = member1.getFullName();
      monthNewAssignments = patrolData.readNewIndividualAssignments(year1, month1, date1);
    }

    // Persist the changes
    if (!dupError) {
      if (transNumber == NO_REPLACEMENT_NEEDED) {
        err = persistNoReplacementNeeded(szdate1, index1Str, monthNewAssignments, patrolData, LOG);
      } else if (transNumber == NEEDS_REPLACEMENT) {
        err = persistNeedsReplacement(szdate1, index1Str, calendarToday, nPos1, nIndex1AsNum,
                                      newID, submitterID, monthNewAssignments, patrolData, LOG);
      } else {
        err = patrolData.writeAssignment(night1);
      }
    }

    // Send email notifications on success
    if (!dupError && !err && newName != null) {
      String emailMessage = buildEmailMessage(transNumber, newName, dayOfWeek0based, month1, date1, year1,
                                             night1, szSubmitterName, sessionData, resort);
      DirectorSettings ds = patrolData.readDirectorSettings();
      sendMailNotifications(ds, sessionData, patrolData, submitterID, member1, member2, emailMessage, resort, LOG);
    }

    patrolData.close();

    if (PAUSE_ON_THIS_SCREEN) {
      model.addAttribute("resort", resort);
      model.addAttribute("transactionName", transNumber < TRANS_NAMES.length ? TRANS_NAMES[transNumber] : "Unknown");
      model.addAttribute("newName", newName);
      model.addAttribute("submitterName", szSubmitterName);
      model.addAttribute("submissionTime", new Date().toString());
      model.addAttribute("dupError", dupError);
      model.addAttribute("err", err);
      model.addAttribute("calendarUrl", "/calendar-1/MonthCalendar?resort=" + resort
          + "&month=" + (month1 - 1) + "&year=" + year1 + "&ID=" + szMyID);
      return "process-change-shift-assignments";
    }
    return redirectToCalendar(resort, month1, year1, szMyID);
  }

  private boolean persistNoReplacementNeeded(String szdate1, String index1Str,
                                             HashMap<String, NewIndividualAssignment> monthNewAssignments,
                                             PatrolData patrolData, Logger LOG) {
    String key = szdate1 + "_" + index1Str;
    NewIndividualAssignment nia = monthNewAssignments.get(key);
    if (nia == null) {
      LOG.error("noReplacementNeeded: assignment not found for key=" + key);
      return false;
    }
    patrolData.deleteNewIndividualAssignment(nia);
    return false;
  }

  private boolean persistNeedsReplacement(String szdate1, String index1Str, Calendar calendarToday,
                                          int nPos1, int nIndex1AsNum, String newID, String submitterID,
                                          HashMap<String, NewIndividualAssignment> monthNewAssignments,
                                          PatrolData patrolData, Logger LOG) {
    String key = szdate1 + "_" + index1Str;
    NewIndividualAssignment nia = monthNewAssignments.get(key);
    if (nia == null) {
      LOG.error("needsReplacement: creating new record (shiftType forced to DAY_SHIFT)");
      nia = new NewIndividualAssignment(calendarToday.getTime(), nPos1, nIndex1AsNum,
                                       NewIndividualAssignment.DAY_TYPE,
                                       NewIndividualAssignment.FLAG_BIT_NEEDS_REPLACEMENT,
                                       newID, submitterID);
      patrolData.insertNewIndividualAssignment(nia);
    } else {
      nia.setNeedsReplacement(true);
      patrolData.updateNewIndividualAssignment(nia);
    }
    return false;
  }

  private String buildEmailMessage(int transNumber, String newName, int dayOfWeek0based,
                                   int month1, int date1, int year1, Assignments night1,
                                   String szSubmitterName, SessionData sessionData, String resort) {
    StringBuilder msg = new StringBuilder("Schedule Changed:\n\n");
    msg.append(TRANS_NAMES[transNumber]).append(" ").append(newName)
       .append(" On ").append(StaticUtils.szDays[dayOfWeek0based])
       .append(", ").append(StaticUtils.szMonthsFull[month1 - 1])
       .append("/").append(date1).append("/").append(year1);
    if (night1 != null) {
      msg.append(" (").append(night1.getStartingTimeString())
         .append(" - ").append(night1.getEndingTimeString()).append(")");
    }
    msg.append("\n  ");
    msg.append("\nSubmission done by: ").append(szSubmitterName)
       .append(" (").append(sessionData.getEmailUser()).append(")\n");
    msg.append("At Ski Resort: ").append(resort).append("\n");
    msg.append("\nPlease do NOT reply to this automated reminder.\n");
    return msg.toString();
  }

  private void sendMailNotifications(DirectorSettings ds, SessionData sessionData,
                                     PatrolData patrolData, String submitterID,
                                     Roster member1, Roster member2,
                                     String emailMessage, String resort, Logger LOG) {
    if (!ds.getNotifyChanges()) {
      LOG.debug("no mail sent: notify changes is false");
      return;
    }
    if (submitterID.equals(sessionData.getBackDoorUser())) {
      LOG.debug("no mail: back door user");
      return;
    }
    if (resort.equalsIgnoreCase("Sample")) {
      LOG.debug("no mail: Sample resort");
      return;
    }

    MailMan mail = new MailMan(sessionData.getEmailUser(), sessionData, LOG);
    boolean sentToFirst = false;
    boolean sentToSecond = false;

    // Email all directors with yesEmail flag
    ResultSet rosterResults = patrolData.resetRoster();
    Roster mbr;
    while ((mbr = patrolData.nextMember("", rosterResults)) != null) {
      if (mbr.isDirector() && "yesEmail".equalsIgnoreCase(mbr.getDirector())) {
        if (sendMailTo(mail, mbr, emailMessage, sessionData, member1, member2)) {
          if (member1 != null && member1.getID().equals(mbr.getID())) sentToFirst = true;
          if (member2 != null && member2.getID().equals(mbr.getID())) sentToSecond = true;
        }
      }
    }

    // Email affected patrollers if configured
    if (ds.getNotifyChanges()) {
      if (!sentToFirst && member1 != null) {
        sendMailTo(mail, member1, emailMessage, sessionData, member1, member2);
      }
      if (!sentToSecond && member2 != null) {
        sendMailTo(mail, member2, emailMessage, sessionData, member1, member2);
      }
    }
  }

  private boolean sendMailTo(MailMan mail, Roster mbr, String emailMessage, SessionData sessionData,
                             Roster member1, Roster member2) {
    if (mbr == null) return false;
    String recipient = mbr.getEmailAddress();
    if (recipient != null && recipient.length() > 3 && recipient.indexOf('@') > 0) {
      mail.sendMessage("Patrol Roster Changed (" + sessionData.getLoggedInResort() + ")",
                       emailMessage, recipient);
      return true;
    }
    return false;
  }

  private String redirectToCalendar(String resort, int month1, int year1, String szMyID) {
    return "redirect:/MonthCalendar?resort=" + resort + "&month=" + (month1 - 1) + "&year=" + year1 + "&ID=" + szMyID;
  }

  private int parseIntParam(String param, int defaultValue) {
    try {
      return (param != null) ? Integer.parseInt(param) : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
