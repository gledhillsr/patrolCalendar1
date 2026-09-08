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
 * The resorts whose members are invented, and which therefore must never be emailed.
 *
 * <p>{@code Sample} is the demo anyone may experiment in; {@code SampleBackup} is the pristine
 * copy it is restored from at 2am, listed in {@code resortMap} only so the site-wide schema jobs
 * keep it in step - a restore copies the master's table structure as well as its rows, so a
 * migration that skipped it would be undone overnight.
 *
 * <p>This exists because adding that second demo resort was a trap: four separate places compared
 * against the literal {@code "Sample"}, and two of them would otherwise have started mailing
 * made-up addresses every night.
 */
class DemoResortTest {

  @Test
  void bothDemoResortsAreRecognised() {
    assertTrue(PatrolData.isDemoResort("Sample"));
    assertTrue(PatrolData.isDemoResort("SampleBackup"));
  }

  @Test
  void aRealResortIsNotADemo() {
    assertFalse(PatrolData.isDemoResort("Brighton"));
    assertFalse(PatrolData.isDemoResort("Afton"));
    assertFalse(PatrolData.isDemoResort(null));
    assertFalse(PatrolData.isDemoResort(""));
  }

  /** Both are real entries in the map, or the schema jobs would never reach them. */
  @Test
  void bothAreInTheResortMap() {
    assertTrue(PatrolData.isValidResort("Sample"));
    assertTrue(PatrolData.isValidResort("SampleBackup"),
               "SampleBackup has to be in resortMap or the site-wide schema jobs skip it, and the"
                 + " nightly restore puts an out-of-date schema back over Sample");
  }

  /**
   * Nothing outside PatrolData may test for a demo resort by writing the name out.
   *
   * <p>That is precisely what went wrong: DailyReminder, EmailFormController and
   * SiteDirectorsService each held their own {@code "Sample"} comparison, so a second demo resort
   * was invisible to all three. The name belongs in one place.
   */
  @Test
  void nothingElseComparesAgainstTheDemoResortByName() throws IOException {
    List<String> offenders = new ArrayList<>();

    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        if (file.getFileName().toString().equals("PatrolData.java")) {
          continue;   //where the one copy lives
        }
        List<String> lines = Files.readAllLines(file);
        for (int number = 0; number < lines.size(); number++) {
          String line = lines.get(number).trim();
          if (line.startsWith("//") || line.startsWith("*")) {
            continue;
          }
          //a behavioural comparison, not the word appearing in a message or a model attribute
          boolean comparesByName =
            (line.contains("\"Sample\"") || line.contains("\"SampleBackup\""))
              && (line.contains("equals") || line.contains("equalsIgnoreCase"));
          if (comparesByName) {
            offenders.add(file.getFileName() + ":" + (number + 1) + "  " + line);
          }
        }
      }
    }

    assertEquals(List.of(), offenders,
                 "these should ask PatrolData.isDemoResort instead - including the cosmetic ones,"
                   + " so a demo resort behaves the same way everywhere:\n"
                   + String.join("\n", offenders));
  }

  /**
   * Every limitation Sample has, SampleBackup has too - checked against the running application
   * rather than by reading the source, since the point is the behaviour a visitor meets.
   */
  @org.springframework.boot.test.context.SpringBootTest
  @org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.test.context.junit.jupiter.SpringExtension.class)
  static class BothDemoResortsBehaveTheSame {

    private static final String BROWSER =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.web.context.WebApplicationContext context;

    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
      mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                  .webAppContextSetup(context).build();
    }

    /** The demo login hint is shown on both, so SampleBackup is usable in the same way. */
    @Test
    void theLoginPageOffersDemoCredentialsOnBoth() throws Exception {
      assertTrue(loginPage("Sample").contains("demo"), "Sample should show the demo hint");
      assertTrue(loginPage("SampleBackup").contains("demo"),
                 "SampleBackup should show it too - same invented members, same logins");
    }

    /** Neither may have a password changed: on Sample it would be undone at 2am anyway. */
    @Test
    void passwordChangesAreRefusedOnBoth() throws Exception {
      for (String resort : new String[] {"Sample", "SampleBackup"}) {
        String answer = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
              .post("/LoginHelp").param("resort", resort).param("emailme", "1")
              .param("ID", "123456").header("User-Agent", BROWSER))
          .andReturn().getResponse().getContentAsString();

        assertTrue(answer.contains("Password changes are not allowed"),
                   resort + " should refuse a password change");
      }
    }

    private String loginPage(String resort) throws Exception {
      return mockMvc.perform(
          org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/MemberLogin").param("resort", resort).header("User-Agent", BROWSER))
        .andReturn().getResponse().getContentAsString();
    }
  }
}
