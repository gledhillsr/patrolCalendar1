package com.gledhills.calendar.controller;

import com.gledhills.calendar.dto.LockerRoomAssignments;
import com.gledhills.calendar.dto.LockerRoomRoster;
import com.gledhills.calendar.dto.LockerRoomSkiHistory;
import com.gledhills.calendar.store.Assignments;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Read-only JSON for the locker-room software, which runs on a Mac at the mountain and cannot
 * reach this server's MySQL port (3306 is firewalled, deliberately). It used to open a direct
 * database connection and read `assignments` itself; that connection simply times out, and
 * handing a machine in a locker room raw SQL access to production was never a good trade.
 *
 * <p><b>Not a browser endpoint.</b> Authentication is a shared secret in the {@code X-Locker-Key}
 * header, matched against {@code lockerRoomApiKey} in credentials.properties. There is no session
 * and no cookie: the caller is a PHP script, not a logged-in member. If the key is not configured
 * the endpoint fails closed with 503 rather than serving the roster to anyone who asks.
 *
 * <p>{@link com.gledhills.calendar.config.BotBlockingFilter} skips this path. That filter rejects
 * any request whose User-Agent is missing or looks like a script - which is every sensible HTTP
 * client, PHP included - and it is the wrong check here: this endpoint already proves who the
 * caller is with a key, which is strictly stronger than a User-Agent string.
 */
@RestController
public class LockerRoomApiController {

  static final String API_KEY_HEADER = "X-Locker-Key";

  /** Path prefix that BotBlockingFilter lets through; keep the two in step. */
  public static final String API_PREFIX = "/api/locker/";

