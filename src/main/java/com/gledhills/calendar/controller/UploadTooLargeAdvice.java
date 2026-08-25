package com.gledhills.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Turns "that upload was too big" into the EmailForm page saying so, instead of the bare 413 the
 * browser would otherwise show.
 *
 * This has to be @ControllerAdvice rather than an @ExceptionHandler on EmailFormController.
 * Multipart bodies are parsed in DispatcherServlet.checkMultipart, which runs *before* the handler
 * is chosen, so when parsing fails there is no controller yet and only advice beans are consulted.
 * A handler on the controller itself is never reached, and Spring falls through to its default
 * resolver - which renders MaxUploadSizeExceededException as a raw 413.
 */
@ControllerAdvice
public class UploadTooLargeAdvice {

    /**
     * The multipart body is unreadable at this point, so send the user back to a clean form
     * rather than trying to redisplay what they typed. EmailForm is the only upload on the site;
     * anything else gets the plain 413, which is at least honest.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleUploadTooLarge(HttpServletRequest request) throws MaxUploadSizeExceededException {
        if (!request.getRequestURI().endsWith("/EmailForm")) {
            throw new MaxUploadSizeExceededException(-1);
        }
        // resort/fromPatrollerId ride in the form action's query string, so they survive
        // even though the body was rejected
        String queryString = request.getQueryString();
        // audience too: without it a site-wide director mailing that was rejected for size would
        // come back as this resort's own roster, with no sign that the list had changed
        return "redirect:/EmailForm?resort=" + queryParameter(queryString, "resort")
             + "&ID=" + queryParameter(queryString, "fromPatrollerId")
             + "&audience=" + queryParameter(queryString, "audience")
             + "&attachmentsTooLarge=true";
    }

    /**
     * Reads one value straight out of the query string. request.getParameter would be the obvious
     * way, but on a request whose multipart body failed to parse the container rethrows that same
     * parse failure the moment anything asks for a parameter - which would take this handler down
     * with it, silently, and put the user right back on the raw 413.
     */
    private String queryParameter(String queryString, String name) {
        if (queryString == null) {
            return "";
        }
        for (String pair : queryString.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return reencode(pair.substring(equals + 1));
            }
        }
        return "";
    }

    /** Decode then re-encode, so nothing in the URL can smuggle its own parameters into the redirect. */
    private String reencode(String rawValue) {
        try {
            String decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            return URLEncoder.encode(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            return "";
        }
    }
}
