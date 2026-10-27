package dev.runreviq.authoring;

import static org.junit.jupiter.api.Assertions.*;
import dev.runreviq.auth.PrivateConfiguration;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="RUNREVIQ_JDBC_TEST_DIR",matches=".+")
class DraftIntegrationTest {
    @LocalServerPort int port;
    static Path dir(){return Path.of(System.getenv("RUNREVIQ_JDBC_TEST_DIR"));}
    static DraftConnections connections(){return new DraftConnections(dir().resolve("drafts.json"));}
    static DraftRepository repo(){return new DraftRepository(connections());}
    static String owner(){return DraftRepository.owner("demo",UUID.randomUUID().toString());}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("runreviq.auth.mode",()->"demo");r.add("runreviq.auth.config-path",()->dir().resolve("auth.json").toString());
        r.add("runreviq.drafts.enabled",()->"true");r.add("runreviq.drafts.config-path",()->dir().resolve("drafts.json").toString());
    }
    static Connection admin()throws Exception {
        var n=PrivateConfiguration.readOwnerOnly(dir().resolve("admin.json"));String db=n.get("database").asString();
        if(!db.matches("opslens_java_test_[0-9a-f]{32}")||!db.equals(System.getenv("RUNREVIQ_JDBC_TEST_DATABASE")))throw new IllegalStateException("Not disposable");
        var s=new PGSimpleDataSource();s.setServerNames(new String[]{"127.0.0.1"});s.setPortNumbers(new int[]{55432});s.setDatabaseName(db);s.setUser("opslens_admin");s.setPassword(n.get("password").asString());return s.getConnection();
    }
    static JsonNode metadata()throws Exception {return DraftRepository.JSON.readTree("{\"title\":\"Test\",\"service\":\"identity-service\",\"environment\":\"production\",\"version_min\":\"2.4.0\",\"version_max_exclusive\":\"2.5.0\",\"key_rotation_enabled\":\"none\"}");}
    HttpResponse<String> request(String path,String role,byte[] body,String type)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(role!=null)b.header("Authorization","Bearer "+role+"-test-"+"x".repeat(40));
        if(body!=null){b.POST(HttpRequest.BodyPublishers.ofByteArray(body));if(type!=null)b.header("Content-Type",type);}
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path,String role,String body,String type)throws Exception{return request(path,role,body.getBytes(java.nio.charset.StandardCharsets.UTF_8),type);}
    @Test void pythonDraftAndJavaReviewRoundTrip()throws Exception {
        var fixture=DraftRepository.JSON.readTree(Files.readString(dir().resolve("draft-interop.json")));
        String owner=fixture.get("owner").asString();var expected=fixture.get("draft");var id=UUID.fromString(expected.get("id").asString());
        assertEquals(expected,DraftRepository.JSON.valueToTree(repo().get(owner,id)));
        var metadata=(tools.jackson.databind.node.ObjectNode)expected.get("review").get("metadata").deepCopy();metadata.put("title","Java reviewed title");
        assertEquals(2,repo().save(owner,id,1,metadata).get("revision"));
    }
    @Test void fullHttpOwnerFlowAndImmutableReviewSnapshots()throws Exception {
        var created=post("/v1/drafts?filename=incident.md","platform","# Draft\nInspect keys.","text/markdown");assertEquals(201,created.statusCode(),created.body());
        var d=DraftRepository.JSON.readTree(created.body());String id=d.get("id").asString();assertTrue(d.get("review").isNull());assertTrue(d.get("proposal").get("review_required").asBoolean());
        assertEquals(200,request("/v1/drafts/"+id,"platform",null,null).statusCode());
        var hidden=request("/v1/drafts/"+id,"security",null,null);assertEquals(404,hidden.statusCode());assertFalse(hidden.body().contains("Inspect"));
        assertFalse(request("/v1/drafts","security",null,null).body().contains(id));
        String body="{\"expected_revision\":0,\"metadata\":"+metadata()+"}";
        assertEquals(404,post("/v1/drafts/"+id+"/review","security",body,"application/json").statusCode());
        var saved=post("/v1/drafts/"+id+"/review","platform",body,"application/json");assertEquals(200,saved.statusCode(),saved.body());
        assertEquals(1,DraftRepository.JSON.readTree(saved.body()).get("revision").asInt());
        assertEquals(409,post("/v1/drafts/"+id+"/review","platform",body,"application/json").statusCode());
        assertEquals(200,post("/v1/drafts/"+id+"/review","platform",body.replace("revision\":0","revision\":1"),"application/json").statusCode());
        var loaded=DraftRepository.JSON.readTree(request("/v1/drafts/"+id,"platform",null,null).body());assertEquals(2,loaded.get("review").get("revision").asInt());assertEquals(d.get("content"),loaded.get("content"));assertEquals(d.get("proposal"),loaded.get("proposal"));
        String owner=DraftRepository.owner("demo","test-platform");
        connections().transaction(owner,c->{try(var q=c.prepareStatement("SELECT count(*) FROM opslens.draft_reviews WHERE draft_id=?")){q.setObject(1,UUID.fromString(id));try(var r=q.executeQuery()){r.next();assertEquals(2,r.getInt(1));}}return null;});
        // Later workflow endpoints stay unavailable in this increment.
        assertEquals(403,post("/v1/drafts/"+id+"/submit","platform","{}","application/json").statusCode());
        assertEquals(403,request("/v1/publications","platform",null,null).statusCode());
    }
    @Test void strictHttpInputAndAuthenticationBeforeParsing()throws Exception {
        assertEquals(401,post("/v1/drafts?filename=x.md",null,"x","invalid").statusCode());
        assertEquals(415,post("/v1/drafts?filename=x.md","intern","x","application/json").statusCode());
        assertEquals(422,post("/v1/drafts?filename=../x.md","intern","x","text/markdown").statusCode());
        assertEquals(422,post("/v1/drafts?filename=x.md","intern","\u0000","text/markdown").statusCode());
        assertEquals(422,request("/v1/drafts?filename=x.md","intern",new byte[]{(byte)0xc3,0x28},"text/markdown").statusCode());
        assertEquals(413,post("/v1/drafts?filename=x.md","intern","é".repeat(500001),"text/markdown").statusCode());
        String path="/v1/drafts/"+UUID.randomUUID()+"/review";
        for(String body:List.of("{","{\"expected_revision\":true,\"metadata\":{}}","{\"expected_revision\":0,\"expected_revision\":1,\"metadata\":{}}","{\"expected_revision\":0,\"metadata\":{},\"owner\":\"injected\"}"))assertEquals(422,post(path,"intern",body,"application/json").statusCode());
        assertEquals(401,post(path,null,"{","application/json").statusCode());
    }
    @Test void concurrentReviewsHaveExactlyOneWinner()throws Exception {
        String owner=owner();var d=repo().create(owner,"test.md","# Source\nActual procedure.");var id=UUID.fromString((String)d.get("id"));var metadata=metadata();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Integer> save=()->{gate.await();try{repo().save(owner,id,0,metadata);return 200;}catch(DraftFailure e){return e.status();}};
            var a=pool.submit(save);var b=pool.submit(save);gate.countDown();var statuses=new ArrayList<>(List.of(a.get(),b.get()));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
        }
        assertEquals(1,((Map<?,?>)repo().get(owner,id).get("review")).get("revision"));
    }
    @Test void quotaIsSerializedAndOwnerScoped()throws Exception {
        String owner=owner();for(int i=0;i<19;i++)repo().create(owner,"test.md","source");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Integer> create=()->{gate.await();try{repo().create(owner,"test.md","source");return 201;}catch(DraftFailure e){return e.status();}};
            var a=pool.submit(create);var b=pool.submit(create);gate.countDown();var statuses=new ArrayList<>(List.of(a.get(),b.get()));Collections.sort(statuses);assertEquals(List.of(201,429),statuses);
        }
        assertEquals(20,repo().list(owner).size());assertTrue(repo().list(owner()).isEmpty());
    }
    @Test void rollbackRlsAndLeastPrivilege()throws Exception {
        String owner=owner();var d=repo().create(owner,"test.md","source");
        assertNull(repo().get(owner(),UUID.fromString((String)d.get("id"))));
        for(String sql:List.of("UPDATE opslens.upload_drafts SET content='changed'","DELETE FROM opslens.draft_reviews","SET ROLE opslens_publish_operator","INSERT INTO opslens.documents VALUES ('BAD-1',1,ARRAY['platform'])"))
            assertEquals(503,assertThrows(DraftFailure.class,()->connections().transaction(owner,c->{c.createStatement().execute(sql);return null;})).status());
        assertThrows(DraftFailure.class,()->connections().transaction(owner,c->{try(var q=c.prepareStatement("INSERT INTO opslens.upload_drafts(id,owner,filename,content,proposal) VALUES (?,?, 'bad.md','source','{}')")){q.setObject(1,UUID.randomUUID());q.setString(2,owner);q.execute();}throw new SQLException("forced rollback");}));
        assertEquals(1,repo().list(owner).size());
        try(var c=admin();var q=c.createStatement()) {
            q.execute("ALTER TABLE opslens.upload_drafts DISABLE ROW LEVEL SECURITY");
            try{assertEquals(503,assertThrows(DraftFailure.class,()->repo().list(owner)).status());}
            finally{q.execute("ALTER TABLE opslens.upload_drafts ENABLE ROW LEVEL SECURITY");}
        }
    }
    @Test void invalidSourceProfileAndStaleRevisionCannotAppend()throws Exception {
        String owner=owner();var d=repo().create(owner,"test.md","# Heading\nProcedure");var id=UUID.fromString((String)d.get("id"));
        var m=(tools.jackson.databind.node.ObjectNode)metadata();m.set("evidence_profile",DraftRepository.JSON.readTree("{\"schema_version\":1,\"http_scope\":null,\"units\":[{\"role\":\"procedure\",\"start_line\":1,\"end_line\":1}]}"));
        assertEquals(422,assertThrows(DraftFailure.class,()->repo().save(owner,id,0,m)).status());assertNull(repo().get(owner,id).get("review"));
        assertEquals(409,assertThrows(DraftFailure.class,()->repo().save(owner,id,1,metadata())).status());assertNull(repo().get(owner,id).get("review"));
    }
    @Test void configFailureIsRedactedAndCannotFallBack()throws Exception {
        Path p=dir().resolve("bad-drafts.json");Files.writeString(p,"{\"database\":\"postgres\",\"draft_password\":\"do-not-reflect\",\"user\":\"opslens_admin\"}");Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var e=assertThrows(DraftFailure.class,()->new DraftRepository(new DraftConnections(p)).list(owner()));assertEquals(503,e.status());assertEquals("Draft store unavailable",e.getMessage());
    }
}
