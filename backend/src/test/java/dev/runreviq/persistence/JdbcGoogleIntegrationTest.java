package dev.runreviq.persistence;

import static org.junit.jupiter.api.Assertions.*;
import dev.runreviq.auth.IdentityProvider;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="RUNREVIQ_JDBC_TEST_DIR",matches=".+")
@Import(JdbcGoogleIntegrationTest.Provider.class)
class JdbcGoogleIntegrationTest {
    @TestConfiguration static class Provider {
        @Bean @Primary IdentityProvider verifiedTestProvider() {return (c,v,n,config,uri)->new IdentityProvider.VerifiedIdentity("test-subject",Instant.now().plusSeconds(3600));}
    }
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("runreviq.auth.mode",()->"google");r.add("runreviq.auth.config-path",()->JdbcIntegrationTest.dir().resolve("google.json").toString());
        r.add("runreviq.drafts.enabled",()->"true");r.add("runreviq.drafts.config-path",()->JdbcIntegrationTest.dir().resolve("drafts.json").toString());
        r.add("runreviq.database.enabled",()->"true");r.add("runreviq.database.config-path",()->JdbcIntegrationTest.dir().resolve("readers.json").toString());
    }
    @Test void googleIdentityRoleAndOriginGuardRealDatabaseReads() throws Exception {
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String base="http://127.0.0.1:"+port;
        var start=client.send(HttpRequest.newBuilder(URI.create(base+"/auth/google/login")).build(),HttpResponse.BodyHandlers.ofString());
        String state=Arrays.stream(URI.create(start.headers().firstValue("location").orElseThrow()).getRawQuery().split("&")).filter(p->p.startsWith("state=")).findFirst().orElseThrow();
        var callback=client.send(HttpRequest.newBuilder(URI.create(base+"/auth/google/callback?"+state+"&code=test")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(303,callback.statusCode());
        String body="{\"service\":\"identity-service\",\"software_version\":\"2.4.0\",\"environment\":\"production\",\"key_rotation_enabled\":true}";
        for(boolean origin:List.of(false,true)) {
            var request=HttpRequest.newBuilder(URI.create(base+"/v1/select")).POST(HttpRequest.BodyPublishers.ofString(body));
            if(origin)request.header("Origin","http://127.0.0.1:8081").header("X-Runreviq-Request","1");
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(origin?200:403,response.statusCode());assertFalse(response.body().contains("SEC-021"));
        }
        var hidden=client.send(HttpRequest.newBuilder(URI.create(base+"/v1/documents/SEC-021/revisions/1")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(404,hidden.statusCode());
        String draftId=null;
        for(boolean origin:List.of(false,true)) {
            var request=HttpRequest.newBuilder(URI.create(base+"/v1/drafts?filename=google.md")).header("Content-Type","text/markdown").POST(HttpRequest.BodyPublishers.ofString("# Source\nInspect keys."));
            if(origin)request.header("Origin","http://127.0.0.1:8081").header("X-Runreviq-Request","1");
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(origin?201:403,response.statusCode(),response.body());
            if(origin)draftId=StoredEvidence.JSON.readTree(response.body()).get("id").asString();
        }
        String draftPath=base+"/v1/drafts/"+draftId;
        assertEquals(200,client.send(HttpRequest.newBuilder(URI.create(draftPath)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        Path config=JdbcIntegrationTest.dir().resolve("google.json");String saved=Files.readString(config);
        try {
            Files.writeString(config,saved.replace("\"test-subject\": \"platform\"",""));
            var revoked=client.send(HttpRequest.newBuilder(URI.create(base+"/v1/documents/RB-042/revisions/1")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,revoked.statusCode());
            assertEquals(403,client.send(HttpRequest.newBuilder(URI.create(draftPath)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {Files.writeString(config,saved);}
        var logout=HttpRequest.newBuilder(URI.create(base+"/auth/logout")).header("Origin","http://127.0.0.1:8081").header("X-Runreviq-Request","1").POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(204,client.send(logout,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(draftPath)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
