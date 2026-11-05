package com.gledhills.calendar.controller;

import com.gledhills.calendar.service.EmailBatchLog;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a bulk send is getting on, for the line that counts up on the confirmation page.
 *
 * <p><b>Why not a meta-refresh.</b> Re-rendering the whole confirmation page every five seconds
 * costs ~30KB and a database round trip for the header's custom menu buttons, sixty times over for
 * a five minute wait, and it cannot stop itself when the batch finishes. This returns about a
 * hundred bytes, touches no database, and lets the page stop polling the moment the send is done -
 * which for most batches is a few seconds, not five minutes.
 *
 * <p>Gated on a session logged in to this resort, and the batch must belong to that resort. The
 * id is a random UUID so it is not guessable, but a subject line and a recipient count are still
 * nobody else's business.
 */
@Controller
public class EmailSendStatusController {

    private final EmailBatchLog batchLog;

    public EmailSendStatusController(EmailBatchLog batchLog) {
        this.batchLog = batchLog;
    }

    @GetMapping("/EmailSendStatus")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> status(@RequestParam String resort,
                                                      @RequestParam(name = "batch") String batchId,
                                                      HttpServletRequest request) {
        if (!PatrolData.isValidResort(resort)) {
            return ResponseEntity.badRequest().build();
        }

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        Logger LOG = new Logger(EmailSendStatusController.class, javaxRequest, "GET", resort,
                                Logger.INFO);
        SessionData sessionData = new SessionData(javaxRequest, null, LOG);

        if (!resort.equals(sessionData.getLoggedInResort())
              || sessionData.getLoggedInUserId() == null) {
            return ResponseEntity.status(403).build();
        }

        EmailBatchLog.Send send = batchLog.find(batchId);

        //...and it must be this resort's batch. The id is a random UUID so it is not guessable,
        //but the check costs nothing and means a leaked id still cannot read another resort's
        //subject line from a session that has no business with it.
        if (send != null && !resort.equals(send.resort())) {
            return ResponseEntity.status(403).build();
        }

        if (send == null) {
            //aged out, or the application restarted mid-send. Not an error: the page says so and
            //stops asking rather than polling for something that will never appear.
            Map<String, Object> gone = new LinkedHashMap<>();
            gone.put("found", false);
            gone.put("finished", true);
            gone.put("status", "This send is no longer being tracked. "
                               + "If any of it failed you will be emailed a summary.");
            return ResponseEntity.ok(gone);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("found", true);
        body.put("finished", send.finished());
        body.put("queued", send.queued());
        body.put("sent", send.sent());
        body.put("status", send.describeStatus());
        body.put("cleanly", send.finished() && send.wentOutCleanly());
        if (send.stoppedEarly()) {
            body.put("stopReason", send.stopReason());
        }
        return ResponseEntity.ok(body);
    }
}
