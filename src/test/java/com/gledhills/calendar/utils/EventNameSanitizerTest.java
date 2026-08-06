package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests - no Spring context, no database. */
class EventNameSanitizerTest {

  @Test
  void keepsTheFormattingTagsTheEditorAdvertises() {
    assertEquals("<b>Powder day</b>", EventNameSanitizer.sanitize("<b>Powder day</b>"));
    assertEquals("Race<br>Practice", EventNameSanitizer.sanitize("Race<br>Practice"));
    assertEquals("Race<br>Practice", EventNameSanitizer.sanitize("Race<br/>Practice"));
    assertEquals("Race<br>Practice", EventNameSanitizer.sanitize("Race<BR />Practice"));
    assertEquals("<i>Night</i> <u>ops</u>", EventNameSanitizer.sanitize("<i>Night</i> <u>ops</u>"));
  }

  @Test
  void lowerCasesTagNamesButLeavesTextAlone() {
    assertEquals("<b>SKI PATROL</b>", EventNameSanitizer.sanitize("<B>SKI PATROL</B>"));
  }

  @Test
  void escapesScriptTags() {
    String sanitized = EventNameSanitizer.sanitize("<script>alert('xss')</script>");

    assertFalse(sanitized.contains("<script"), "a script tag must not survive: " + sanitized);
    assertTrue(sanitized.contains("&lt;script&gt;"), "it should show as literal text: " + sanitized);
  }

  @Test
  void escapesEventHandlerAttributesOnOtherwiseAllowedTags() {
    String sanitized = EventNameSanitizer.sanitize("<b onmouseover='alert(1)'>hover</b>");

    assertFalse(sanitized.contains("onmouseover='alert(1)'"),
                "an attribute must never survive: " + sanitized);
    assertTrue(sanitized.contains("&lt;b onmouseover="), "the open tag should be escaped whole");
    assertTrue(sanitized.contains("</b>"), "the bare closing tag is still allowed");
  }

  @Test
  void escapesImageAndIframePayloads() {
    assertFalse(EventNameSanitizer.sanitize("<img src=x onerror=alert(1)>").contains("<img"));
    assertFalse(EventNameSanitizer.sanitize("<iframe src='//evil'></iframe>").contains("<iframe"));
  }

  @Test
  void keepsOrdinaryLinks() {
    assertEquals("<a href=\"https://example.com/rules\" target=\"_blank\" "
                   + "rel=\"noopener noreferrer\">Rules</a>",
                 EventNameSanitizer.sanitize("<a href=\"https://example.com/rules\">Rules</a>"));
  }

  @Test
  void acceptsTheUrlFormsDirectorsActuallyType() {
    //single quoted, unquoted, uppercase tag, http, mailto and a link to another page on this site
    assertTrue(EventNameSanitizer.sanitize("<a href='http://example.com'>x</a>").contains("href=\"http://example.com\""));
    assertTrue(EventNameSanitizer.sanitize("<a href=http://example.com>x</a>").contains("href=\"http://example.com\""));
    assertTrue(EventNameSanitizer.sanitize("<A HREF=\"https://example.com\">x</A>").contains("href=\"https://example.com\""));
    assertTrue(EventNameSanitizer.sanitize("<a href=\"mailto:patrol@example.com\">mail</a>").contains("href=\"mailto:"));
    assertTrue(EventNameSanitizer.sanitize("<a href=\"/calendar-1/MonthCalendar?resort=Sample\">cal</a>")
                 .contains("href=\"/calendar-1/MonthCalendar?resort=Sample\""));
  }

  /** A real comment a director wrote, with an unquoted href. */
  @Test
  void keepsARealWorldMeetingLink() {
    String sanitized = EventNameSanitizer.sanitize(
      "Patrol Meeting 7PM - <a href=https://youtu.be/u2KdzOcvk24>Watch Patrol Meeting</a>");

    assertEquals("Patrol Meeting 7PM - <a href=\"https://youtu.be/u2KdzOcvk24\" target=\"_blank\" "
                   + "rel=\"noopener noreferrer\">Watch Patrol Meeting</a>", sanitized);
  }

