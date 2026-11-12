package org.example.tokenclient;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoopbackCaptchaTest {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test void callbackRejectsOtherOriginsMalformedProofAndWrongMethods() throws Exception {
        try (var challenge = new LoopbackCaptcha()) {
            URI page = challenge.page();
            assertEquals("127.0.0.1", page.getHost());
            assertEquals(403, post(page.resolve("complete"), "https://untrusted.invalid", KuroAuthServiceTest.PROOF).statusCode());
            assertEquals(403, post(page.resolve("complete"), null, KuroAuthServiceTest.PROOF).statusCode());
            assertEquals(400, post(page.resolve("complete"), origin(page), "{}").statusCode());
            assertEquals(413, post(page.resolve("complete"), origin(page), "x".repeat(16385)).statusCode());
            assertEquals(405, client.send(HttpRequest.newBuilder(page.resolve("complete")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, post(page.resolve("../complete"), origin(page), KuroAuthServiceTest.PROOF).statusCode());
            assertNull(challenge.proof());
            assertEquals(200, post(page.resolve("complete"), origin(page), KuroAuthServiceTest.PROOF).statusCode());
            assertEquals(KuroAuthServiceTest.PROOF, challenge.proof());
        }
    }

    @Test void cancellationShutsDownListenerAndDiscardsLateCallbacks() throws Exception {
        var challenge = new LoopbackCaptcha();
        URI page = challenge.page();
        assertEquals(200, client.send(HttpRequest.newBuilder(page).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        challenge.close();
        assertDoesNotThrow(challenge::close);
        assertThrows(IOException.class, () -> post(page.resolve("complete"), origin(page), KuroAuthServiceTest.PROOF));
        assertNull(challenge.proof());
    }

    @Test void statusIsBoundToChallengeAndDoesNotOverwriteCompletedProof() throws Exception {
        try (var challenge = new LoopbackCaptcha()) {
            URI page = challenge.page();
            assertEquals(200, post(page.resolve("status"), origin(page), "sdk-error").statusCode());
            assertEquals("sdk-error", challenge.status());
            post(page.resolve("status"), origin(page), "initializing");
            assertEquals("sdk-error", challenge.status(), "Late loading updates must not hide failure");
            post(page.resolve("status"), origin(page), "ready");
            post(page.resolve("status"), origin(page), "initializing");
            assertEquals("ready", challenge.status(), "Late loading updates must not hide readiness");
            assertEquals(400, post(page.resolve("status"), origin(page), "arbitrary message").statusCode());
            post(page.resolve("complete"), origin(page), KuroAuthServiceTest.PROOF);
            post(page.resolve("status"), origin(page), "closed");
            assertEquals("complete", challenge.status());
        }
    }

    private HttpResponse<String> post(URI target, String origin, String body) throws Exception {
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) request.header("Origin", origin);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String origin(URI page) { return "http://" + page.getAuthority(); }
}
