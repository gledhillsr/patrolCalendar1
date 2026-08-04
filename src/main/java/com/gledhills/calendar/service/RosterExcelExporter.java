package com.gledhills.calendar.service;

import com.gledhills.calendar.dto.RosterSelection;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.Roster;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Writes a roster listing to a real .xlsx workbook (Office Open XML), replacing the legacy
 * download that sent tab separated text under an application/vnd.ms-excel content type.
 *
 * The sheet holds the same columns, rows and order the ListPatrollers page displayed, so
 * "Download this table" gives the director the table they are looking at.
 */
@Service
public class RosterExcelExporter {

  /** The empty spacer columns the print view offers ("blank column to write in"). */
  private static final String BLANK_HEADING = "";

  /** One spreadsheet column: its heading and how to pull the value out of a member. */
  private record Column(String heading, Function<Roster, Object> value) {
  }

  /**
   * Build the workbook bytes for a roster listing.
   *
   * @param selection members to write plus the column choices they were listed with
   * @param resortFullName resort name, used for the sheet title row
   * @return the .xlsx file content
   */
  public byte[] toXlsx(RosterSelection selection, String resortFullName) throws IOException {
    List<Column> columns = buildColumns(selection.displayFlags(), selection.firstNameFirst());

    try (Workbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {

      Sheet sheet = workbook.createSheet("Roster");
      CellStyle titleStyle = boldStyle(workbook, 14);
      CellStyle headerStyle = boldStyle(workbook, 11);

      // Title row, then the headings, then one row per member
      Row titleRow = sheet.createRow(0);
      Cell titleCell = titleRow.createCell(0);
      titleCell.setCellValue(resortFullName + " Ski Patrol roster");
      titleCell.setCellStyle(titleStyle);
      if (columns.size() > 1) {
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, columns.size() - 1));
      }

      Row headerRow = sheet.createRow(1);
      for (int column = 0; column < columns.size(); column++) {
        Cell cell = headerRow.createCell(column);
        cell.setCellValue(columns.get(column).heading());
        cell.setCellStyle(headerStyle);
      }

      int rowNumber = 2;
      for (Roster member : selection.members()) {
        Row row = sheet.createRow(rowNumber++);
        for (int column = 0; column < columns.size(); column++) {
          Object value = columns.get(column).value().apply(member);
          Cell cell = row.createCell(column);
          if (value instanceof Integer number) {
            cell.setCellValue(number);
          }
          else {
            cell.setCellValue(value == null ? "" : value.toString());
          }
        }
      }

      // Headings stay visible while scrolling, and the columns are wide enough to read
      sheet.createFreezePane(0, 2);
      for (int column = 0; column < columns.size(); column++) {
        sheet.autoSizeColumn(column);
      }
      if (!selection.members().isEmpty()) {
        sheet.setAutoFilter(new CellRangeAddress(1, rowNumber - 1, 0, columns.size() - 1));
      }

      workbook.write(bytes);
      return bytes.toByteArray();
    }
  }

  /**
   * The columns list-patrollers.html renders, in the same order. Keep the two in step:
   * a column added to the page belongs here too.
   */
  private List<Column> buildColumns(Map<String, Boolean> flags, boolean firstNameFirst) {
    List<Column> columns = new ArrayList<>();

    columns.add(new Column("Name", member ->
      firstNameFirst ? member.getFullName() : member.getFullName_lastNameFirst()));

    addIf(columns, flags, "showBlank", new Column(BLANK_HEADING, member -> ""));
    addIf(columns, flags, "showBlankWide", new Column(BLANK_HEADING, member -> ""));
    addIf(columns, flags, "showClass", new Column("Class", Roster::getClassification));
    addIf(columns, flags, "showID", new Column("ID", Roster::getID));
    addIf(columns, flags, "showSpouse", new Column("Spouse", member -> clean(member.getSpouse())));
    addIf(columns, flags, "showAddr", new Column("Address", member -> clean(member.getAddress())));
    addIf(columns, flags, "showCity", new Column("City", member -> clean(member.getCity())));
    addIf(columns, flags, "showState", new Column("State", member -> clean(member.getState())));
    addIf(columns, flags, "showZip", new Column("Zip", member -> clean(member.getZipCode())));
    addIf(columns, flags, "showHome", new Column("Home", member -> clean(member.getHomePhone())));
    addIf(columns, flags, "showWork", new Column("Work", member -> clean(member.getWorkPhone())));
    addIf(columns, flags, "showCell", new Column("Cell", member -> clean(member.getCellPhone())));
    addIf(columns, flags, "showPager", new Column("Pager", member -> clean(member.getPager())));
    addIf(columns, flags, "showEmail", new Column("Email", member -> clean(member.getEmailAddress())));
    addIf(columns, flags, "showEmergency",
          new Column("Call Up", member -> clean(member.getEmergency())));
    addIf(columns, flags, "showSubstitute",
          new Column("Sub", member -> clean(member.getSub())));
    // Both are stored as numbers; the spreadsheet shows the labels the edit dropdowns use
    addIf(columns, flags, "showCommit", new Column("Commitment", Roster::getCommitmentLabel));
    addIf(columns, flags, "showInstructor", new Column("Instructor", Roster::getInstructorLabel));
    addIf(columns, flags, "showDirector", new Column("Director", member -> yesNo(member.isDirector())));
    addIf(columns, flags, "showLastUpdated",
          new Column("Last Updated", member -> clean(member.getLastUpdated())));
    addIf(columns, flags, "showComments",
          new Column("Private Comments", member -> clean(member.getComments())));
    addIf(columns, flags, "showCanEarnCredits",
          new Column("Can Earn Credits", member -> yesNo(member.canEarnCredits())));
    addIf(columns, flags, "showCreditsEarned",
          new Column("Credits Available", member -> clean(member.getCreditsEarned())));
    addIf(columns, flags, "showCreditDate",
          new Column("Last Credit Update", member -> clean(member.getLastCreditDateStr())));
    addIf(columns, flags, "showTeamLead", new Column("Team Lead", member -> yesNo(member.isTeamLead())));
    addIf(columns, flags, "showMentoring", new Column("Mentoring", member -> yesNo(member.isMentoring())));

    addShiftColumns(columns, flags, "showDayCnt", "showDayList", "Day", Assignments.DAY_TYPE);
    addShiftColumns(columns, flags, "showSwingCnt", "showSwingList", "Swing", Assignments.SWING_TYPE);
    addShiftColumns(columns, flags, "showNightCnt", "showNightList", "Night", Assignments.NIGHT_TYPE);
    addShiftColumns(columns, flags, "showTrainingCnt", "showTrainingList", "Training",
                    Assignments.TRAINING_TYPE);
    addShiftColumns(columns, flags, "showOtherCnt", "showOtherList", "Other", Assignments.OTHER_TYPE);
    addShiftColumns(columns, flags, "showHolidayCnt", "showHolidayList", "Holiday",
                    Assignments.HOLIDAY_TYPE);

    return columns;
  }

  private void addShiftColumns(List<Column> columns, Map<String, Boolean> flags,
                               String countFlag, String listFlag, String label, int shiftType) {
    addIf(columns, flags, countFlag,
          new Column(label + " Shifts", member -> member.AssignmentCount[shiftType]));
    addIf(columns, flags, listFlag,
          new Column(label + " Shift Details", member -> clean(member.szAssignments[shiftType])));
  }

  private void addIf(List<Column> columns, Map<String, Boolean> flags, String flag, Column column) {
    if (Boolean.TRUE.equals(flags.get(flag))) {
      columns.add(column);
    }
  }

  /** Roster reads use "&nbsp;" as the empty placeholder; a spreadsheet wants a blank cell. */
  private static String clean(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("&nbsp;", "").trim();
  }

  private static String yesNo(boolean value) {
    return value ? "Yes" : "No";
  }

  private CellStyle boldStyle(Workbook workbook, int points) {
    Font font = workbook.createFont();
    font.setBold(true);
    font.setFontHeightInPoints((short) points);
    CellStyle style = workbook.createCellStyle();
    style.setFont(font);
    return style;
  }
}
