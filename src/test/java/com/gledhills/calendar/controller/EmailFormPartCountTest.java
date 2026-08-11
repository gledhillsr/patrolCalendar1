package com.gledhills.calendar.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bulk email selects one recipient per {@code patrollerIds} part, so the POST carries a part per
 * addressee. Tomcat's maxPartCount caps that, and blowing the cap is a 413 the user sees raw -
 * multipart parsing fails inside DispatcherServlet, before EmailFormController is ever reached.
 *
 * MockMvc calls the controller directly and never parses a multipart body, so only a real Tomcat
 * can catch either of these. The requests are unauthenticated on purpose: reaching the redirect to
 * MemberLogin is proof enough that the body parsed, and it keeps the test off the mail path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailFormPartCountTest {

    /** Comfortably past Tomcat 11's default maxPartCount of 50. */
    private static final int RECIPIENT_COUNT = 400;

    /** Past spring.servlet.multipart.max-request-size, so the body genuinely cannot be buffered. */
    private static final int OVERSIZED_ATTACHMENT_BYTES = 11 * 1024 * 1024;

    private static final String BOUNDARY = "----PatrolCalendarTestBoundary";

    @LocalServerPort
    private int port;

    @Test
    void bulkEmailToAWholeRosterIsNotRejectedAsTooLarge() throws Exception {
        HttpResponse<String> response = postBulkEmail(RECIPIENT_COUNT, new byte[0]);

        assertNotEquals(413, response.statusCode(),
            "A bulk email to " + RECIPIENT_COUNT + " recipients was rejected with 413 before it "
            + "reached the controller - raise server.tomcat.max-part-count / max-parameter-count");
        assertTrue(response.statusCode() == 302 || response.statusCode() == 200,
            "expected the request to reach the controller, got " + response.statusCode());
    }

    /**
     * An upload too big to buffer still cannot reach the controller, so the friendly "too large"
     * message has to come from UploadTooLargeAdvice. Before it existed the user got a raw 413 here.
     */
    @Test
    void anOversizedAttachmentBouncesBackToTheFormWithAMessage() throws Exception {
        HttpResponse<String> response =
            postBulkEmail(1, new byte[OVERSIZED_ATTACHMENT_BYTES]);

        assertEquals(302, response.statusCode(),
            "an oversized attachment should redirect back to EmailForm, not fail with a raw status");
        String location = response.headers().firstValue("Location").orElse("");
        assertTrue(location.contains("attachmentsTooLarge=true"),
            "expected a redirect carrying the attachment error, got: " + location);
        assertTrue(location.contains("resort=Sample"),
            "the resort has to survive the rejected body via the query string, got: " + location);
    }

    private HttpResponse<String> postBulkEmail(int recipientCount, byte[] attachment)
            throws Exception {
        List<byte[]> chunks = new ArrayList<>();
        chunks.add(textPart("subject", "Season kickoff"));
        chunks.add(textPart("message", "Please read the updated shift policy."));
        for (int i = 0; i < recipientCount; i++) {
            chunks.add(textPart("patrollerIds", String.valueOf(100000 + i)));
        }
        // an untouched file input still posts one empty part
        chunks.add(filePart("attachments", attachment.length == 0 ? "" : "trail-map.pdf", attachment));
        chunks.add(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        int total = chunks.stream().mapToInt(chunk -> chunk.length).sum();
        byte[] body = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, body, offset, chunk.length);
            offset += chunk.length;
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port
                            + "/calendar-1/EmailForm?resort=Sample&fromPatrollerId=123456"))
            // BotBlockingFilter treats a missing User-Agent as a crawler
            .header("User-Agent", "Mozilla/5.0 (Macintosh) EmailFormPartCountTest")
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private byte[] textPart(String name, String value) {
        return ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] filePart(String name, String filename, byte[] content) {
        byte[] header = ("--" + BOUNDARY + "\r\n"
                         + "Content-Disposition: form-data; name=\"" + name
                         + "\"; filename=\"" + filename + "\"\r\n"
                         + "Content-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8);
        byte[] trailer = "\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] part = new byte[header.length + content.length + trailer.length];
        System.arraycopy(header, 0, part, 0, header.length);
        System.arraycopy(content, 0, part, header.length, content.length);
        System.arraycopy(trailer, 0, part, header.length + content.length, trailer.length);
        return part;
    }
}
