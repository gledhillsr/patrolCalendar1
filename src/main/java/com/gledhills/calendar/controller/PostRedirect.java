package com.gledhills.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.View;

/**
 * Redirecting away from a POST, with the status code that actually says "now GET this".
 *
 * <p>Both of Spring's ordinary ways out of a POST handler answer <b>302 Found</b>:
 * {@code response.sendRedirect} always does, and so does a {@code "redirect:..."} view name.
 * RFC 9110 says a client receiving a 302 for a POST <em>may</em> repeat the POST at the new
 * location - browsers turn it into a GET by long convention, but that is convention, not the
 * specification, and in-app webviews and proxies do not all follow it.
 *
 * <p>When one of them does repeat the POST, it lands on a page that only answers GET - the month
 * calendar after a successful login, the login page after a password reset - and the patroller is
 * shown a bare <b>405 Method Not Allowed</b> at the exact moment they thought they had signed in.
 * There is nothing in the page to explain it and nothing they can do but start again.
 *
 * <p><b>303 See Other</b> is the status that <em>requires</em> the client to switch to GET. It has
 * been in the standard since HTTP/1.1 and every browser has understood it for twenty years, so
 * there is no cost to sending it and no reason a POST that succeeded should say anything else.
 *
 * <p>Only redirects that leave a POST need this. A redirect from a GET has no method to preserve,
 * so 302 there is correct and is left alone.
 */
final class PostRedirect {

    private PostRedirect() {
    }

    /**
     * Send the redirect immediately, in place of {@code response.sendRedirect(url)}, which has no
     * way to say anything but 302.
     */
    static void sendTo(HttpServletResponse response, String url) {
        response.setStatus(HttpStatus.SEE_OTHER.value());
        response.setHeader("Location", url);
    }

    /**
     * Mark a {@code "redirect:..."} view name so it goes out as 303, and hand it straight back for
     * returning. {@code RedirectView} reads this attribute and skips {@code sendRedirect} when it
     * is set, which is the same route {@code @ResponseStatus} takes.
     *
     * <pre>return PostRedirect.view(request, "redirect:/MemberLogin?resort=" + resort);</pre>
     */
    static String view(HttpServletRequest request, String redirectViewName) {
        request.setAttribute(View.RESPONSE_STATUS_ATTRIBUTE, HttpStatus.SEE_OTHER);
        return redirectViewName;
    }
}
