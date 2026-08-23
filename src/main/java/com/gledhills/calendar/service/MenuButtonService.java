package com.gledhills.calendar.service;

import com.gledhills.calendar.store.MenuButton;
import com.gledhills.calendar.utils.Logger;
import com.gledhills.calendar.utils.PatrolData;
import com.gledhills.calendar.utils.SessionData;
import com.gledhills.calendar.controller.ServletAdapter;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * The custom header buttons for a resort, for the header fragment to draw.
 *
 * Called from the template as {@code ${@menuButtonService.forHeader(resort, patrollerId)}} rather
 * than through the model, because the header is drawn by every page in the app - putting it in the
 * model would mean touching every controller and remembering to do it in the next one.
 *
 * Opens its own short-lived connection. That is one extra query per page; the alternative is
 * threading the list through twenty controllers, and this feature exists precisely so that adding
 * a button does not mean editing code.
 */
@Service
public class MenuButtonService {

    /**
     * The two bars' worth of buttons, split by who may see them.
     *
     * @param publicButtons drawn in the light grey bar above the menu, for everybody
     * @param memberButtons drawn in the members' bar between Directors and Logout, and only when
     *                      somebody is logged in
     */
    public record HeaderButtons(List<MenuButton> publicButtons, List<MenuButton> memberButtons) {
    }

    /**
     * What this viewer's header should draw, fetched in one go - the two bars sit in different
     * places in the markup, and a template asking twice would mean two queries a page.
     *
     * Never throws. A header that cannot reach the database draws no custom buttons rather than
     * taking every page in the app down with it.
     *
     * @param patrollerId the logged-in member, or null/blank for a visitor
     */
    public HeaderButtons forHeader(String resort, String patrollerId) {
        boolean loggedIn = patrollerId != null && !patrollerId.trim().isEmpty();
        try {
            List<MenuButton> all = forResort(resort).stream()
                .map(button -> resolve(button, resort, patrollerId))
                .toList();
            return new HeaderButtons(
                all.stream().filter(button -> !button.isMemberOnly()).toList(),
                loggedIn ? all.stream().filter(MenuButton::isMemberOnly).toList() : List.of());
        }
        catch (Exception e) {
            return new HeaderButtons(List.of(), List.of());
        }
    }

    /**
     * Fill in the placeholders a configured url may carry.
     *
     * A link into this app needs to say who is asking - the built-in menu items are all built as
     * {@code @{/Page(resort=..., ID=...)}} - but a url typed into a form is a fixed string. So
     * {@code {ID}} and {@code {RESORT}} are substituted here. Without this a configured link to,
     * say, RefresherTraining arrives with no member on it, which is how Brighton's button came to
     * ask for a login it had already been given.
     */
    private MenuButton resolve(MenuButton button, String resort, String patrollerId) {
        String url = button.getUrl();
        if (!url.contains("{")) {
            return button;
        }
        String resolved = url
            .replace("{ID}", patrollerId == null ? "" : patrollerId)
            .replace("{RESORT}", resort == null ? "" : resort);
        return new MenuButton(button.getPosition(), button.getText(), resolved,
                              button.isMemberOnly(), button.isEnabled());
    }

    private List<MenuButton> forResort(String resort) {
        if (resort == null || !PatrolData.isValidResort(resort)) {
            return List.of();
        }
        // Logger and SessionData are both built around a request, and this is called from a
        // template rather than a handler, so take the one in flight.
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return List.of();
        }
        javax.servlet.http.HttpServletRequest javaxRequest =
            ServletAdapter.toJavaxRequest(attributes.getRequest());

        Logger LOG = new Logger(MenuButtonService.class, javaxRequest, "GET", resort, Logger.INFO);
        PatrolData patrolData = new PatrolData(resort, new SessionData(javaxRequest, null, LOG), LOG);
        try {
            return MenuButton.readDisplayable(LOG, patrolData.getConnection());
        }
        finally {
            patrolData.close();
        }
    }
}
