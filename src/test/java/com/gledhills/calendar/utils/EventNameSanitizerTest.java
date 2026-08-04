package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

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
    assertFalse(EventNameSanitizer.sanitize("<a href='//evil'>click</a>").contains("<a href"));
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
