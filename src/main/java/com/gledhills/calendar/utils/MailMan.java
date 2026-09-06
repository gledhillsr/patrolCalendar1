package com.gledhills.calendar.utils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.SesClientBuilder;
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
        this.fromAddress = SiteIdentity.FROM_EMAIL;
      }
    }
    catch (NoClassDefFoundError e) {
      this.fromAddress = SiteIdentity.FROM_EMAIL;
    }

    // Prefer the AWS keys from credentials.properties (loaded by SessionData);
    // fall back to the SDK default provider chain (env vars, IAM instance role, ...)
    SesClientBuilder builder = SesClient.builder().region(Region.US_EAST_1);
    if (StaticUtils.isNotEmpty(sessionData.getAWSAccessKeyId()) &&
      StaticUtils.isNotEmpty(sessionData.getAWSSecretKey())) {
      builder.credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(sessionData.getAWSAccessKeyId(), sessionData.getAWSSecretKey())));
    }
    sesClient = builder.build();
    //    logger();
  }

  /** @return true if the email was sent, false if sending failed (already logged) */
  public boolean sendMessage(String subject, String messageBody, String toAddress) {
    //    logger();
    if (DEBUG_DONT_SEND) {
      debugOutDontSend("nothing sent because of debug");
      return true;
    }
    return sendAmazonEmail(fromAddress, replyToAddress, toAddress, subject, messageBody);
  }

  private boolean sendAmazonEmail(String from, String replyToAddress, String toAddress, String subject, String textBody) {
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
      return true;
    }
    catch (Exception ex) {
      LOG.error("The email was not sent.  Exception message: " + ex.getMessage());
      return false;
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
}

