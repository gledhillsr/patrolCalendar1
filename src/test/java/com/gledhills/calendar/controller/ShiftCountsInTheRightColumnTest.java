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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A member's day shifts must be counted in the Day Shifts column.
 *
 * <p>They were not. The ListPatrollers template indexed the shift-count array with literal
 * numbers, rotated by one - Day read slot 1, Swing read slot 2, Night read slot 0 - so every day
 * shift landed under <b>Night Shifts</b>. Most shifts are day shifts, so the symptom was a Night
 * column holding everything and a Day column of zeroes.
 *
 * <p>Checked against the spreadsheet rather than against hard-coded numbers, because that is the
 * bug's own shape: {@code RosterQueryService} fills the array by {@code Assignments}' shift-type
 * constants and {@code RosterExcelExporter} reads it by them, so the download was right the whole
 * time and only the page disagreed. CLAUDE.md already promises the .xlsx holds exactly what the
 * page displayed; this is that promise, asserted.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class ShiftCountsInTheRightColumnTest {

  private static final String DIRECTOR_ID = "123456";
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
  void thePageCountsShiftsIntoTheSameColumnsTheDownloadDoes() throws Exception {
    Map<String, List<String>> onThePage = countsFromPage();
    Map<String, List<String>> inTheWorkbook = countsFromWorkbook();

    assertEquals(inTheWorkbook.keySet(), onThePage.keySet(),
                 "page and download should list the same members");

    for (String name : inTheWorkbook.keySet()) {
      assertEquals(inTheWorkbook.get(name), onThePage.get(name),
                   "day/swing/night counts for " + name + " should match the spreadsheet");
    }
  }

  /**
   * Without this the comparison above passes on a roster where every count is zero, which is
   * exactly the state a rotated index is invisible in.
   */
  @Test
  void someMemberActuallyHasAShiftToCount() throws Exception {
    boolean anyNonZero = countsFromWorkbook().values().stream()
      .flatMap(List::stream)
      .anyMatch(count -> !count.isEmpty() && !count.equals("0"));

    assertTrue(anyNonZero,
               "the Sample roster must hold at least one assignment, or this test proves nothing");
  }

  /** Name, Day, Swing, Night - the only columns asked for, so the row is four cells wide. */
  private Map<String, List<String>> countsFromPage() throws Exception {
    String page = mockMvc.perform(request("/ListPatrollers"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    //the header carries <th>, a member row carries only <td>, which is what separates them
    Map<String, List<String>> counts = new LinkedHashMap<>();
    Matcher rows = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(page);
    while (rows.find()) {
      String row = rows.group(1);
      if (row.contains("<th")) {
        continue;
      }
      List<String> cells = new ArrayList<>();
      Matcher cell = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL).matcher(row);
      while (cell.find()) {
        cells.add(cell.group(1).trim());
      }
      if (cells.size() == 4) {
        counts.put(cells.get(0), cells.subList(1, 4));
      }
    }
    assertTrue(!counts.isEmpty(), "the page should have listed some members: " + page);
    return counts;
  }

  private Map<String, List<String>> countsFromWorkbook() throws Exception {
    byte[] xlsx = mockMvc.perform(request("/DownloadRoster"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

    Map<String, List<String>> counts = new LinkedHashMap<>();
    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
      Sheet sheet = workbook.getSheetAt(0);

      //located by heading rather than by position: the exporter decides the column order, and
      //this test is about which column a count lands in, not about where the block sits
      //row 0 is the workbook's title, row 1 the headings, so member rows start at 2
      Row headings = sheet.getRow(1);
      int day = columnOf(headings, "Day Shifts");
      int swing = columnOf(headings, "Swing Shifts");
      int night = columnOf(headings, "Night Shifts");

      for (int r = 2; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null || row.getCell(0) == null) {
          continue;
        }
        counts.put(row.getCell(0).getStringCellValue().trim(),
                   List.of(text(row, day), text(row, swing), text(row, night)));
      }
    }
    return counts;
  }

  private int columnOf(Row headings, String heading) {
    for (int c = 0; c < headings.getLastCellNum(); c++) {
      if (headings.getCell(c) != null
            && heading.equals(headings.getCell(c).getStringCellValue().trim())) {
        return c;
      }
    }
    throw new AssertionError("the download has no \"" + heading + "\" column");
  }

  private String text(Row row, int column) {
    if (row.getCell(column) == null) {
      return "";
    }
    return switch (row.getCell(column).getCellType()) {
      case NUMERIC -> String.valueOf((long) row.getCell(column).getNumericCellValue());
      case STRING -> row.getCell(column).getStringCellValue().trim();
      default -> "";
    };
  }

  /** Name plus the three shift counters, and nothing else, so the row shape is predictable. */
  private MockHttpServletRequestBuilder request(String path) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    MockHttpServletRequestBuilder builder = get(path)
      .param("resort", "Sample").param("ID", DIRECTOR_ID).param("NAME", "LAST");

    for (String classification :
      List.of("BAS", "INA", "SR", "SRA", "ALM", "PRO", "AUX", "TRA", "CAN", "NSK", "OTH")) {
      builder = builder.param(classification, "1");
    }
    builder = builder.param("FullTime", "1").param("PartTime", "1").param("Inactive", "1")
      .param("ALL", "1")
      .param("DAY_CNT", "1").param("SWING_CNT", "1").param("NIGHT_CNT", "1");

    return builder.header("User-Agent", BROWSER).session(session);
  }
}
