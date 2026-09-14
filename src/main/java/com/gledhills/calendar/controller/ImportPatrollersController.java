package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.NspRosterImport;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.ResortData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.utils.SqlWriter;
import com.gledhills.calendar.utils.ValidateCredentialsRedirectIfNeeded;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Add new patrollers from the roster a patrol representative downloads from nsp.org.
 *
 * <p>Director-only, and deliberately two steps: the file is read and <b>shown</b> first - who
 * would be added, and for everybody else the reason they would not be - and nothing is written
 * until the director presses Import on that page. Inserting members into a roster is not something
 * to do on the strength of a file picker.
 *
 * <p>Only ever inserts. A patroller already on the roster is listed as skipped and their record is
 * left exactly as it is, so running the same file twice adds nobody twice and overwrites nothing a
 * director has since edited by hand.
 */
@Controller
public class ImportPatrollersController {

    @GetMapping("/ImportPatrollers")
    public String show(@RequestParam String resort,
                       @RequestParam(required = false) String ID,
                       HttpServletRequest request, HttpServletResponse response, Model model)
        throws IOException {

        return withDirector(resort, request, response, model, (patrolData, LOG, sessionData) -> {
            model.addAttribute("step", "choose");
            return "import-patrollers";
        });
    }

    /** Read the file and show what would happen. Writes nothing. */
    @PostMapping("/ImportPatrollers")
    public String preview(@RequestParam String resort,
                          @RequestParam(required = false) String ID,
                          @RequestParam(required = false) MultipartFile rosterFile,
                          HttpServletRequest request, HttpServletResponse response, Model model)
        throws IOException {

        return withDirector(resort, request, response, model, (patrolData, LOG, sessionData) -> {
            if (rosterFile == null || rosterFile.isEmpty()) {
                model.addAttribute("step", "choose");
                model.addAttribute("errorMessage", "Choose the .csv file you downloaded from"
                                                     + " nsp.org, then press Read the file.");
                return "import-patrollers";
            }

            byte[] bytes;
            List<NspRosterImport.Candidate> candidates;
            try {
                bytes = rosterFile.getBytes();
                candidates = parse(bytes, patrolData);
            }
            catch (Exception e) {
                LOG.logException("could not read the nsp.org roster file", e);
                model.addAttribute("step", "choose");
                model.addAttribute("errorMessage",
                                   "That file could not be read as an nsp.org roster export.");
                return "import-patrollers";
            }

            //Held in the session, not sent back through the browser, so the director chooses the
            //file once. A file input cannot be pre-filled - browsers forbid it, and send a fake
            //path - so the only alternative to keeping it here is asking for it a second time.
            //Confirm re-parses these bytes and re-checks the roster; nothing posted decides who
            //gets inserted, which is the property that matters.
            request.getSession().setAttribute(PENDING_FILE, bytes);
            request.getSession().setAttribute(PENDING_FILE_NAME, rosterFile.getOriginalFilename());

            model.addAttribute("step", "preview");
            model.addAttribute("fileName", rosterFile.getOriginalFilename());
            addCandidates(model, candidates);
            return "import-patrollers";
        });
    }

    /**
     * Insert the new patrollers.
     *
     * <p><b>The file is read again here</b>, and the roster checked again, rather than acting on
     * anything the browser sent back. What may be inserted is decided by the file and the roster,
     * never by a posted list - otherwise a hand-built form could add anybody it liked.
     */
    @PostMapping("/ImportPatrollers/confirm")
    public String confirm(@RequestParam String resort,
                          @RequestParam(required = false) String ID,
                          @RequestParam(required = false) MultipartFile rosterFile,
                          HttpServletRequest request, HttpServletResponse response, Model model)
        throws IOException {

        return withDirector(resort, request, response, model, (patrolData, LOG, sessionData) -> {
            byte[] bytes = (byte[]) request.getSession().getAttribute(PENDING_FILE);
            if (bytes == null) {
                //a session that timed out, or Import pressed on a page reloaded from history
                model.addAttribute("step", "choose");
                model.addAttribute("errorMessage",
                                   "That file is no longer held. Please choose it and read it again.");
                return "import-patrollers";
            }

            List<NspRosterImport.Candidate> candidates;
            try {
                //parsed afresh, and the roster read again: somebody else may have added a member
                //since the preview, and they must still count as already here
                candidates = parse(bytes, patrolData);
            }
            catch (Exception e) {
                LOG.logException("could not re-read the nsp.org roster file", e);
                model.addAttribute("step", "choose");
                model.addAttribute("errorMessage",
                                   "That file could not be read as an nsp.org roster export.");
                return "import-patrollers";
            }

            int added = 0;
            List<String> failures = new ArrayList<>();
            for (NspRosterImport.Candidate candidate : candidates) {
                if (!candidate.importable()) {
                    continue;
                }
                if (insert(candidate, resort, patrolData, sessionData, LOG)) {
                    added++;
                }
                else {
                    failures.add(candidate.id() + " " + candidate.getFullName());
                }
            }

            LOG.warn("nsp.org roster import into " + resort + " by " + sessionData.getLoggedInUserId()
                       + ": " + added + " added, " + failures.size() + " failed");

            //Named one row at a time rather than counted: the value nsp.org used is the thing
            //that has to be read to decide whether it is a class this site should know, and a
            //bare count of "8 defaulted" tells nobody which eight or to what.
            for (NspRosterImport.Candidate candidate : candidates) {
                if (candidate.importable() && !candidate.classificationResolved()) {
                    LOG.warn("nsp.org import: " + candidate.id() + " " + candidate.getFullName()
                               + " - class \""
                               + (candidate.nspClass().isEmpty() ? "(blank)" : candidate.nspClass())
                               + "\" matches no classification here, imported as "
                               + candidate.getClassificationLabel());
                }
            }

            //spent: pressing back and Import again must not run the same file a second time
            request.getSession().removeAttribute(PENDING_FILE);
            request.getSession().removeAttribute(PENDING_FILE_NAME);

            model.addAttribute("step", "done");
            model.addAttribute("addedCount", added);
            model.addAttribute("failures", failures);
            addCandidates(model, candidates);
            return "import-patrollers";
        });
    }