  @GetMapping(value = API_PREFIX + "assignments", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> assignments(@RequestParam(required = false) String resort,
                                       @RequestParam(required = false) String date,
                                       @RequestHeader(value = API_KEY_HEADER, required = false) String suppliedKey,
                                       HttpServletRequest request) {

    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(LockerRoomApiController.class, javaxRequest, "GET", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);

    ResponseEntity<?> refusal = refuseIfNotAllowed(sessionData, LOG, resort, suppliedKey, request);
    if (refusal != null) {
      return refusal;
    }

    LocalDate day;
    try {
      day = (date == null || date.isEmpty()) ? LocalDate.now() : LocalDate.parse(date);
    }
    catch (DateTimeParseException e) {
      return ResponseEntity.status(400).body(error("date must be yyyy-MM-dd, got: " + date));
    }

    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    try {
      List<LockerRoomAssignments.Slot> slots = new ArrayList<>();
      ArrayList<Assignments> shifts =
        patrolData.readSortedAssignments(day.getYear(), day.getMonthValue(), day.getDayOfMonth());
      for (Assignments shift : shifts) {
        //getCount() is how many positions this shift has, which is not the same as how many are
        //taken - the unfilled ones come back with id "0" and are meant to be shown as openings
        for (int i = 0; i < shift.getCount(); ++i) {
          String patrollerId = shift.getPosID(i);
          String name = null;
          if (patrollerId != null && !patrollerId.isEmpty() && !"0".equals(patrollerId)) {
            Roster member = patrolData.getMemberByID(patrollerId);
            if (member != null) {
              name = member.getFullName();
            }
          }
          else {
            patrollerId = "0";
          }
          slots.add(new LockerRoomAssignments.Slot(shift.getStartingTimeString(),
                                                   shift.getEndingTimeString(),
                                                   shift.getEventName(),
                                                   shift.getType(),
                                                   patrollerId,
                                                   name));
        }
      }
      LOG.info("locker room read " + slots.size() + " slot(s) for " + resort + " on " + day);
      return ResponseEntity.ok(new LockerRoomAssignments(resort, day.toString(), slots));
    }
    finally {
      patrolData.close();
    }
  }

  /**
   * Every roster row for this resort, the replacement for the roster half of the locker room's
   * old direct-MySQL sync (syncRoster.php).
   *
   * <p>Read straight from the table with the column names the result set reports, rather than
   * through Roster's accessors, because the caller writes the rows back into a table of the same
   * shape - a column added here should flow through without either side being taught about it.
   */
  @GetMapping(value = API_PREFIX + "roster", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> roster(@RequestParam(required = false) String resort,
                                  @RequestHeader(value = API_KEY_HEADER, required = false) String suppliedKey,
                                  HttpServletRequest request) {

    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(LockerRoomApiController.class, javaxRequest, "GET", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);

    ResponseEntity<?> refusal = refuseIfNotAllowed(sessionData, LOG, resort, suppliedKey, request);
    if (refusal != null) {
      return refusal;
    }

    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    try (Statement statement = patrolData.getConnection().createStatement();
         ResultSet rows = statement.executeQuery("SELECT * FROM `roster` ORDER BY IDNumber")) {
      ResultSetMetaData meta = rows.getMetaData();
      int columnCount = meta.getColumnCount();
      List<Map<String, String>> members = new ArrayList<>();
      while (rows.next()) {
        Map<String, String> member = new LinkedHashMap<>();
        for (int c = 1; c <= columnCount; ++c) {
          String column = meta.getColumnLabel(c);
          if (ROSTER_COLUMNS_TO_SKIP.contains(column.toLowerCase())) {
            continue;
          }
          String value = rows.getString(c);
          member.put(column, value == null ? "" : value);
        }
        members.add(member);
      }
      LOG.info("locker room read " + members.size() + " roster member(s) for " + resort);
      return ResponseEntity.ok(new LockerRoomRoster(resort, members.size(), members));
    }
    catch (Exception e) {
      LOG.logException("locker room roster read failed for " + resort, e);
      return ResponseEntity.status(500).body(error("could not read the roster"));
    }
    finally {
      patrolData.close();
    }
  }

  /**
   * Replaces the web site's skihistory with the locker room's copy - the upload half of
   * syncSkiHistory.php, which used to reconcile row by row over a MySQL connection to this
   * server. The locker room is where patrollers actually sign in, so it is the source of truth
   * for who skied; this makes the web site match it.
   *
   * <p>Reconciles rather than replacing the table wholesale: rows missing here are inserted,
   * rows that differ are updated, and rows the locker room no longer has are deleted. Same end
   * state as the old script, in one transaction instead of thousands of round trips.
   *
   * <p><b>It can delete.</b> If the locker-room database were empty or half restored, a plain
   * "make it match" would erase a season of history here with no way back. So a delete larger
   * than {@link #DELETE_ALARM_FRACTION} of what is stored is refused and nothing is applied,
   * until the caller repeats the request with force=true.
   */
  @PostMapping(value = API_PREFIX + "skihistory", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> uploadSkiHistory(@RequestParam(required = false) String resort,
                                            @RequestParam(required = false, defaultValue = "false") boolean force,
                                            @RequestHeader(value = API_KEY_HEADER, required = false) String suppliedKey,
                                            @RequestBody LockerRoomSkiHistory upload,
                                            HttpServletRequest request) {

    javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
    Logger LOG = new Logger(LockerRoomApiController.class, javaxRequest, "POST", resort, Logger.INFO);
    SessionData sessionData = new SessionData(javaxRequest, null, LOG);

    ResponseEntity<?> refusal = refuseIfNotAllowed(sessionData, LOG, resort, suppliedKey, request);
    if (refusal != null) {
      return refusal;
    }
    if (upload == null || upload.rows() == null) {
      return ResponseEntity.status(400).body(error("no rows in the request body"));
    }

    PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
    Connection connection = patrolData.getConnection();
    boolean restoreAutoCommit = false;
    try {
      //the columns the table actually has - anything else in the payload is ignored rather
      //than trusted, since these names are interpolated into the SQL
      Set<String> tableColumns = new LinkedHashSet<>();
      try (Statement statement = connection.createStatement();
           ResultSet probe = statement.executeQuery("SELECT * FROM `skihistory` WHERE 0=1")) {
        ResultSetMetaData meta = probe.getMetaData();
        for (int c = 1; c <= meta.getColumnCount(); ++c) {
          tableColumns.add(meta.getColumnLabel(c));
        }
      }
      if (!tableColumns.contains(HISTORY_ID)) {
        return ResponseEntity.status(500).body(error("skihistory has no " + HISTORY_ID + " column"));
      }

      //what the locker room sent, keyed by history_id
      Map<String, Map<String, String>> incoming = new LinkedHashMap<>();
      for (Map<String, String> row : upload.rows()) {
        String id = row.get(HISTORY_ID);
        if (id == null || id.isEmpty()) {
          return ResponseEntity.status(400).body(error("a row has no " + HISTORY_ID));
        }
        incoming.put(id, row);
      }

      //what is stored now
      Map<String, Map<String, String>> existing = new HashMap<>();
      try (Statement statement = connection.createStatement();
           ResultSet rows = statement.executeQuery("SELECT * FROM `skihistory`")) {
        while (rows.next()) {
          Map<String, String> row = new LinkedHashMap<>();
          for (String column : tableColumns) {
            String value = rows.getString(column);
            row.put(column, value == null ? "" : value);
          }
          existing.put(row.get(HISTORY_ID), row);
        }
      }

      List<String> toDelete = new ArrayList<>();
      for (String id : existing.keySet()) {
        if (!incoming.containsKey(id)) {
          toDelete.add(id);
        }
      }
      List<Map<String, String>> toInsert = new ArrayList<>();
      List<Map<String, String>> toUpdate = new ArrayList<>();
      int unchanged = 0;
      for (Map.Entry<String, Map<String, String>> entry : incoming.entrySet()) {
        Map<String, String> was = existing.get(entry.getKey());
        if (was == null) {
          toInsert.add(entry.getValue());
        }
        else if (differs(was, entry.getValue(), tableColumns)) {
          toUpdate.add(entry.getValue());
        }
        else {
          unchanged++;
        }
      }

      //the guard: a large delete is far more likely to be a broken locker-room database
      //than a real correction, and it is not recoverable from here
      int allowedDeletes = (int) Math.ceil(existing.size() * DELETE_ALARM_FRACTION);
      if (!force && toDelete.size() > allowedDeletes && !existing.isEmpty()) {
        String why = "refusing to delete " + toDelete.size() + " of " + existing.size() +
                     " stored rows (more than " + Math.round(DELETE_ALARM_FRACTION * 100) +
                     "%); nothing was changed. Repeat with force=true if this is intended.";
        LOG.error("skihistory upload from " + request.getRemoteAddr() + ": " + why);
        return ResponseEntity.status(409)
          .body(new LockerRoomSkiHistory.Result(0, 0, 0, unchanged, why));
      }

      restoreAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        List<String> columns = new ArrayList<>(tableColumns);
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
        String insertSql = "INSERT INTO `skihistory` (`" + String.join("`,`", columns) +
                           "`) VALUES (" + placeholders + ")";

        if (!toDelete.isEmpty()) {
          try (PreparedStatement delete =
                 connection.prepareStatement("DELETE FROM `skihistory` WHERE `" + HISTORY_ID + "`=?")) {
            for (String id : toDelete) {
              delete.setString(1, id);
              delete.addBatch();
            }
            delete.executeBatch();
          }
        }
        //an update is a delete plus an insert, the way the old script did it - skihistory has
        //no natural place to put a partial update and history_id is unique
        if (!toUpdate.isEmpty()) {
          try (PreparedStatement delete =
                 connection.prepareStatement("DELETE FROM `skihistory` WHERE `" + HISTORY_ID + "`=?")) {
            for (Map<String, String> row : toUpdate) {
              delete.setString(1, row.get(HISTORY_ID));
              delete.addBatch();
            }
            delete.executeBatch();
          }
        }
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
          for (List<Map<String, String>> batch : List.of(toInsert, toUpdate)) {
            for (Map<String, String> row : batch) {
              for (int c = 0; c < columns.size(); ++c) {
                String value = row.get(columns.get(c));
                insert.setString(c + 1, value == null ? "" : value);
              }
              insert.addBatch();
            }
          }
          insert.executeBatch();
        }
        connection.commit();
      }
      catch (Exception e) {
        connection.rollback();
        throw e;
      }
      finally {
        connection.setAutoCommit(restoreAutoCommit);
      }

      LOG.info("skihistory upload for " + resort + ": +" + toInsert.size() + " ~" + toUpdate.size() +
               " -" + toDelete.size() + " =" + unchanged);
      return ResponseEntity.ok(new LockerRoomSkiHistory.Result(
        toInsert.size(), toUpdate.size(), toDelete.size(), unchanged, null));
    }
    catch (Exception e) {
      LOG.logException("skihistory upload failed for " + resort, e);
      return ResponseEntity.status(500).body(error("upload failed: " + e.getMessage()));
    }
    finally {
      patrolData.close();
    }
  }

  private boolean differs(Map<String, String> was, Map<String, String> now, Set<String> columns) {
    for (String column : columns) {
      String a = was.get(column);
      String b = now.get(column);
      a = (a == null) ? "" : a;
      b = (b == null) ? "" : b;
      if (!a.equals(b)) {
        return true;
      }
    }
    return false;
  }

  static final String HISTORY_ID = "history_id";

  /** A delete touching more than this share of stored rows needs force=true. */
  static final double DELETE_ALARM_FRACTION = 0.10;

  /**
   * Passwords are not sent to the locker room. The old sync copied both of these down, where
   * nothing read them - the board lists names, it does not authenticate anyone. Leaving them
   * out means a machine sitting in a locker room no longer holds every member's password.
   * Patroller::getSQLRosterInsert writes '' for a column it was not given, so the local table
   * keeps its shape.
   */
  static final Set<String> ROSTER_COLUMNS_TO_SKIP = Set.of("password", "newpassword");

  /** Null when the caller may proceed, otherwise the response to send back. */
  private ResponseEntity<?> refuseIfNotAllowed(SessionData sessionData, Logger LOG, String resort,
                                               String suppliedKey, HttpServletRequest request) {
    String expectedKey = sessionData.readLockerRoomApiKey();
    if (expectedKey == null || expectedKey.isEmpty()) {
      LOG.error("lockerRoomApiKey is not set in credentials.properties, refusing the request");
      return ResponseEntity.status(503).body(error("locker room API is not configured on this server"));
    }
    if (!matches(expectedKey, suppliedKey)) {
      LOG.error("bad or missing " + API_KEY_HEADER + " from " + request.getRemoteAddr());
      return ResponseEntity.status(401).body(error("bad or missing " + API_KEY_HEADER));
    }
    if (!PatrolData.isValidResort(resort)) {
      return ResponseEntity.status(400).body(error("resort not found: " + resort));
    }
    return null;
  }

  /** Constant-time compare so a wrong key cannot be found one character at a time. */
  private boolean matches(String expected, String supplied) {
    if (supplied == null) {
      return false;
    }
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                 supplied.getBytes(StandardCharsets.UTF_8));
  }

  private java.util.Map<String, String> error(String message) {
    return java.util.Map.of("error", message);
  }
}
