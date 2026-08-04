package com.gledhills.calendar.controller;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for DownloadRosterController - the .xlsx roster download reached from the
 * "Download this table" button on ListPatrollers.
 *
 * Note: like the other controller tests these run against the live Sample database, and assume
 * 123456 (Steve) is a director and 200401 is not.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class DownloadRosterControllerTest {

  private static final String DIRECTOR_ID = "123456";
  private static final String MEMBER_ID = "200401";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Test
  void testDownloadRequiresLogin() throws Exception {
    mockMvc.perform(get("/DownloadRoster")
                      .param("resort", "Sample")
                      .header("User-Agent", BROWSER))
      .andExpect(status().is3xxRedirection());
  }

  @Test
  void testNonDirectorIsRefused() throws Exception {
    mockMvc.perform(rosterRequest(MEMBER_ID))
      .andExpect(status().isForbidden());
  }

  @Test
  void testDirectorDownloadsRealXlsx() throws Exception {
    MvcResult result = mockMvc.perform(rosterRequest(DIRECTOR_ID))
      .andExpect(status().isOk())
      .andReturn();

    byte[] body = result.getResponse().getContentAsByteArray();

    assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                 result.getResponse().getContentType(),
                 "must be served as a modern xlsx workbook, not vnd.ms-excel");
    assertTrue(result.getResponse().getHeader("Content-Disposition").contains(".xlsx"),
               "download must be named .xlsx");
    assertTrue(body.length > 0 && body[0] == 'P' && body[1] == 'K',
               "xlsx files are zip archives and start with PK");

    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
      Sheet sheet = workbook.getSheetAt(0);
      List<String> headings = new ArrayList<>();
      for (org.apache.poi.ss.usermodel.Cell cell : sheet.getRow(1)) {
        headings.add(cell.getStringCellValue());
      }

      // The requested columns, in the order list-patrollers.html renders them
      assertEquals(List.of("Name", "Class", "ID", "Home", "Email",
                           "Commitment", "Instructor", "Day Shifts"), headings);

      // Row 0 is the title, row 1 the headings, so member rows start at 2
      int memberRows = sheet.getLastRowNum() - 1;
      assertEquals(patrollersListedOnPage(), memberRows,
                   "spreadsheet must hold the same members the page listed");
      assertTrue(memberRows > 0, "Sample resort should list at least one member");

      assertLabelsNotRawNumbers(sheet, headings.indexOf("Commitment"), headings.indexOf("Instructor"));
    }
  }

  /**
   * Commitment and Instructor are stored as numbers ("2", bit flags), and must be written as
   * the labels the edit dropdowns use ("Full Time", "OEC, Ski") rather than those numbers.
   */
  private void assertLabelsNotRawNumbers(Sheet sheet, int commitmentColumn, int instructorColumn) {
    List<String> commitmentLabels = List.of("Inactive", "Part Time", "Full Time", "");
    List<String> certifications = List.of("OEC", "CPR", "Ski", "Toboggan");

    for (int rowNumber = 2; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
      Row row = sheet.getRow(rowNumber);

      String commitment = row.getCell(commitmentColumn).getStringCellValue();
      assertTrue(commitmentLabels.contains(commitment),
                 "commitment should be a dropdown label, got: " + commitment);

      String instructor = row.getCell(instructorColumn).getStringCellValue();
      for (String part : instructor.split(", ")) {
        assertTrue(part.isEmpty() || certifications.contains(part),
                   "instructor should be a certification list, got: " + instructor);
      }
    }
  }

  /** Same request the download button builds: the page's filters plus the chosen columns. */
  private MockHttpServletRequestBuilder rosterRequest(String userId) {
    return listingRequest("/DownloadRoster", userId);
  }

  private MockHttpServletRequestBuilder listingRequest(String path, String userId) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", userId);
    session.setAttribute("loggedInResort", "Sample");

    MockHttpServletRequestBuilder builder = get(path)
      .param("resort", "Sample")
      .param("ID", userId);

    // Filters: every classification, every commitment level, everyone listed.
    // okToDisplay() drops a member unless their classification and commitment are both asked for.
    for (String classification :
      List.of("BAS", "INA", "SR", "SRA", "ALM", "PRO", "AUX", "TRA", "CAN", "OTH")) {
      builder = builder.param(classification, "1");
    }
    builder = builder.param("FullTime", "1").param("PartTime", "1").param("Inactive", "1")
      .param("ALL", "1");

    // Columns to show
    builder = builder.param("CLASS", "1").param("SHOW_ID", "1")
      .param("HOME", "1").param("EMAIL", "1")
      .param("COMMIT", "1").param("INSTRUCTOR", "1").param("DAY_CNT", "1");

    return builder.header("User-Agent", BROWSER).session(session);
  }

  /**
   * The ListPatrollers page shows the same labels the download does, not the stored numbers.
   * Sample resort has a member with every certification and one with none.
   */
  @Test
  void testPageShowsCommitmentAndInstructorLabels() throws Exception {
    String page = listPatrollersPage();

    assertTrue(page.contains(">Full Time</td>"), "commitment should render as its dropdown label");
    assertTrue(page.contains(">OEC, CPR, Ski, Toboggan</td>"),
               "instructor should render as its certification list");
    assertTrue(page.contains(">Ski, Toboggan</td>"),
               "instructor bit flags should decode to just the certifications held");
  }

  /** "Total Patrollers Listed=N" off the ListPatrollers page, for the same filters. */
  private int patrollersListedOnPage() throws Exception {
    String page = listPatrollersPage();

    Matcher matcher = Pattern.compile("Total Patrollers Listed=(\\d+)").matcher(page);
    assertTrue(matcher.find(), "ListPatrollers should report how many members it listed");
    return Integer.parseInt(matcher.group(1));
  }

  private String listPatrollersPage() throws Exception {
    return mockMvc.perform(listingRequest("/ListPatrollers", DIRECTOR_ID))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }
}
