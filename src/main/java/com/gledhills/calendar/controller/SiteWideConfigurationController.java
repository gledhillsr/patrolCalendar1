package com.gledhills.calendar.controller;

import com.gledhills.calendar.ShiftLabelMigration;
import com.gledhills.calendar.ShiftTimeCleanup;
import com.gledhills.calendar.store.MenuButton;
import com.gledhills.calendar.store.CalendarFeedToken;
import com.gledhills.calendar.store.PasswordResetToken;
import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.store.ShiftTemplatePatrollers;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.ResortData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.ValidateCredentialsRedirectIfNeeded;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;
import com.gledhills.calendar.store.DirectorSettings;
import com.gledhills.calendar.utils.ResortTimeZones;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Jobs that touch every resort at once, rather than the one whose page you are on.
 *
 * Only the site owner sees this - it walks all 48 databases in {@code PatrolData.resortMap}, which
 * is nobody's business but theirs. The gate is deliberately the two literals below rather than
 * "is a director": every resort has directors, and this is not a director's tool.
 *
 * Handles GET /SiteWideConfiguration  - what it will do
 * Handles POST /SiteWideConfiguration - does it, and reports per resort
 */
@Controller
public class SiteWideConfigurationController {

    /** The one member who may run these. */
    static final String OWNER_RESORT = "Brighton";
    static final String OWNER_ID = "192443";

    /** One resort's outcome, for the results table. */
    public static class ResortResult {
        private final String resort;
        private final boolean created;
        private final String detail;

        ResortResult(String resort, boolean created, String detail) {
            this.resort = resort;
            this.created = created;
            this.detail = detail;
        }

        public String getResort() {
            return resort;
        }

        public boolean isCreated() {
            return created;
        }

        public String getDetail() {
            return detail;
        }
    }

    @GetMapping("/SiteWideConfiguration")
    public String show(HttpServletRequest request, HttpServletResponse response, Model model)
            throws IOException {
        return handle(request, response, model, false);
    }

    @PostMapping("/SiteWideConfiguration")
    public String run(HttpServletRequest request, HttpServletResponse response, Model model)
            throws IOException {
        return handle(request, response, model, true);
    }

