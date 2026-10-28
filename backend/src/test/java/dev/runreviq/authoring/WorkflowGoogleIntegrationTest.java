package dev.runreviq.authoring;

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
@Import(WorkflowGoogleIntegrationTest.Provider.class)
class WorkflowGoogleIntegrationTest {
    @TestConfiguration static class Provider {@Bean @Primary IdentityProvider provider(){return (c,v,n,config,uri)->new IdentityProvider.VerifiedIdentity("test-subject",Instant.now().plusSeconds(3600));}}
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        WorkflowIntegrationTest.properties(r);r.add("runreviq.auth.mode",()->"google");r.add("runreviq.auth.config-path",()->WorkflowIntegrationTest.dir().resolve("google.json").toString());
    }
    @Test void googleWorkflowWritesRequireOriginAndCurrentOperatorAssignment()throws Exception {
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var client=HttpClient.newBuilder().cookieHandler(cookies).build();String base="http://127.0.0.1:"+port;
        var start=client.send(HttpRequest.newBuilder(URI.create(base+"/auth/google/login")).build(),HttpResponse.BodyHandlers.ofString());
        String state=Arrays.stream(URI.create(start.headers().firstValue("location").orElseThrow()).getRawQuery().split("&")).filter(p->p.startsWith("state=")).findFirst().orElseThrow();
        assertEquals(303,client.send(HttpRequest.newBuilder(URI.create(base+"/auth/google/callback?"+state+"&code=test")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var id=WorkflowIntegrationTest.submitted(DraftIntegrationTest.owner());String decision="{\"decision\":\"approved\",\"comment\":\"Google reviewer checked source\"}";
        for(boolean origin:List.of(false,true)){
            var b=HttpRequest.newBuilder(URI.create(base+"/v1/submissions/"+id+"/decision")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(decision));
            if(origin)b.header("Origin","http://127.0.0.1:8081").header("X-Runreviq-Request","1");assertEquals(origin?200:403,client.send(b.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        String body="{\"allowed_roles\":[\"platform\"],\"claims\":"+WorkflowIntegrationTest.claims()+",\"metadata_verified\":true}";
        Path config=WorkflowIntegrationTest.dir().resolve("publishers.json");String saved=Files.readString(config);
        var publish=HttpRequest.newBuilder(URI.create(base+"/v1/publications/"+id)).header("Origin","http://127.0.0.1:8081").header("X-Runreviq-Request","1").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try{Files.writeString(config,"{\"google\":[],\"demo\":[\"test-intern\"]}");assertEquals(403,client.send(publish,HttpResponse.BodyHandlers.ofString()).statusCode());}finally{Files.writeString(config,saved);}
        var published=client.send(publish,HttpResponse.BodyHandlers.ofString());assertEquals(200,published.statusCode(),published.body());
        String doc=DraftRepository.JSON.readTree(published.body()).get("document_id").asString();
        var withdrawal=HttpRequest.newBuilder(URI.create(base+"/v1/publication-management/documents/"+doc+"/withdraw")).POST(HttpRequest.BodyPublishers.ofString("{\"expected_revision\":1,\"reason\":\"Retired\"}")).build();
        assertEquals(403,client.send(withdrawal,HttpResponse.BodyHandlers.ofString()).statusCode());
        var logout=HttpRequest.newBuilder(URI.create(base+"/auth/logout")).header("Origin","http://127.0.0.1:8081").header("X-Runreviq-Request","1").POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(204,client.send(logout,HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(401,client.send(publish,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
