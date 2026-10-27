package dev.runreviq.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoAuthHttpTest {
    static final Path CONFIG;
    static {
        try {CONFIG=Files.createTempFile("runreviq-demo-test-", ".json");save("platform");}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static void save(String role) throws Exception {
        Files.writeString(CONFIG,"[{\"subject\":\"demo-1\",\"role\":\""+role+"\",\"token\":\""+"a".repeat(43)+"\"}]");
        Files.setPosixFilePermissions(CONFIG,PosixFilePermissions.fromString("rw-------"));
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("runreviq.auth.mode",()->"demo");r.add("runreviq.auth.config-path",()->CONFIG.toString());
    }
    @LocalServerPort int port;
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void reset() throws Exception {save("platform");}
    @AfterAll static void clean() throws Exception {Files.deleteIfExists(CONFIG);}
    HttpResponse<String> get(String path, String token) throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(token!=null)r.header("Authorization","Bearer "+token);
        return client.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void demoIdentityRequiresValidBearerAndReloadsRole() throws Exception {
        var missing=get("/v1/me",null);assertEquals(401,missing.statusCode());
        assertEquals("Bearer",missing.headers().firstValue("www-authenticate").orElseThrow());
        assertEquals(401,get("/v1/me","bad").statusCode());
        var me=get("/v1/me","a".repeat(43));assertEquals(200,me.statusCode());assertTrue(me.body().contains("platform"));
        assertTrue(me.headers().allValues("set-cookie").isEmpty());
        save("intern");assertTrue(get("/v1/me","a".repeat(43)).body().contains("intern"));
        Files.delete(CONFIG);assertEquals(503,get("/v1/me","a".repeat(43)).statusCode());
    }
    @Test void googleAndDataEndpointsRemainUnavailableInDemoMode() throws Exception {
        assertEquals(403,get("/auth/google/login",null).statusCode());
        assertEquals(403,get("/auth/session","a".repeat(43)).statusCode());
        assertEquals(403,get("/v1/submissions","a".repeat(43)).statusCode());
    }
}
