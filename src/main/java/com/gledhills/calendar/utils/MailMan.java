package com.gledhills.calendar.utils;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

import java.util.Collections;

/**
 * @author Steve Gledhill
 */

@SuppressWarnings("CommentedOutCode")
public class MailMan {

  @SuppressWarnings("unused")
  private static final boolean DEBUG = true;
  private static final boolean DEBUG_DONT_SEND = false;
  @SuppressWarnings("WeakerAccess")
  static final String CONFIGSET = "nspMail";

  private SesClient sesClient = null;
  private String fromAddress;
  private String replyToAddress = null;
  private final Logger LOG;

  public MailMan(String fromAddress, SessionData sessionData, Logger LOG) {
    this.LOG = LOG;
    if (DEBUG_DONT_SEND) {
      debugOutDontSend("NOTHING WILL BE SENT BECAUSE OF DEBUG SETTING!");
      return;
    }
    if (StaticUtils.isValidEmailAddress(fromAddress)) {
      this.replyToAddress = fromAddress;
      LOG.debug("DEBUG - MailMan setting replyToAddress to: " + fromAddress);
    }
    else {
      this.replyToAddress = null;
      if (StaticUtils.isNotEmpty(fromAddress)) {
        LOG.debug("DEBUG - replyToAddress invalid email address: [" + fromAddress + "]");
      }
    }
    try {
      String resort = sessionData.getLoggedInResort();
      ResortData resortInfo = PatrolData.getResortInfo(
        resort); //will return null if invalid resort (like in dailyReminder because no HttpSession)
      if (resortInfo != null && StaticUtils.isNotEmpty(resortInfo.getDirectorsVerifiedEmail())) {
        //todo NOTE: this is where we load the resort/directors email address
        this.fromAddress = resortInfo.getDirectorsVerifiedEmail();
      }
      else {
        this.fromAddress = "steve@gledhills.com";
      }
    }
    catch (NoClassDefFoundError e) {
      this.fromAddress = "steve@gledhills.com";
    }

    sesClient = SesClient.builder().region(Region.US_EAST_1).build();
    //    logger();
  }

  public void sendMessage(String subject, String messageBody, String toAddress) {
    //    logger();
    if (DEBUG_DONT_SEND) {
      debugOutDontSend("nothing sent because of debug");
      return;
    }
    sendAmazonEmail(fromAddress, replyToAddress, toAddress, subject, messageBody);
  }

  private void sendAmazonEmail(String from, String replyToAddress, String toAddress, String subject, String textBody) {
    try {
      Destination destination = Destination.builder()
        .toAddresses(toAddress)
        .build();

      Content subjectContent = Content.builder()
        .data(subject)
        .build();

      Content textContent = Content.builder()
        .data(textBody)
        .build();

      Body body = Body.builder()
        .text(textContent)
        .build();

      Message message = Message.builder()
        .subject(subjectContent)
        .body(body)
        .build();

      SendEmailRequest.Builder requestBuilder = SendEmailRequest.builder()
        .destination(destination)
        .message(message)
        .source(from)
        .configurationSetName(CONFIGSET);

      if (replyToAddress != null) {
        requestBuilder.replyToAddresses(Collections.singletonList(replyToAddress));
      }

      Long startMillis = System.nanoTime() / 1000000L;
      SendEmailResponse result = sesClient.sendEmail(requestBuilder.build());
      Long endMillis = System.nanoTime() / 1000000L;
      LOG.info("Email sent from=" + from + ", to: " + destination + ", mailTime=" + (endMillis - startMillis) +
                 " milliSeconds.  result=" + result.toString());
    }
    catch (Exception ex) {
      LOG.error("The email was not sent.  Exception message: " + ex.getMessage());
    }
  }

  //  private static void logger(Object... msg) {
  //    System.out.println("MailMan: zzz");
  //    for (Object item : msg) {
  //      System.out.print(item); //keep this here
  //    }
  //    System.out.println("");
  //  }
  //
  private void debugOutDontSend(String msg) {
    if (DEBUG_DONT_SEND) {
      //      Logger.printToLogFileStatic(sessionData.getRequest(), sessionData.getLoggedInResort(), "DEBUG_DONT_SEND-Mailman: " + msg);
      LOG.info("DEBUG_DONT_SEND-Mailman: " + msg);
    }
  }

  //  public static void main(String[] args) throws MailManException {
  //    if (args.length < 5) {
  //      Log.log("Invalid arguments");
  //      Log.log("java com.gledhills.MailMan <host> <from address> <subject> <message> <recipient address>");
  //      return;
  //    }
  //    String smtpHost = args[0];      //zimbra.xmission.com
  //    String fromAddress = args[1];   //nancy@gledhills.com
  //    String fromName = "Nancy Gledhill";
  //    String subject = args[2];       //"subject text"
  //    String message = args[3];       //"body text\n line 2"
  //    String toAddress = args[4];     //gledhillsr@familysearch.org
  //    MailMan mailMan = new MailMan(smtpHost, fromAddress, fromName);
  //    mailMan.sendMessage(subject, message, toAddress);
  //    mailMan.close();
  //  }
}

