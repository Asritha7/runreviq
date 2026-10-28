package dev.runreviq.api;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoundaryTest {
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> request(String path, boolean post) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (post) builder.POST(HttpRequest.BodyPublishers.ofString("{}"));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void healthIsPublicAndOperationalRoutesAreDenied() throws Exception {
        var health = request("/health", false);
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("disabled"));
        assertTrue(health.headers().allValues("set-cookie").isEmpty());
        for (String path : new String[]{"/", "/search", "/documents", "/auth/google/login", "/actuator/env", "/v1/submissions", "/v1/publications/queue", "/v1/reviewer-access", "/v1/publisher-access", "/v1/publication-management/documents", "/v1/drafts", "/v1/drafts/00000000-0000-0000-0000-000000000001", "/v1/drafts/00000000-0000-0000-0000-000000000001/review"}) {
            assertEquals(403, request(path,false).statusCode(),path);
            assertEquals(403, request(path,true).statusCode(),path);
        }
    }
}
