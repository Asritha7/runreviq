package dev.runreviq.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GoogleAuthHttpTest.Fakes.class)
class GoogleAuthHttpTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path CONFIG;
    static {
        try { CONFIG = Files.createTempFile("runreviq-java-test-", ".json"); save(Map.of("user-1","platform"),"test-client"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    static final MutableClock CLOCK = new MutableClock();
    static final AtomicInteger CALLS = new AtomicInteger();
    static String verifier, nonce, callback;
    static boolean fail;
    static long identityLifetime = 7200;
    @TestConfiguration static class Fakes {
        @Bean @Primary Clock testClock() { return CLOCK; }
        @Bean @Primary IdentityProvider fakeProvider() { return (code,v,n,c,redirect) -> {
            CALLS.incrementAndGet(); verifier=v; nonce=n; callback=redirect;
            if (fail) throw new IllegalArgumentException("sensitive-provider-response");
            return new IdentityProvider.VerifiedIdentity("user-1",CLOCK.instant().plusSeconds(identityLifetime));
        }; }
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("runreviq.auth.mode", () -> "google");
        r.add("runreviq.auth.origin", () -> "http://127.0.0.1:8081");
        r.add("runreviq.auth.config-path", () -> CONFIG.toString());
    }
    @LocalServerPort int port;
    HttpClient client;
    CookieManager cookies;
    static void save(Map<String,String> roles, String clientId) throws Exception {
        Files.writeString(CONFIG, JSON.writeValueAsString(Map.of("client_id",clientId,"client_secret","offline-test-secret","allowed_subjects",roles)));
        Files.setPosixFilePermissions(CONFIG,PosixFilePermissions.fromString("rw-------"));
    }
    @BeforeEach void reset() throws Exception {
        save(Map.of("user-1","platform"),"test-client"); fail=false; identityLifetime=7200; CALLS.set(0);
        CLOCK.now=Instant.parse("2026-01-01T00:00:00Z");
        cookies = new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        client=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @AfterAll static void cleanup() throws Exception { Files.deleteIfExists(CONFIG); }
    HttpResponse<String> request(String path, String method, Map<String,String> headers) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        headers.forEach(b::header); b.method(method,HttpRequest.BodyPublishers.noBody());
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path) throws Exception { return request(path,"GET",Map.of()); }
    Map<String,String> start() throws Exception {
        var response=get("/auth/google/login"); assertEquals(303,response.statusCode());
        assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
        var result=new HashMap<String,String>();
        for (String pair:URI.create(response.headers().firstValue("location").orElseThrow()).getRawQuery().split("&")) {
            var kv=pair.split("=",2); result.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));
        }
        return result;
    }
    HttpResponse<String> complete(Map<String,String> flow) throws Exception {
        return get("/auth/google/callback?state="+flow.get("state")+"&code=test-code");
    }
    String sessionCookie() { return cookies.getCookieStore().getCookies().stream()
            .filter(c->c.getName().equals(AuthService.SESSION_COOKIE)).findFirst().orElseThrow().getValue(); }
    @Test void loginBindsNoncePkceAndOpaqueSession() throws Exception {
        assertEquals("google",JSON.readTree(get("/auth/config").body()).get("mode").asString());
        var flow=start();
        assertEquals("openid",flow.get("scope")); assertEquals("S256",flow.get("code_challenge_method"));
        assertFalse(flow.containsKey("client_secret"));
        var response=complete(flow);
        assertEquals("/",response.headers().firstValue("location").orElseThrow());
        assertTrue(response.headers().allValues("set-cookie").stream().anyMatch(c->c.contains(AuthService.SESSION_COOKIE) && c.contains("HttpOnly") && c.contains("SameSite=Lax")));
        assertEquals(43,sessionCookie().length()); assertFalse(sessionCookie().contains("."));
        assertEquals(flow.get("nonce"),nonce);
        assertEquals(flow.get("code_challenge"),Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(AuthService.digest(verifier))));
        assertEquals("http://127.0.0.1:8081/auth/google/callback",callback);
        assertEquals(JSON.readTree("{\"subject\":\"user-1\",\"role\":\"platform\"}"),JSON.readTree(get("/v1/me").body()));
        assertEquals(403,get("/v1/documents/restricted/revisions/1").statusCode());
    }
    @Test void missingBindingAndReplayAreRejectedBeforeExchange() throws Exception {
        var flow=start(); cookies.getCookieStore().removeAll();
        assertEquals("/?login=failed",complete(flow).headers().firstValue("location").orElseThrow());
        complete(flow); assertEquals(0,CALLS.get()); assertEquals(401,get("/v1/me").statusCode());
    }
    @Test void successfulCallbackCannotBeReplayed() throws Exception {
        var flow=start(); complete(flow); complete(flow); assertEquals(1,CALLS.get());
    }
    @Test void duplicateQueryOrProviderDenialConsumesFlow() throws Exception {
        var flow=start();
        assertEquals("/?login=failed",get("/auth/google/callback?state="+flow.get("state")+"&state=other&code=x").headers().firstValue("location").orElseThrow());
        complete(flow); assertEquals(0,CALLS.get());
        flow=start(); get("/auth/google/callback?state="+flow.get("state")+"&error=access_denied");
        complete(flow); assertEquals(0,CALLS.get());
    }
    @Test void providerErrorsDoNotLeak() throws Exception {
        fail=true; var response=complete(start());
        assertEquals("/?login=failed",response.headers().firstValue("location").orElseThrow());
        assertFalse(response.body().contains("sensitive")); assertEquals(401,get("/v1/me").statusCode());
    }
    @Test void roleRemovalAndChangeApplyWithoutLogin() throws Exception {
        complete(start()); save(Map.of("user-1","intern"),"test-client");
        assertEquals("intern",JSON.readTree(get("/v1/me").body()).get("role").asString());
        save(Map.of(),"test-client"); assertEquals(403,get("/v1/me").statusCode());
        var session=get("/auth/session"); assertEquals(200,session.statusCode());
        assertTrue(JSON.readTree(session.body()).get("role").isNull());
    }
    @Test void unassignedUserCanOnlyInspectOwnIdentity() throws Exception {
        save(Map.of(),"test-client"); complete(start());
        assertEquals(200,get("/auth/session").statusCode()); assertEquals(403,get("/v1/me").statusCode());
    }
    @Test void roleHeadersAndBearerCannotBypassGoogleMode() throws Exception {
        assertEquals(401,request("/v1/me","GET",Map.of("Authorization","Bearer "+"x".repeat(43),"X-Role","security")).statusCode());
    }
    @Test void logoutNeedsOriginAndMarkerAndRevokesCopiedCookie() throws Exception {
        complete(start()); String old=sessionCookie();
        for (var headers:List.of(Map.<String,String>of(),Map.of("Origin","https://evil.example","X-Runreviq-Request","1"),Map.of("Origin","http://127.0.0.1:8081")))
            assertEquals(403,request("/auth/logout","POST",headers).statusCode());
        assertEquals(200,get("/v1/me").statusCode());
        assertEquals(204,request("/auth/logout","POST",Map.of("Origin","http://127.0.0.1:8081","X-Runreviq-Request","1")).statusCode());
        assertEquals(401,request("/v1/me","GET",Map.of("Cookie",AuthService.SESSION_COOKIE+"="+old)).statusCode());
    }
    @Test void repeatLoginInvalidatesOldSession() throws Exception {
        complete(start()); String old=sessionCookie(); complete(start());
        assertNotEquals(old,sessionCookie()); cookies.getCookieStore().removeAll();
        assertEquals(401,request("/v1/me","GET",Map.of("Cookie",AuthService.SESSION_COOKIE+"="+old)).statusCode());
    }
    @Test void flowAndSessionExpireAtAbsoluteDeadlines() throws Exception {
        var flow=start(); CLOCK.now=CLOCK.now.plusSeconds(600); complete(flow); assertEquals(0,CALLS.get());
        complete(start()); assertEquals(200,get("/v1/me").statusCode());
        CLOCK.now=CLOCK.now.plusSeconds(3600); assertEquals(401,get("/v1/me").statusCode());
    }
    @Test void sessionCannotOutliveProviderIdentity() throws Exception {
        identityLifetime=30; complete(start());
        assertEquals(200,get("/v1/me").statusCode());
        CLOCK.now=CLOCK.now.plusSeconds(30); assertEquals(401,get("/v1/me").statusCode());
    }
    @Test void wrongBrowserBindingConsumesFlow() throws Exception {
        var flow=start();
        cookies.getCookieStore().removeAll();
        var wrong=request("/auth/google/callback?state="+flow.get("state")+"&code=test-code","GET",
                Map.of("Cookie",AuthService.FLOW_COOKIE+"=wrong"));
        assertEquals("/?login=failed",wrong.headers().firstValue("location").orElseThrow());
        assertEquals(0,CALLS.get());
    }
    @Test void changedClientInvalidatesFlowAndSession() throws Exception {
        complete(start()); var flow=start(); save(Map.of("user-1","platform"),"different-client");
        assertEquals(401,get("/v1/me").statusCode()); complete(flow); assertEquals(1,CALLS.get());
    }
    @Test void missingUnsafeAndMalformedConfigFailClosed() throws Exception {
        complete(start()); Files.setPosixFilePermissions(CONFIG,PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(503,get("/v1/me").statusCode());
        Files.delete(CONFIG); assertEquals(503,get("/auth/config").statusCode());
        save(Map.of("user-1","platform"),"test-client"); Files.writeString(CONFIG,"{broken");
        var result=get("/auth/session"); assertEquals(503,result.statusCode()); assertFalse(result.body().contains("broken"));
    }
    @Test void hostileHostRejectedAndCorsNeverGranted() throws Exception {
        // Raw socket is used because HttpClient forbids setting Host.
        try (var socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write("GET /auth/config HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertTrue(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).startsWith("HTTP/1.1 400"));
        }
        var response=request("/v1/me","OPTIONS",Map.of("Origin","https://evil.example","Access-Control-Request-Method","GET"));
        assertEquals(403,response.statusCode()); assertTrue(response.headers().allValues("access-control-allow-origin").isEmpty());
    }
}