    private String handle(HttpServletRequest request, HttpServletResponse response, Model model,
                          boolean running) throws IOException {
        String resort = request.getParameter("resort");

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(SiteWideConfigurationController.class, javaxRequest,
                                running ? "POST" : "GET", resort, Logger.INFO);

        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);
        String loggedInUserId;
        try {
            ValidateCredentialsRedirectIfNeeded credentials =
                new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                                        "SiteWideConfiguration", patrolData, LOG);
            if (credentials.hasInvalidCredentials()) {
                return null; //redirect already sent
            }
            loggedInUserId = sessionData.getLoggedInUserId();
        }
        finally {
            patrolData.close();
        }

        if (!isSiteOwner(resort, loggedInUserId)) {
            LOG.error("DENIED site wide configuration: " + loggedInUserId + " at " + resort);
            response.sendError(403, "Not authorized");
            return null;
        }

        model.addAttribute("resort", resort);
        model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
        model.addAttribute("patrollerId", loggedInUserId);
        model.addAttribute("resortCount", PatrolData.resortMap.size());

        if (running) {
            String action = request.getParameter("action");
            List<ResortResult> results;
            String heading;

            if ("removeUnnamed".equals(action)) {
                results = unnamedTemplates(sessionData, LOG, true);
                heading = "Unnamed shift templates removed";
            }
            else if ("findUnnamed".equals(action)) {
                results = unnamedTemplates(sessionData, LOG, false);
                heading = "Unnamed shift templates found";
            }
            else if ("timeZones".equals(action)) {
                results = timeZones(sessionData, LOG);
                heading = "Calendar sync settings added";
            }
            else if ("shiftLabels".equals(action)) {
                results = shiftLabels(sessionData, LOG);
                heading = "Shift Label column added and old Start values sorted";
            }
            else if ("shiftTimes".equals(action)) {
                results = shiftTimes(sessionData, LOG);
                heading = "Shift template start and end times tidied";
            }
            else {
                results = createTables(sessionData, LOG);
                heading = "Tables created";
            }
            model.addAttribute("results", results);
            model.addAttribute("resultsHeading", heading);
            model.addAttribute("okCount", results.stream().filter(ResortResult::isCreated).count());
        }

        ResortData resortData = PatrolData.getResortInfo(resort);
        if (resortData != null) {
            model.addAttribute("resortUrl", resortData.getResortURL());
            model.addAttribute("resortImage", resortData.getResortImage());
            model.addAttribute("resortImgHeight", resortData.getImageHeight());
        }

        return "site-wide-configuration";
    }

    /** Whether this is the site owner, on their own resort. */
    static boolean isSiteOwner(String resort, String patrollerId) {
        return OWNER_RESORT.equalsIgnoreCase(resort) && OWNER_ID.equals(patrollerId);
    }

    /**
     * Create every table the app expects to already exist, in every resort's database.
     *
     * Both are listed rather than just the one being migrated today: the job is idempotent, and
     * dropping menubuttons from it would leave a resort added to the map later with no table and
     * no way to get one - its directors could not save a menu and there would be no button to fix
     * it.
     *
     * A resort in the map may have no database yet, so each is reported rather than allowed to
     * stop the run - the point is a list of what worked, not an all-or-nothing migration. Sorted
     * so the report reads the same way twice.
     */
    /**
     * Which resorts the shift-label migration touches: all of them.
     *
     * <p>It was held to Sample alone while it was being proved on the live site, which is why this
     * is a named method rather than the map inlined into the loop - it is the one place to narrow
     * again if a resort ever needs doing on its own.
     */
    static Set<String> shiftLabelResorts() {
        return PatrolData.resortMap.keySet();
    }

    /**
     * Gives every resort the shift {@code Label} column and sorts the old free-text Start values
     * into it - the migration described on {@link ShiftLabelMigration}.
     *
     * <p>Idempotent, and safe to press again: a resort that has already been done writes nothing
     * and reports "nothing to do". It is judged on the data rather than on whether the column
     * exists, so a resort whose column was added but whose rows were not moved - a run that died
     * in between, which no transaction can prevent because MySQL commits DDL immediately - is
     * finished rather than skipped forever.
     *
     * <p>A resort with no database is reported and stepped over, the same as the other jobs here:
     * several entries in resortMap have never had one.
     */
    private List<ResortResult> shiftLabels(SessionData sessionData, Logger LOG) {
        List<ResortResult> results = new ArrayList<>();

        for (String resort : new TreeSet<>(shiftLabelResorts())) {
            PatrolData patrolData = null;
            try {
                patrolData = new PatrolData(resort, sessionData, LOG);
                if (patrolData.getConnection() == null) {
                    results.add(new ResortResult(resort, false, "no database"));
                    continue;
                }
                ShiftLabelMigration.ResortOutcome outcome =
                    ShiftLabelMigration.migrateResort(patrolData.getConnection());
                if (!outcome.ok()) {
                    LOG.error("shift label migration failed for " + resort + ": " + outcome.detail());
                }
                results.add(new ResortResult(resort, outcome.ok(), outcome.detail()));
            }
            catch (Exception e) {
                LOG.logException("shift label migration failed for " + resort, e);
                results.add(new ResortResult(resort, false, e.getClass().getSimpleName()));
            }
            finally {
                if (patrolData != null) {
                    try {
                        patrolData.close();
                    }
                    catch (Exception ignored) {
                        //closing a connection we are finished with cannot fail the job
                    }
                }
            }
        }
        return results;
    }

    /**
     * Rewrites every resort's shift <b>templates</b> so their start and end times are readable and
     * written the one way - {@link ShiftTimeCleanup}.
     *
     * <p>Three things the Label migration left behind: a missing start time recoverable from a Label, because the old
     * rule would not accept {@code 4:00P}, a start not in canonical form, and an end that was never
     * migrated at all. All three are the computer's to fix; what it cannot read it leaves alone and
     * the panel on Edit Shift Templates asks a director about.
     *
     * <p><b>Templates only.</b> The same faults run through twenty years of {@code assignments},
     * and rewriting a shift somebody worked in 2019 helps nobody. Templates are what build the
     * seasons ahead.
     *
     * <p>Idempotent: a row already in canonical form is not written, so a second press reports
     * "nothing to do". Runs over the same resort list as the Label job, since it finishes that
     * job's work and cannot run anywhere it has not been.
     */
    private List<ResortResult> shiftTimes(SessionData sessionData, Logger LOG) {
        List<ResortResult> results = new ArrayList<>();

        for (String resort : new TreeSet<>(shiftLabelResorts())) {
            PatrolData patrolData = null;
            try {
                patrolData = new PatrolData(resort, sessionData, LOG);
                if (patrolData.getConnection() == null) {
                    results.add(new ResortResult(resort, false, "no database"));
                    continue;
                }
                ShiftLabelMigration.ResortOutcome outcome =
                    ShiftTimeCleanup.cleanResort(patrolData.getConnection());
                if (!outcome.ok()) {
                    LOG.error("shift time cleanup failed for " + resort + ": " + outcome.detail());
                }
                results.add(new ResortResult(resort, outcome.ok(), outcome.detail()));
            }
            catch (Exception e) {
                LOG.logException("shift time cleanup failed for " + resort, e);
                results.add(new ResortResult(resort, false, e.getClass().getSimpleName()));
            }
            finally {
                if (patrolData != null) {
                    try {
                        patrolData.close();
                    }
                    catch (Exception ignored) {
                        //closing a connection we are finished with cannot fail the job
                    }
                }
            }
        }
        return results;
    }

    /**
     * Give every resort a {@code timeZone} column on directorsettings, and fill in the one
     * {@link ResortTimeZones} believes is right.
     *
     * <p>Idempotent, and judged on the data rather than on whether the column exists: a resort
     * whose column was added but never filled in - a run that died between the two, which no
     * transaction can prevent because MySQL commits DDL immediately - is finished rather than
     * skipped forever. A resort that already holds a zone is <b>left alone</b>: a director may
     * have corrected it, and their answer is the authority.
     */
    private List<ResortResult> timeZones(SessionData sessionData, Logger LOG) {
        List<ResortResult> results = new ArrayList<>();

        for (String resort : new TreeSet<>(PatrolData.resortMap.keySet())) {
            PatrolData patrolData = null;
            try {
                patrolData = new PatrolData(resort, sessionData, LOG);
                if (patrolData.getConnection() == null) {
                    results.add(new ResortResult(resort, false, "no database"));
                    continue;
                }
                results.add(addCalendarSyncColumns(resort, patrolData.getConnection()));
            }
            catch (Exception e) {
                //the message, not just the class: "SQLSyntaxErrorException" tells the site owner
                //nothing they can act on, and this job walks 54 databases
                LOG.logException("time zone job failed for " + resort, e);
                results.add(new ResortResult(resort, false,
                                             e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
            finally {
                if (patrolData != null) {
                    try {
                        patrolData.close();
                    }
                    catch (Exception ignored) {
                        //closing a finished connection cannot fail the job
                    }
                }
            }
        }
        return results;
    }

    /**
     * Both columns the calendar-sync settings need: the resort's zone, and the on/off switch.
     *
     * <p>Done together because they are one feature on one table, and because a resort with only
     * half of them is a state nothing else in the app expects.
     *
     * <p><b>syncEnabled is created but never switched on here.</b> Defaulting it to "0" is what
     * makes the feed opt-in: turning sync on for 44 resorts from a button the site owner pressed
     * for a different reason is precisely the sort of thing nobody would find until a patroller
     * asked why their calendar had filled up.
     */
    private ResortResult addCalendarSyncColumns(String resort, Connection connection)
        throws SQLException {
        ResortResult zone = addTimeZone(resort, connection);

        if (!hasColumn(connection, "directorsettings", DirectorSettings.SYNC_ENABLED_FIELD)) {
            addColumnDespiteLegacyDefaults(connection,
                "ALTER TABLE `directorsettings` ADD COLUMN `"
                  + DirectorSettings.SYNC_ENABLED_FIELD + "` varchar(1) NOT NULL DEFAULT '0'");
            return new ResortResult(resort, zone.isCreated(),
                                    zone.getDetail() + ", syncEnabled column added (off)");
        }
        return zone;
    }

    private ResortResult addTimeZone(String resort, Connection connection) throws SQLException {
        boolean added = false;
        if (!hasColumn(connection, "directorsettings", DirectorSettings.TIME_ZONE_FIELD)) {
            addColumnDespiteLegacyDefaults(connection,
                "ALTER TABLE `directorsettings` ADD COLUMN `"
                  + DirectorSettings.TIME_ZONE_FIELD + "` varchar(40) NOT NULL DEFAULT ''");
            added = true;
        }

        String zone = ResortTimeZones.forResort(resort).getId();
        int filled;
        String sql = "UPDATE `directorsettings` SET `" + DirectorSettings.TIME_ZONE_FIELD
                       + "` = ? WHERE `" + DirectorSettings.TIME_ZONE_FIELD + "` = '' OR `"
                       + DirectorSettings.TIME_ZONE_FIELD + "` IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, zone);
            filled = statement.executeUpdate();
        }

        if (!added && filled == 0) {
            return new ResortResult(resort, true, "already set");
        }
        return new ResortResult(resort, true,
                                (added ? "column added, " : "") + filled + " row(s) set to " + zone);
    }

    /**
     * Add a column to a table that MySQL would otherwise refuse to touch.
     *
     * <p>{@code directorsettings} carries {@code lastVoucherHistoryUpdate date NOT NULL DEFAULT
     * '0000-00-00'} from twenty years ago, and an ALTER makes the server revalidate the whole
     * table - which today's {@code NO_ZERO_DATE} mode then rejects. The failure names that old
     * column and says nothing about the one being added, which is thoroughly misleading.
     *
     * <p>So the mode is relaxed for this one statement and put straight back. The offending
     * default is left exactly as it is: changing a column twenty years of rows depend on, as a side
     * effect of adding an unrelated one, is not a trade worth making.
     */
    private void addColumnDespiteLegacyDefaults(Connection connection, String alterSql)
        throws SQLException {

        String previousMode = null;
        try (Statement statement = connection.createStatement()) {
            try (ResultSet mode = statement.executeQuery("SELECT @@session.sql_mode")) {
                if (mode.next()) {
                    previousMode = mode.getString(1);
                }
            }
            statement.execute("SET SESSION sql_mode = ''");
            statement.executeUpdate(alterSql);
        }
        finally {
            if (previousMode != null) {
                try (PreparedStatement restore =
                       connection.prepareStatement("SET SESSION sql_mode = ?")) {
                    restore.setString(1, previousMode);
                    restore.execute();
                }
                catch (SQLException e) {
                    //the connection is closed at the end of this resort either way
                }
            }
        }
    }

    private boolean hasColumn(Connection connection, String table, String column)
        throws SQLException {
        try (ResultSet columns =
               connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return columns.next();
        }
    }

    private List<ResortResult> createTables(SessionData sessionData, Logger LOG) {
        List<ResortResult> results = new ArrayList<>();

        for (String resort : new TreeSet<>(PatrolData.resortMap.keySet())) {
            PatrolData patrolData = null;
            try {
                patrolData = new PatrolData(resort, sessionData, LOG);
                if (patrolData.getConnection() == null) {
                    results.add(new ResortResult(resort, false, "no database"));
                    continue;
                }
                boolean menuButtons = MenuButton.createTable(LOG, patrolData.getConnection());
                boolean templatePatrollers =
                    ShiftTemplatePatrollers.createTable(LOG, patrolData.getConnection());
                boolean resetTokens =
                    PasswordResetToken.createTable(LOG, patrolData.getConnection(), resort);
                boolean feedTokens =
                    CalendarFeedToken.createTable(LOG, patrolData.getConnection());

                boolean created =
                    menuButtons && templatePatrollers && resetTokens && feedTokens;
                results.add(new ResortResult(resort, created,
                                             created ? "ok"
                                                     : "menubuttons " + outcome(menuButtons)
                                                         + ", shifttemplatepatrollers "
                                                         + outcome(templatePatrollers)
                                                         + ", password_reset_tokens "
                                                         + outcome(resetTokens)
                                                         + ", calendarfeedtokens "
                                                         + outcome(feedTokens)));
            }
            catch (Exception e) {
                results.add(new ResortResult(resort, false, e.getClass().getSimpleName()));
            }
            finally {
                if (patrolData != null) {
                    try {
                        patrolData.close();
                    }
                    catch (Exception e) {
                        //nothing useful to do; the result is already recorded
                    }
                }
            }
        }
        LOG.info("site wide table create: " + results.stream().filter(ResortResult::isCreated).count()
                   + " of " + results.size() + " resorts");
        return results;
    }

    private String outcome(boolean created) {
        return created ? "ok" : "failed";
    }

    /**
     * Find - or remove - shift template rows with no event name, in every resort.
     *
     * These are the "_0" rows the template editor used to write when it was saved with the name
     * field blank: they belong to no template and show as a blank entry in its dropdown. Both
     * routes that made them are closed, so this is only about what they left behind.
     *
     * Counting and deleting share a walk so that "find" and "remove" cannot disagree about what
     * counts as unnamed - the rule lives once, in ShiftDefinitions.
     *
     * @param remove false to report only, which is what the first button does
     */
    private List<ResortResult> unnamedTemplates(SessionData sessionData, Logger LOG, boolean remove) {
        List<ResortResult> results = new ArrayList<>();

        for (String resort : new TreeSet<>(PatrolData.resortMap.keySet())) {
            PatrolData patrolData = null;
            try {
                patrolData = new PatrolData(resort, sessionData, LOG);
                if (patrolData.getConnection() == null) {
                    results.add(new ResortResult(resort, false, "no database"));
                    continue;
                }
                int found = ShiftDefinitions.countUnnamed(LOG, patrolData.getConnection());
                if (found < 0) {
                    results.add(new ResortResult(resort, false, "could not read"));
                    continue;
                }
                if (found == 0) {
                    results.add(new ResortResult(resort, true, "none"));
                    continue;
                }
                if (!remove) {
                    results.add(new ResortResult(resort, true, found + " to remove"));
                    continue;
                }
                int removed = ShiftDefinitions.deleteUnnamed(LOG, patrolData.getConnection());
                results.add(removed < 0 ? new ResortResult(resort, false, "delete failed")
                                        : new ResortResult(resort, true, removed + " removed"));
            }
            catch (Exception e) {
                results.add(new ResortResult(resort, false, e.getClass().getSimpleName()));
            }
            finally {
                if (patrolData != null) {
                    try {
                        patrolData.close();
                    }
                    catch (Exception e) {
                        //nothing useful to do; the result is already recorded
                    }
                }
            }
        }
        return results;
    }
}