    /**
     * The uploaded file, held between the preview and the confirm.
     *
     * <p>A patrol roster export is a few hundred rows - tens of kilobytes - so keeping it in the
     * session costs nothing, and it is dropped as soon as the import runs. The multipart limit in
     * application.properties caps what can arrive here regardless.
     */
    private static final String PENDING_FILE = "importPatrollersFile";
    private static final String PENDING_FILE_NAME = "importPatrollersFileName";

    private List<NspRosterImport.Candidate> parse(byte[] bytes, PatrolData patrolData)
        throws IOException {
        try (Reader reader = new InputStreamReader(new java.io.ByteArrayInputStream(bytes),
                                                   StandardCharsets.UTF_8)) {
            return NspRosterImport.read(reader, rosterIds(patrolData));
        }
    }

    /** A new member: what the file gives us, plus the defaults every import gets. */
    private boolean insert(NspRosterImport.Candidate candidate, String resort,
                           PatrolData patrolData, SessionData sessionData, Logger LOG) {
        try {
            Roster member = new Roster(LOG);
            member.memberData[Roster.ID_NUM] = candidate.id();
            member.setFirst(candidate.firstName());
            member.setLast(candidate.lastName());
            member.setEmail(candidate.email());
            member.setCellPhone(candidate.cellPhone());
            member.setClassification(candidate.classification());
            member.setCommitment(NspRosterImport.NEW_COMMITMENT);
            member.setDirector(NspRosterImport.NEW_DIRECTOR);
            //Instructor is an integer column and a new Roster starts its fields as " ", which
            //MySQL rejects outright. No certifications are known from the export anyway.
            member.setInstructor(NspRosterImport.NEW_INSTRUCTOR);

            //A first password, so the patroller can actually get in. Without one the insert still
            //hashes the blank default into newPassword, which reads as "has a password" - so the
            //last-name sign-in that covers a member with none never applies, and the account is
            //unreachable except through a password reset. The last name is what the login already
            //accepts for a member with no password set, so this matches what a patroller expects
            //to try, and it is case-insensitive: the hash is taken from the lower-cased value.
            member.setPassword(candidate.lastName());

            Connection connection = patrolData.getConnection();
            SqlWriter.executeUpdate(LOG, connection, "importPatroller",
                                    member.getInsertSQLString(resort, sessionData));
            return true;
        }
        catch (Exception e) {
            //one bad row must not abandon the rest: the director is told which failed
            LOG.logException("could not import patroller " + candidate.id(), e);
            return false;
        }
    }

    private void addCandidates(Model model, List<NspRosterImport.Candidate> candidates) {
        List<NspRosterImport.Candidate> importing = new ArrayList<>();
        List<NspRosterImport.Candidate> skipping = new ArrayList<>();
        boolean anyClassDefaulted = false;
        for (NspRosterImport.Candidate candidate : candidates) {
            (candidate.importable() ? importing : skipping).add(candidate);
            if (candidate.importable() && !candidate.classificationResolved()) {
                anyClassDefaulted = true;
            }
        }
        model.addAttribute("importing", importing);
        model.addAttribute("skipping", skipping);
        model.addAttribute("rowCount", candidates.size());
        //the note explaining the italics is drawn only when there is something in italics
        model.addAttribute("anyClassDefaulted", anyClassDefaulted);
    }

    /** Every member id already on this resort's roster. */
    private Set<String> rosterIds(PatrolData patrolData) {
        Set<String> ids = new HashSet<>();
        ResultSet rows = patrolData.resetRoster();
        Roster member;
        while ((member = patrolData.nextMember("", rows)) != null) {
            ids.add(member.getID());
        }
        return ids;
    }

    /** What a handler does once the caller is known to be a director of this resort. */
    private interface DirectorWork {
        String run(PatrolData patrolData, Logger LOG, SessionData sessionData) throws IOException;
    }

    /**
     * The gate every entry point here shares: a real resort, a signed-in member, and a director.
     * Authority is the session, never the {@code ID} parameter - that is for links and logging.
     */
    private String withDirector(String resort, HttpServletRequest request,
                                HttpServletResponse response, Model model, DirectorWork work)
        throws IOException {

        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse =
            ServletAdapter.toJavaxResponse(response);

        Logger LOG = new Logger(ImportPatrollersController.class, javaxRequest, "POST", resort,
                                Logger.INFO);
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        try {
            ValidateCredentialsRedirectIfNeeded credentials =
                new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                                        "ImportPatrollers", patrolData, LOG);
            if (credentials.hasInvalidCredentials()) {
                return null;   //redirect already sent
            }

            String loggedInUserId = sessionData.getLoggedInUserId();
            Roster member = patrolData.getMemberByID(loggedInUserId);
            if (member == null || !member.isDirector()) {
                LOG.error("DENIED ImportPatrollers: member " + loggedInUserId + " is not a director");
                response.sendError(403, "Directors only");
                return null;
            }

            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", loggedInUserId);
            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            return work.run(patrolData, LOG, sessionData);
        }
        finally {
            patrolData.close();
        }
    }
}
