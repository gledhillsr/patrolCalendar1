package com.gledhills.calendar.controller;

import com.gledhills.calendar.store.MenuButton;
import com.gledhills.calendar.store.Roster;
import com.gledhills.calendar.utils.EventNameSanitizer;
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
import java.util.ArrayList;
import java.util.List;

/**
 * "Menu Configuration" on the Directors page: lets a resort define its own header buttons.
 *
 * Brighton's header carries two hand-written buttons, which is why this app has two header
 * fragments. The intent is for every resort to define the same thing here, so Brighton's move into
 * the table and one header serves everybody.
 *
 * This page only stores the definitions; nothing renders them in the header yet.
 *
 * Handles GET /ButtonMaintenance  - the form
 * Handles POST /ButtonMaintenance - saves all {@link MenuButton#MAX_BUTTONS} slots at once
 */
@Controller
public class ButtonMaintenanceController {

    @GetMapping("/ButtonMaintenance")
    public String showForm(HttpServletRequest request, HttpServletResponse response, Model model)
            throws IOException {
        return handle(request, response, model, false);
    }

    @PostMapping("/ButtonMaintenance")
    public String save(HttpServletRequest request, HttpServletResponse response, Model model)
            throws IOException {
        return handle(request, response, model, true);
    }

    private String handle(HttpServletRequest request, HttpServletResponse response, Model model,
                          boolean saving) throws IOException {
        String resort = request.getParameter("resort");

        javax.servlet.http.HttpServletRequest javaxRequest = ServletAdapter.toJavaxRequest(request);
        javax.servlet.http.HttpServletResponse javaxResponse = ServletAdapter.toJavaxResponse(response);
        Logger LOG = new Logger(ButtonMaintenanceController.class, javaxRequest,
                                saving ? "POST" : "GET", resort, Logger.INFO);

        if (!PatrolData.isValidResort(resort)) {
            response.sendError(400, "Resort not found: " + resort);
            return null;
        }

        SessionData sessionData = new SessionData(javaxRequest, null, LOG);
        PatrolData patrolData = new PatrolData(resort, sessionData, LOG);

        try {
            ValidateCredentialsRedirectIfNeeded credentials =
                new ValidateCredentialsRedirectIfNeeded(sessionData, javaxRequest, javaxResponse,
                                                        "ButtonMaintenance", patrolData, LOG);
            if (credentials.hasInvalidCredentials()) {
                return null; //redirect already sent
            }

            String loggedInUserId = sessionData.getLoggedInUserId();
            Roster editor = patrolData.getMemberByID(loggedInUserId);
            boolean isDirector = editor != null && editor.isDirector();

            // These buttons appear in every member's header, so only a director may change them.
            if (!isDirector) {
                LOG.error("DENIED menu configuration: member " + loggedInUserId + " is not a director");
                response.sendError(403, "Directors only");
                return null;
            }

            List<MenuButton> buttons;
            if (saving) {
                List<String> problems = new ArrayList<>();
                buttons = readButtonsFromForm(request, problems);

                if (problems.isEmpty()) {
                    buttons = enableNewlyDefined(
                        MenuButton.readAll(LOG, patrolData.getConnection()), buttons);

                    // Nothing creates this table on demand any more - Site Wide Configuration does
                    // it for every resort at once. So a resort added since that ran has no table,
                    // and a save that quietly failed must not report success.
                    if (MenuButton.writeAll(LOG, patrolData.getConnection(), buttons)) {
                        model.addAttribute("saved", true);
                    }
                    else {
                        problems.add("Could not save. This resort's menu button table is missing - "
                                       + "ask the site owner to run Site Wide Configuration.");
                        model.addAttribute("problems", problems);
                    }
                    //read back, so the page shows what is stored rather than what was typed
                    buttons = MenuButton.readAll(LOG, patrolData.getConnection());
                }
                else {
                    model.addAttribute("problems", problems);
                }
            }
            else {
                buttons = MenuButton.readAll(LOG, patrolData.getConnection());
            }

            model.addAttribute("resort", resort);
            model.addAttribute("resortFullName", PatrolData.getResortFullName(resort, LOG));
            model.addAttribute("patrollerId", loggedInUserId);
            model.addAttribute("buttons", buttons);

            ResortData resortData = PatrolData.getResortInfo(resort);
            if (resortData != null) {
                model.addAttribute("resortUrl", resortData.getResortURL());
                model.addAttribute("resortImage", resortData.getResortImage());
                model.addAttribute("resortImgHeight", resortData.getImageHeight());
            }

            return "button-maintenance";
        }
        finally {
            patrolData.close();
        }
    }

    /**
     * Turn on a slot that has just been filled in for the first time.
     *
     * Somebody who types a button expects to see it; making them tick a second box for that is a
     * trap they only notice when the button does not appear. Only on the way from blank to
     * complete, so a director who deliberately turns an existing button off and saves keeps it off
     * - by then the slot is no longer new.
     */
    private List<MenuButton> enableNewlyDefined(List<MenuButton> stored, List<MenuButton> submitted) {
        List<MenuButton> result = new ArrayList<>();

        for (MenuButton button : submitted) {
            MenuButton wasStored = stored.get(button.getPosition() - 1);
            boolean newlyDefined = wasStored.isBlank() && !button.isBlank();

            if (newlyDefined && !button.isEnabled()) {
                result.add(new MenuButton(button.getPosition(), button.getText(), button.getUrl(),
                                          button.isMemberOnly(), true));
            }
            else {
                result.add(button);
            }
        }
        return result;
    }

    /**
     * Read all four slots off the form, collecting anything that would make a bad button.
     *
     * A slot left entirely blank is fine and is simply cleared. A slot with something in it has to
     * be complete, and its url has to be a link this app is willing to render - the same test the
     * calendar's day comments use, which is what keeps "javascript:" out of an href.
     */
    private List<MenuButton> readButtonsFromForm(HttpServletRequest request, List<String> problems) {
        List<MenuButton> buttons = new ArrayList<>();

        for (int position = 1; position <= MenuButton.MAX_BUTTONS; position++) {
            String text = trimmed(request.getParameter("text_" + position));
            String url = trimmed(request.getParameter("url_" + position));
            boolean memberOnly = request.getParameter("memberOnly_" + position) != null;
            boolean enabled = request.getParameter("enabled_" + position) != null;

            if (!text.isEmpty() || !url.isEmpty()) {
                if (text.isEmpty()) {
                    problems.add("Button " + position + " needs button text.");
                }
                else if (text.length() > MenuButton.MAX_TEXT_LENGTH) {
                    //maxlength on the input is a browser courtesy, not a limit
                    problems.add("Button " + position + ": button text is limited to "
                                   + MenuButton.MAX_TEXT_LENGTH + " characters.");
                }
                if (url.isEmpty()) {
                    problems.add("Button " + position + " needs a URL.");
                }
                else if (!EventNameSanitizer.isSafeUrl(url)) {
                    problems.add("Button " + position + ": '" + url + "' is not a usable link. "
                                   + "Use http://, https://, mailto: or a path starting with /.");
                }
            }
            buttons.add(new MenuButton(position, text, url, memberOnly, enabled));
        }
        return buttons;
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