  @Test
  void keepsQueryStringsIntactAndEscapedForAnAttribute() {
    String sanitized = EventNameSanitizer.sanitize(
      "<a href=\"https://example.com/p?a=1&b=2\">two params</a>");

    assertTrue(sanitized.contains("href=\"https://example.com/p?a=1&amp;b=2\""),
               "the ampersand must be escaped for attribute context: " + sanitized);
    assertTrue(sanitized.contains(">two params</a>"));
  }

  @Test
  void refusesScriptSchemes() {
    for (String url : List.of("javascript:alert(1)", "JaVaScRiPt:alert(1)",
                              "data:text/html;base64,PHNjcmlwdD4=", "vbscript:msgbox(1)",
                              "  javascript:alert(1)")) {
      String sanitized = EventNameSanitizer.sanitize("<a href=\"" + url + "\">click</a>");
      assertFalse(sanitized.contains("<a href"),
                  "must not become a link: " + url + " -> " + sanitized);
      assertTrue(sanitized.contains("&lt;a href"), "it should show as text: " + sanitized);
    }
  }

  /** The scheme has to be there literally; the browser would decode entities in an href. */
  @Test
  void refusesAnEntityObfuscatedScheme() {
    String sanitized = EventNameSanitizer.sanitize("<a href=\"java&#115;cript:alert(1)\">click</a>");

    assertFalse(sanitized.contains("<a href"), "must not become a link: " + sanitized);
  }

  @Test
  void refusesAProtocolRelativeJumpToAnotherHost() {
    assertFalse(EventNameSanitizer.sanitize("<a href='//evil.example'>click</a>").contains("<a href"));
  }

  @Test
  void refusesAnAnchorCarryingAnythingBesidesHref() {
    for (String tag : List.of("<a href=\"https://ok.example\" onclick=\"alert(1)\">x</a>",
                              "<a onmouseover=\"alert(1)\" href=\"https://ok.example\">x</a>",
                              "<a href=\"https://ok.example\" style=\"position:fixed;inset:0\">x</a>",
                              "<a name=\"anchor\">x</a>")) {
      String sanitized = EventNameSanitizer.sanitize(tag);
      assertFalse(sanitized.contains("<a href"), "must not be partially trusted: " + sanitized);
      assertFalse(sanitized.contains("onclick=\"alert(1)\"") && sanitized.contains("<a"),
                  "an event handler must never reach the page live: " + sanitized);
    }
  }

  @Test
  void cannotForgeThePlaceholderMarkers() {
    //the markers used internally are control characters; stored text containing them must not
    //be able to conjure a link that was never validated
    String sanitized = EventNameSanitizer.sanitize("\u00010\u0002 <a href=\"https://ok.example\">x</a>");

    assertEquals(1, sanitized.split("<a href", -1).length - 1,
                 "exactly one real link should survive: " + sanitized);
  }

  @Test
  void closingAnchorTagsSurviveOnTheirOwn() {
    assertEquals("plain</a>", EventNameSanitizer.sanitize("plain</a>"));
  }

  @Test
  void escapesQuotesSoTheValueCannotBreakOutOfAnAttribute() {
    String sanitized = EventNameSanitizer.sanitize("\" onload=\"alert(1)");

    assertFalse(sanitized.contains("\""), "double quotes must be escaped: " + sanitized);
    assertTrue(sanitized.contains("&quot;"));
  }

  @Test
  void keepsTheEmptyPlaceholderReadable() {
    assertEquals("&nbsp;", EventNameSanitizer.sanitize("&nbsp;"));
    assertEquals("", EventNameSanitizer.sanitize(null));
    assertEquals("", EventNameSanitizer.sanitize(""));
  }

  @Test
  void escapesLoneAmpersandsWithoutDoubleEscaping() {
    assertEquals("Ski &amp; Ride", EventNameSanitizer.sanitize("Ski & Ride"));
    assertEquals("&amp;lt;", EventNameSanitizer.sanitize("&lt;"),
                 "text that was already escaped stays visible rather than becoming a tag");
  }

  @Test
  void plainTextStripsEverything() {
    assertEquals("Powder day", EventNameSanitizer.toPlainText("<b>Powder day</b>"));
    assertEquals("Race Practice", EventNameSanitizer.toPlainText("Race&nbsp;Practice"));
    assertEquals("alert(1)", EventNameSanitizer.toPlainText("<script>alert(1)</script>"));
    assertEquals("", EventNameSanitizer.toPlainText(null));
  }
}
