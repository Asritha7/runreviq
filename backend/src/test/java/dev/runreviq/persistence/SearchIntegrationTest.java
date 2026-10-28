package dev.runreviq.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="RUNREVIQ_JDBC_TEST_DIR",matches=".+")
class SearchIntegrationTest {
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){JdbcIntegrationTest.properties(r);}
    HttpResponse<String> request(String role,String body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/search")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(role!=null)b.header("Authorization","Bearer "+role+"-test-"+"x".repeat(40));
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static ObjectNode body(){return (ObjectNode)StoredEvidence.JSON.readTree("{\"question\":\"intermittent 401 errors\",\"service\":\"identity-service\",\"software_version\":\"2.4.0\",\"environment\":\"production\",\"key_rotation_enabled\":true}");}
    @TestFactory Stream<DynamicTest> frozenFullResponseParity()throws Exception{
        var fixture=StoredEvidence.JSON.readTree(getClass().getResourceAsStream("/search-parity.json"));var tests=new ArrayList<DynamicTest>();
        for(var c:fixture.get("cases"))tests.add(DynamicTest.dynamicTest(c.get("id").asString(),()->{
            var b=(ObjectNode)c.get("context").deepCopy();String role=b.remove("role").asString();b.put("question",c.get("question").asString());
            var r=request(role,b.toString());assertEquals(200,r.statusCode(),r.body());var actual=StoredEvidence.JSON.readTree(r.body());assertEquals(c.get("expected"),actual);
            assertEquals("no-store",r.headers().firstValue("cache-control").orElseThrow());assertTrue(actual.get("passages").size()<=5);
            for(var p:actual.get("passages")){
                var source=JdbcIntegrationTest.repository().revision(role,p.get("document_id").asString(),p.get("revision").asInt());assertNotNull(source);
                String content=(String)source.get("content");var lines=StoredEvidence.lines(content);
                assertEquals(String.join("\n",lines.subList(p.get("start_line").asInt()-1,p.get("end_line").asInt())),p.get("quote").asString());
                assertEquals(PythonJson.sha256(content),p.get("source_sha256").asString());
            }
        }));return tests.stream();
    }
    @Test void strictValidationBeforeDatabaseAndNoCallerRole()throws Exception{
        assertEquals(401,request(null,"{").statusCode());
        var invalid=new ArrayList<String>(List.of("{}","[]","{"," ".repeat(4097),body()+" {}"));
        for(String q:List.of(""," ","x".repeat(501)," ".repeat(500)+"x"))invalid.add(body().put("question",q).toString());
        invalid.add(body().put("role","security").toString());invalid.add(body().put("question",42).toString());invalid.add(body().put("http_status",503).toString());
        invalid.add(body().toString().replace("\"question\":","\"question\":\"JWKS\",\"question\":"));
        invalid.add(body().put("key_rotation_enabled","true").toString());invalid.add(body().put("http_status",true).toString());
        Path path=JdbcIntegrationTest.dir().resolve("readers.json");String saved=Files.readString(path);
        try{Files.writeString(path,"private invalid configuration");for(String b:invalid)assertEquals(422,request("platform",b).statusCode(),b);
            var r=request("platform",body().toString());assertEquals(503,r.statusCode());assertEquals("{\"detail\":\"Evidence store unavailable\"}",r.body());
        }finally{Files.writeString(path,saved);}
    }
    @Test void currentRevisionAclWithdrawalAndNoRestrictedLeak()throws Exception{
        var before=StoredEvidence.JSON.readTree(request("platform",body().toString()).body());assertEquals(2,before.get("passages").get(0).get("revision").asInt());
        try(var c=JdbcIntegrationTest.admin();var q=c.createStatement()){
            try{
                q.execute("UPDATE opslens.documents SET allowed_roles=ARRAY['security'] WHERE id='RB-042'");
                var hidden=request("platform",body().toString());assertEquals(200,hidden.statusCode());assertFalse(hidden.body().contains("RB-042"));assertFalse(hidden.body().contains("Current café"));
                q.execute("UPDATE opslens.documents SET allowed_roles=ARRAY['platform','security'] WHERE id='RB-042'");
                q.execute("INSERT INTO opslens.document_withdrawals(document_id,revision,actor,reason) VALUES ('RB-042',2,'search-test','withdrawn')");
                var withdrawn=request("platform",body().toString());assertEquals(200,withdrawn.statusCode());assertFalse(withdrawn.body().contains("RB-042"));
            }finally{q.execute("DELETE FROM opslens.document_withdrawals WHERE document_id='RB-042' AND revision=2");q.execute("UPDATE opslens.documents SET allowed_roles=ARRAY['platform','security'] WHERE id='RB-042'");}
        }
        var injection=request("platform",body().put("question","NORTHSTAR-RESTRICTED-CANARY' | !:*); DROP TABLE opslens.documents; --").toString());
        assertEquals(200,injection.statusCode());assertFalse(injection.body().contains("SEC-021"));assertFalse(injection.body().contains("NORTHSTAR-RESTRICTED-CANARY"));
    }
    @Test void corruptVisibleSourceFailsClosedHiddenSourceIsNeverRead()throws Exception{
        try(var c=JdbcIntegrationTest.admin();var q=c.createStatement()){
            c.setAutoCommit(false);q.execute("INSERT INTO opslens.documents VALUES ('BROKEN-999',1,ARRAY['security'])");q.execute("INSERT INTO opslens.document_versions(document_id,revision,checksum,metadata,content) VALUES ('BROKEN-999',1,'bad','{}','private search source')");c.commit();
            try{assertEquals(200,request("platform",body().toString()).statusCode());var r=request("security",body().toString());assertEquals(503,r.statusCode());assertEquals("{\"detail\":\"Evidence store unavailable\"}",r.body());}
            finally{q.execute("UPDATE opslens.documents SET allowed_roles=ARRAY[]::text[] WHERE id='BROKEN-999'");c.commit();}
        }
    }
    @Test void searchSnapshotSurvivesConcurrentRevocationButNextRequestCannotSeeSource()throws Exception{
        var context=new dev.runreviq.domain.SelectionContext("platform","identity-service","2.4.0","production",true,401);
        try(var admin=JdbcIntegrationTest.admin();var update=admin.createStatement()){
            try{
                JdbcIntegrationTest.readers().read("platform",c->{
                    var sources=EvidenceRepository.snapshot(c);
                    update.execute("UPDATE opslens.documents SET allowed_roles=ARRAY['security'] WHERE id='RB-042'");
                    var response=SearchRepository.search(c,sources,"intermittent 401 errors",context,null);
                    assertTrue(((List<?>)response.get("recommended_documents")).contains("RB-042"));
                    try(var q=c.createStatement();var r=q.executeQuery("SHOW transaction_read_only")){r.next();assertEquals("on",r.getString(1));}
                    return null;
                });
                assertFalse(request("platform",body().toString()).body().contains("RB-042"));
            }finally{update.execute("UPDATE opslens.documents SET allowed_roles=ARRAY['platform','security'] WHERE id='RB-042'");}
        }
    }
    @Test void removedRlsFailsClosedOverSearchHttp()throws Exception{
        try(var c=JdbcIntegrationTest.admin();var q=c.createStatement()){
            try{q.execute("ALTER TABLE opslens.documents DISABLE ROW LEVEL SECURITY");assertEquals(503,request("platform",body().toString()).statusCode());}
            finally{q.execute("ALTER TABLE opslens.documents ENABLE ROW LEVEL SECURITY");}
        }
    }
}
