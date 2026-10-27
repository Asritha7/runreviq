package dev.runreviq.auth;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class LocalIdentityTest {
    @TempDir Path directory;
    final JsonMapper json=JsonMapper.builder().build();
    Path write(String content) throws Exception {
        var path=directory.resolve("private.json"); Files.writeString(path,content);
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")); return path;
    }
    String keys(String role) { return json.writeValueAsString(List.of(Map.of("subject","demo-1","role",role,"token","a".repeat(43)))); }
    AuthService demo(Path path) { return new AuthService("demo","http://127.0.0.1:8081",path,Clock.systemUTC(),null); }
    MockHttpServletRequest bearer(String token) { var r=new MockHttpServletRequest("GET","/v1/me");r.addHeader("Authorization","Bearer "+token);return r; }
    @Test void demoCredentialsReloadAndCannotUseCookiesOrClaimedRole() throws Exception {
        var path=write(keys("platform"));var service=demo(path);var request=bearer("a".repeat(43));request.addHeader("X-Role","security");
        assertEquals("platform",service.principal(request,true).role());
        write(keys("intern"));assertEquals("intern",service.principal(request,true).role());
        write(keys("intern").replace("a".repeat(43),"b".repeat(43)));
        assertEquals(401,assertThrows(AuthFailure.class,()->service.principal(request,true)).status());
        var cookie=new MockHttpServletRequest();cookie.setCookies(new Cookie(AuthService.SESSION_COOKIE,"a".repeat(43)));
        assertEquals(401,assertThrows(AuthFailure.class,()->service.principal(cookie,true)).status());
        Files.delete(path);assertEquals(503,assertThrows(AuthFailure.class,()->service.principal(request,true)).status());
    }
    @Test void duplicateIdentityTokensUnknownRolesAndPermissionsRejected() throws Exception {
        var path=write(keys("platform"));
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(AuthFailure.class,()->demo(path));
        write(keys("admin"));assertThrows(AuthFailure.class,()->demo(path));
        write("["+keys("platform").substring(1,keys("platform").length()-1)+","+keys("platform").substring(1));
        assertThrows(AuthFailure.class,()->demo(path));
        write(keys("platform"));var link=directory.resolve("link.json");Files.createSymbolicLink(link,path);
        assertThrows(AuthFailure.class,()->demo(link));
    }
    @Test void duplicateConfigurationFieldsFailClosed() throws Exception {
        var path=write("{\"client_id\":\"first\",\"client_id\":\"second\",\"client_secret\":\"test\",\"allowed_subjects\":{}}");
        assertEquals(503,assertThrows(AuthFailure.class,()->PrivateConfiguration.google(path)).status());
    }
    @Test void invalidModeAndNonLoopbackOriginsFailAtStartup() throws Exception {
        var path=write(keys("platform"));
        for(String origin:List.of("https://evil.example","http://localhost:8081","http://127.0.0.1:8081/","http://127.0.0.1:8081?x=1"))
            assertThrows(IllegalArgumentException.class,()->new AuthService("demo",origin,path,Clock.systemUTC(),null));
        assertThrows(IllegalArgumentException.class,()->new AuthService("auto","http://127.0.0.1:8081",path,Clock.systemUTC(),null));
    }
    @Test void pendingFlowsAreBoundedAndExpiredCapacityIsReclaimed() throws Exception {
        var path=write("{\"client_id\":\"test\",\"client_secret\":\"test\",\"allowed_subjects\":{}}");
        var clock=new GoogleAuthHttpTest.MutableClock();
        var service=new AuthService("google","http://127.0.0.1:8081",path,clock,null);
        for(int i=0;i<1000;i++) service.start();
        assertEquals(503,assertThrows(AuthFailure.class,service::start).status());
        clock.now=clock.now.plusSeconds(600); assertEquals(303,service.start().getStatusCode().value());
    }
}
