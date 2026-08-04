package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.RosterSelection;
import com.gledhills.calendar.service.RosterExcelExporter;
import com.gledhills.calendar.service.RosterQueryService;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.ValidateCredentialsRedirectIfNeeded;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.time.LocalDate;

/**
 * Downloads the ListPatrollers roster listing as an .xlsx workbook.
 * Migrated from the legacy InnerDownloadRoster servlet, which wrote tab separated text under
 * an application/vnd.ms-excel content type (Excel warned about it on every open).
 *
 * Reached from the "Download this table (.xlsx format)" button on ListPatrollers, which passes
 * along the same filters and column choices the page was rendered with. Directors only - the
 * listing can include private comments and other member details.
 */
@Controller
public class DownloadRosterController {

  private static final MediaType XLSX =
    MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final RosterQueryService rosterQueryService;
  private final RosterExcelExporter rosterExcelExporter;

  public DownloadRosterController(RosterQueryService rosterQueryService,
                                  RosterExcelExporter rosterExcelExporter) {
    this.rosterQueryService = rosterQueryService;
    this.rosterExcelExporter = rosterExcelExporter;
  }

  @GetMapping("/DownloadRoster")
  public ResponseEntity<byte[]> downloadRoster(HttpServletRequest request,
                                               HttpServletResponse response) throws IOException {

    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);

    String resort = request.getParameter("resort");
    Logger LOG = new Logger(DownloadRosterController.class, javaxRequest, "GET", resort, Logger.INFO);

    if (!PatrolData.isValidResort(resort)) {
      response.sendError(400, "Resort not found: " + resort);
      return null;
    }

    SessionData sessionData = new SessionData(javaxRequest, null, LOG);
    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

    try {
      ValidateCredentialsRedirectIfNeeded credentials =
        new ValidateCredentialsRedirectIfNeeded(
          sessionData, javaxRequest, javaxResponse,
          "DownloadRosterController", patrolData, LOG);

      if (credentials.hasInvalidCredentials()) {
        return null; // redirect to MemberLogin already sent
      }

      String userId = sessionData.getLoggedInUserId();
      Roster editor = patrolData.getMemberByID(userId);
      if (editor == null || !editor.isDirector()) {
        LOG.info("non-director id=" + userId + " asked to download the roster");
        response.sendError(403, "Only directors may download the roster");
        return null;
      }

      RosterSelection selection = rosterQueryService.select(request, patrolData);
      byte[] workbook = rosterExcelExporter.toXlsx(selection, PatrolData.getResortFullName(resort, LOG));
      LOG.info("roster download: " + selection.members().size() + " members, " +
                 workbook.length + " bytes");

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(XLSX);
      headers.setContentDisposition(ContentDisposition.attachment().filename(fileName(resort)).build());
      headers.setCacheControl("no-store");
      return new ResponseEntity<>(workbook, headers, 200);
    }
    finally {
      patrolData.close();
    }
  }

  /** e.g. Sample_roster_2026-08-02.xlsx */
  private String fileName(String resort) {
    return resort + "_roster_" + LocalDate.now() + ".xlsx";
  }
}
