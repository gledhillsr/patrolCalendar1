package com.gledhills.calendar.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the site's own domain in one place.
 *
 * The from address, the admin address, the backdoor account and the footer's bug-report link were
 * four separate copies of the same string, which is how a rename leaves half a site pointing at the
 * old domain. This scans the source for anyone writing it out by hand again.
 *
 * Two things are deliberately allowed to say it in full:
 *   - SiteIdentity itself, which is where the one copy lives
 *   - the resortMap entries, where it stands in as the website of a resort that has none of its own
 */
class SiteIdentityTest {

  private static final List<Path> SOURCE_ROOTS =
    List.of(Path.of("src/main/java"), Path.of("src/main/resources/templates"));

  /** Both names the site answers on. Either one written out by hand is the bug this catches. */
  private static final List<String> DOMAINS =
    List.of(SiteIdentity.DOMAIN, SiteIdentity.PATROL_CALENDAR_DOMAIN);

  @Test
  void everyAddressIsBuiltFromTheOneDomain() {
    assertTrue(SiteIdentity.SUPPORT_EMAIL.endsWith("@" + SiteIdentity.DOMAIN),
               "support address should be on the site's domain: " + SiteIdentity.SUPPORT_EMAIL);
    assertTrue(SiteIdentity.FROM_EMAIL.endsWith("@" + SiteIdentity.DOMAIN),
               "from address should be on the site's domain: " + SiteIdentity.FROM_EMAIL);
    assertTrue(SiteIdentity.ADMIN_EMAIL.endsWith("@" + SiteIdentity.DOMAIN),
               "admin address should be on the site's domain: " + SiteIdentity.ADMIN_EMAIL);
  }

  @Test
  void nothingElseSpellsTheDomainOut() throws IOException {
    List<String> offenders = new ArrayList<>();

    for (Path root : SOURCE_ROOTS) {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          if (file.getFileName().toString().equals("SiteIdentity.java")) {
            continue;
          }
          List<String> lines = Files.readAllLines(file);
          for (int number = 0; number < lines.size(); number++) {
            String line = lines.get(number);
            if (DOMAINS.stream().noneMatch(
                  domain -> line.toLowerCase().contains(domain.toLowerCase()))) {
              continue;
            }
            String trimmed = line.trim();
            //a comment is documentation, and a resortMap entry is that resort's own website
            if (trimmed.startsWith("//") || trimmed.startsWith("*")
                  || trimmed.contains("resortMap.put")) {
              continue;
            }
            offenders.add(file + ":" + (number + 1) + "  " + trimmed);
          }
        }
      }
    }

    assertEquals(List.of(), offenders,
                 "these should use SiteIdentity instead of writing the domain out:\n"
                   + String.join("\n", offenders));
  }

  /**
   * The host match behind the header's greeting, tested without a request in flight.
   *
   * The lookalike case is the one worth having: an endsWith on the bare domain would greet
   * notpatrolcalendar.com as if it were us.
   */
  @Test
  void onlyTheSecondDomainAndItsSubdomainsMatch() {
    assertTrue(SiteIdentity.isPatrolCalendarHost("patrolcalendar.com"));
    assertTrue(SiteIdentity.isPatrolCalendarHost("www.patrolcalendar.com"));
    assertTrue(SiteIdentity.isPatrolCalendarHost("PatrolCalendar.COM"), "a host is case-insensitive");

    assertFalse(SiteIdentity.isPatrolCalendarHost(SiteIdentity.DOMAIN));
    assertFalse(SiteIdentity.isPatrolCalendarHost("notpatrolcalendar.com"), "a lookalike is not us");
    assertFalse(SiteIdentity.isPatrolCalendarHost("patrolcalendar.com.example.net"));
    assertFalse(SiteIdentity.isPatrolCalendarHost("localhost"));
    assertFalse(SiteIdentity.isPatrolCalendarHost(null), "no host at all draws no banner");
  }

  /** With no request in flight - DailyReminder, or any unit test - the answer is a quiet false. */
  @Test
  void noRequestInFlightIsNotTheSecondDomain() {
    assertFalse(new SiteIdentity().isPatrolCalendarHost());
  }
}
