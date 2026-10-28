package dev.runreviq.authoring;

import static org.junit.jupiter.api.Assertions.*;
import static dev.runreviq.authoring.WorkflowSql.*;
import dev.runreviq.persistence.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="RUNREVIQ_JDBC_TEST_DIR",matches=".+")
class WorkflowIntegrationTest {
    @LocalServerPort int port;
    static Path dir(){return DraftIntegrationTest.dir();}
    static String actor(String role){return DraftRepository.owner("demo","test-"+role);}
    static WorkflowConnections reviewConnection(){return new WorkflowConnections(dir().resolve("review-db.json"),WorkflowConnections.Kind.REVIEWER);}
    static WorkflowConnections publishConnection(){return new WorkflowConnections(dir().resolve("publish-db.json"),WorkflowConnections.Kind.PUBLISHER);}
    static SubmissionRepository submissions(){return new SubmissionRepository(new DraftConnections(dir().resolve("drafts.json")),reviewConnection());}
    static PublicationRepository publications(){return new PublicationRepository(publishConnection());}
    static LifecycleRepository lifecycle(){return new LifecycleRepository(publishConnection());}
    static DraftRepository drafts(){return DraftIntegrationTest.repo();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("runreviq.auth.mode",()->"demo");r.add("runreviq.auth.config-path",()->dir().resolve("auth.json").toString());
        r.add("runreviq.drafts.enabled",()->"true");r.add("runreviq.drafts.config-path",()->dir().resolve("drafts.json").toString());
        r.add("runreviq.workflows.enabled",()->"true");r.add("runreviq.workflows.reviewer-config-path",()->dir().resolve("review-db.json").toString());
        r.add("runreviq.workflows.publisher-config-path",()->dir().resolve("publish-db.json").toString());
        r.add("runreviq.workflows.reviewers-path",()->dir().resolve("reviewers.json").toString());r.add("runreviq.workflows.publishers-path",()->dir().resolve("publishers.json").toString());
        r.add("runreviq.database.enabled",()->"true");r.add("runreviq.database.config-path",()->dir().resolve("readers.json").toString());
    }
    HttpResponse<String> request(String path,String role,Object body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(role!=null)b.header("Authorization","Bearer "+role+"-test-"+"x".repeat(40));
        if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body instanceof String?(String)body:DraftRepository.JSON.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String path,String role,Object body,int status)throws Exception {var r=request(path,role,body);assertEquals(status,r.statusCode(),r.body());return DraftRepository.JSON.readTree(r.body());}
    static JsonNode claims(){return DraftRepository.JSON.readTree("[{\"action\":\"inspect_jwks\",\"rule\":\"must\",\"start_line\":2,\"end_line\":2}]");}
    static JsonNode roles(){return DraftRepository.JSON.readTree("[\"platform\"]");}
    static UUID draft(String owner)throws Exception{var d=drafts().create(owner,"workflow.md","# Source\nInspect JWKS before restart.\nDo not disable verification.\n");var id=UUID.fromString((String)d.get("id"));drafts().save(owner,id,0,DraftIntegrationTest.metadata());return id;}
    static UUID submitted(String owner)throws Exception{return UUID.fromString((String)submissions().submit(owner,draft(owner),1).get("id"));}
    static UUID approved(String owner)throws Exception{var id=submitted(owner);submissions().decide(actor("security"),id,"approved","Checked source");return id;}
    @Test void fullHttpFlowPreservesSourceAndEnforcesSeparatePermissions()throws Exception {
        String owner=actor("platform");var draft=draft(owner);String path="/v1/drafts/"+draft;
        ok(path+"/submit","security",Map.of("revision",1),404);
        var sub=ok(path+"/submit","platform",Map.of("revision",1),200);String id=sub.get("id").asString();
        assertEquals(sub,ok(path+"/submit","platform",Map.of("revision",1),200));
        ok("/v1/submissions/"+id,"platform",null,403);ok("/v1/submissions/"+id,"security",null,200);
        ok("/v1/publications/"+id,"intern",null,404);
        assertFalse(ok("/v1/publications/queue","intern",null,200).toString().contains(id));
        var decision=Map.of("decision","approved","comment","Source reviewed");
        ok("/v1/submissions/"+id+"/decision","intern",decision,403);
        ok("/v1/submissions/"+id+"/decision","security",decision,200);
        ok("/v1/submissions/"+id+"/decision","security",decision,409);
        assertTrue(ok("/v1/publications/queue","intern",null,200).toString().contains(id));
        var body=map("allowed_roles",roles(),"claims",claims(),"metadata_verified",true);
        ok("/v1/publications/"+id,"security",body,403);
        var pub=ok("/v1/publications/"+id,"intern",body,200);String doc=pub.get("document_id").asString();
        assertEquals(pub,ok("/v1/publications/"+id,"intern",body,200));
        ok("/v1/publications/"+id,"intern",map("allowed_roles",List.of("intern"),"claims",claims(),"metadata_verified",true),409);
        var source=ok("/v1/documents/"+doc+"/revisions/1","platform",null,200);assertTrue(source.get("content").asString().contains("Inspect JWKS"));
        ok("/v1/documents/"+doc+"/revisions/1","intern",null,404); // Publishing permission is not reading permission.
        String manage="/v1/publication-management/documents/"+doc;
        ok(manage,"platform",null,403);ok(manage+"/revisions/1","intern",null,200);
        assertNotNull(ok(path+"/submissions","platform",null,200).get("submissions").get(0).get("publication"));
        var withdraw=Map.of("expected_revision",1,"reason","Needs correction");
        var retired=ok(manage+"/withdraw","intern",withdraw,200);assertEquals(retired,ok(manage+"/withdraw","intern",withdraw,200));
        ok(manage+"/withdraw","intern",Map.of("expected_revision",1,"reason","Different reason"),409);
        assertEquals("withdrawn",ok(manage,"intern",null,200).get("revisions").get(0).get("status").asString());
        assertEquals(source.get("content"),ok("/v1/documents/"+doc+"/revisions/1","platform",null,200).get("content"));
        var selected=new EvidenceRepository(new ReaderConnections(dir().resolve("readers.json"))).current("platform");assertTrue(selected.stream().noneMatch(d->d.id().equals(doc)));
    }
    @Test void pythonPublicationParityAndWithdrawalRoundTrip()throws Exception {
        var f=DraftRepository.JSON.readTree(Files.readString(dir().resolve("workflow-interop.json")));var id=UUID.fromString(f.get("submission_id").asString());String owner=f.get("owner").asString();
        assertEquals(f.get("own"),DraftRepository.JSON.valueToTree(submissions().own(owner,UUID.fromString(f.get("draft_id").asString()))));
        assertEquals(f.get("detail"),DraftRepository.JSON.valueToTree(submissions().detail(actor("security"),id)));
        assertEquals(f.get("publish_detail"),DraftRepository.JSON.valueToTree(publications().detail(actor("intern"),id)));
        assertEquals(f.get("publication"),DraftRepository.JSON.valueToTree(publications().publish(actor("intern"),id,roles(),f.get("claims"),true)));
        String doc=f.get("publication").get("document_id").asString();
        assertEquals(f.get("history"),DraftRepository.JSON.valueToTree(lifecycle().history(actor("intern"),doc)));
        assertEquals(f.get("revision"),DraftRepository.JSON.valueToTree(lifecycle().revision(actor("intern"),doc,1)));
        lifecycle().withdraw(actor("intern"),doc,1,"Java withdrawal round trip");
    }
    @Test void ownerCannotReviewOwnSubmissionEvenWhenAllowlisted()throws Exception {
        var id=submitted(actor("security"));assertNull(submissions().detail(actor("security"),id));
        ok("/v1/submissions/"+id+"/decision","security",Map.of("decision","approved","comment","Self approval"),404);
        assertTrue(submissions().queue(actor("security")).stream().noneMatch(r->r.get("id").equals(id.toString())));
        assertThrows(DraftFailure.class,()->reviewConnection().transaction(actor("security"),c->{execute(c,"INSERT INTO opslens.submission_decisions(submission_id,reviewer,decision,comment) VALUES (?,?,'approved','Bypass')",id,actor("security"));return null;}));
    }
    @Test void allowlistsReloadAndFailuresNeverFallBackToReadingRoles()throws Exception {
        assertFalse(ok("/v1/reviewer-access","platform",null,200).get("can_review").asBoolean());assertTrue(ok("/v1/reviewer-access","security",null,200).get("can_review").asBoolean());
        assertFalse(ok("/v1/publisher-access","security",null,200).get("can_publish").asBoolean());
        Path reviewers=dir().resolve("reviewers.json"),publishers=dir().resolve("publishers.json");String a=Files.readString(reviewers),b=Files.readString(publishers);
        try{
            Files.writeString(reviewers,"{\"google\":[],\"demo\":[]}");ok("/v1/submissions","security",null,403);
            Files.writeString(publishers,"{\"google\":[],\"demo\":[]}");ok("/v1/publications/queue","intern",null,403);
            Files.writeString(publishers,"{\"password\":\"never-reflect-me\"}");var failure=ok("/v1/publications/queue","intern",null,503);assertFalse(failure.toString().contains("never-reflect"));
        }finally{Files.writeString(reviewers,a);Files.writeString(publishers,b);}
        ok("/v1/publications/"+UUID.randomUUID(),null,"{",401);ok("/v1/publications/"+UUID.randomUUID(),"platform","{",403);
        for(String body:List.of("{","{\"allowed_roles\":[],\"claims\":[],\"metadata_verified\":1}","{\"actor\":\"forged\"}"))ok("/v1/publications/"+UUID.randomUUID(),"intern",body,422);
    }
    @Test void incompleteStaleAndRejectedSnapshotsCannotPublish()throws Exception {
        String owner=DraftIntegrationTest.owner();var d=draft(owner);var m=(tools.jackson.databind.node.ObjectNode)DraftIntegrationTest.metadata();m.put("service","");drafts().save(owner,d,1,m);
        assertEquals(409,assertThrows(DraftFailure.class,()->submissions().submit(owner,d,1)).status());
        assertEquals(422,assertThrows(DraftFailure.class,()->submissions().submit(owner,d,2)).status());
        var id=submitted(owner);submissions().decide(actor("security"),id,"changes_requested","Missing procedure context");
        assertNull(publications().detail(actor("intern"),id));assertNull(publications().publish(actor("intern"),id,roles(),claims(),true));
        assertNull(submissions().own(DraftIntegrationTest.owner(),d));
    }
    @Test void decisionsAndPublicationRetriesAreSerialized()throws Exception {
        var id=submitted(DraftIntegrationTest.owner());
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);Callable<Integer> decide=()->{gate.await();try{submissions().decide(actor("security"),id,"approved","Independent check");return 200;}catch(DraftFailure e){return e.status();}};
            var a=pool.submit(decide);var b=pool.submit(decide);gate.countDown();var result=new ArrayList<>(List.of(a.get(),b.get()));Collections.sort(result);assertEquals(List.of(200,409),result);
            var gate2=new CountDownLatch(1);Callable<Map<String,Object>> publish=()->{gate2.await();return publications().publish(actor("intern"),id,roles(),claims(),true);};
            var p=pool.submit(publish);var q=pool.submit(publish);gate2.countDown();assertEquals(p.get(),q.get());
        }
    }
    @Test void newerRevisionSupersedesWithoutRewritingHistoryOrPermissions()throws Exception {
        String owner=DraftIntegrationTest.owner();var d=draft(owner);var first=UUID.fromString((String)submissions().submit(owner,d,1).get("id"));submissions().decide(actor("security"),first,"approved","Checked first");
        var publication=publications().publish(actor("intern"),first,roles(),claims(),true);String doc=(String)publication.get("document_id");
        var before=lifecycle().revision(actor("intern"),doc,1);drafts().save(owner,d,1,DraftIntegrationTest.metadata());var second=UUID.fromString((String)submissions().submit(owner,d,2).get("id"));submissions().decide(actor("security"),second,"approved","Checked second");
        publications().publish(actor("intern"),second,DraftRepository.JSON.readTree("[\"security\"]"),claims(),true);
        var after=lifecycle().revision(actor("intern"),doc,1);assertEquals(before.get("checksum"),after.get("checksum"));assertEquals("superseded",after.get("status"));
        var evidence=new EvidenceRepository(new ReaderConnections(dir().resolve("readers.json")));assertNull(evidence.revision("platform",doc,1));assertNotNull(evidence.revision("security",doc,1));
        assertEquals(409,assertThrows(DraftFailure.class,()->lifecycle().withdraw(actor("intern"),doc,1,"Stale" )).status());
        assertEquals(publication,publications().publish(actor("intern"),first,roles(),claims(),true)); // Retry never resets current_revision or roles.
        assertEquals(2,lifecycle().history(actor("intern"),doc).get("current_revision"));
    }
    @Test void neverPublishedOldSnapshotCannotReplaceNewerGuidance()throws Exception {
        String owner=DraftIntegrationTest.owner();var d=draft(owner);var old=UUID.fromString((String)submissions().submit(owner,d,1).get("id"));submissions().decide(actor("security"),old,"approved","First reviewed");
        drafts().save(owner,d,1,DraftIntegrationTest.metadata());var newer=UUID.fromString((String)submissions().submit(owner,d,2).get("id"));submissions().decide(actor("security"),newer,"approved","Second reviewed");
        var result=publications().publish(actor("intern"),newer,roles(),claims(),true);
        assertEquals(409,assertThrows(DraftFailure.class,()->publications().publish(actor("intern"),old,roles(),claims(),true)).status());
        assertTrue(publications().queue(actor("intern")).stream().noneMatch(r->r.get("id").equals(old.toString())));
        assertEquals(2,lifecycle().history(actor("intern"),(String)result.get("document_id")).get("current_revision"));
    }
    @Test void onlySubmittedAndApprovedSnapshotsCrossDatabaseBoundaries()throws Exception {
        String owner=DraftIntegrationTest.owner();var d=draft(owner);
        assertNull(reviewConnection().transaction(actor("security"),c->row(c,"SELECT id FROM opslens.upload_drafts WHERE id=?",d)));
        assertNull(publishConnection().transaction(actor("intern"),c->row(c,"SELECT id FROM opslens.upload_drafts WHERE id=?",d)));
        var id=UUID.fromString((String)submissions().submit(owner,d,1).get("id"));
        assertNotNull(reviewConnection().transaction(actor("security"),c->row(c,"SELECT id FROM opslens.upload_drafts WHERE id=?",d)));
        assertNull(publishConnection().transaction(actor("intern"),c->row(c,"SELECT id FROM opslens.draft_submissions WHERE id=?",id)));
        submissions().decide(actor("security"),id,"approved","Independent approval");
        assertNotNull(publishConnection().transaction(actor("intern"),c->row(c,"SELECT id FROM opslens.draft_submissions WHERE id=?",id)));
    }
    @Test void claimsAndProhibitionsMustHaveExactSourceSupport()throws Exception {
        var id=approved(DraftIntegrationTest.owner());assertThrows(DraftFailure.class,()->publications().publish(actor("intern"),id,roles(),claims(),false));
        for(String claim:List.of("[]","[{\"action\":\"inspect\",\"rule\":\"must\",\"start_line\":true,\"end_line\":2}]","[{\"action\":\"inspect\",\"rule\":\"must\",\"start_line\":2,\"end_line\":99}]"))assertThrows(DraftFailure.class,()->publications().publish(actor("intern"),id,roles(),DraftRepository.JSON.readTree(claim),true));
        String owner=DraftIntegrationTest.owner();var d=draft(owner);var m=(tools.jackson.databind.node.ObjectNode)DraftIntegrationTest.metadata();m.set("evidence_profile",DraftRepository.JSON.readTree("{\"schema_version\":1,\"http_scope\":null,\"units\":[{\"role\":\"prohibition\",\"start_line\":3,\"end_line\":3}]}"));drafts().save(owner,d,1,m);
        var prohibition=UUID.fromString((String)submissions().submit(owner,d,2).get("id"));submissions().decide(actor("security"),prohibition,"approved","Restriction checked");
        assertThrows(DraftFailure.class,()->publications().publish(actor("intern"),prohibition,roles(),DraftRepository.JSON.readTree("[]"),true));
        var cited=DraftRepository.JSON.readTree("[{\"action\":\"disable_verification\",\"rule\":\"must_not\",\"start_line\":3,\"end_line\":3}]");
        assertNotNull(publications().publish(actor("intern"),prohibition,roles(),cited,true));
        // A reviewed procedure unit has no normative prohibition requiring a claim.
        m.set("evidence_profile",DraftRepository.JSON.readTree("{\"schema_version\":1,\"http_scope\":null,\"units\":[{\"role\":\"procedure\",\"start_line\":2,\"end_line\":2}]}"));
        drafts().save(owner,d,2,m);var procedure=UUID.fromString((String)submissions().submit(owner,d,3).get("id"));
        submissions().decide(actor("security"),procedure,"approved","Procedure checked");
        assertNotNull(publications().publish(actor("intern"),procedure,roles(),DraftRepository.JSON.readTree("[]"),true));
    }
    @Test void restrictedAccountsRlsAndRollbackPreventPartialPublication()throws Exception {
        var id=approved(DraftIntegrationTest.owner());
        assertThrows(DraftFailure.class,()->reviewConnection().transaction(actor("security"),c->{execute(c,"SET ROLE opslens_publish_operator");return null;}));
        assertThrows(DraftFailure.class,()->publishConnection().transaction(actor("intern"),c->{execute(c,"UPDATE opslens.draft_reviews SET metadata='{}'");return null;}));
        assertThrows(DraftFailure.class,()->reviewConnection().transaction(actor("security"),c->{execute(c,"UPDATE opslens.submission_decisions SET comment='changed'");return null;}));
        try(var c=DraftIntegrationTest.admin()){
            // Force failure after document/version INSERTs; all writes must roll back.
            execute(c,"CREATE FUNCTION opslens.test_reject_publication() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END; $$");
            execute(c,"CREATE TRIGGER test_reject_publication BEFORE INSERT ON opslens.publications FOR EACH ROW EXECUTE FUNCTION opslens.test_reject_publication()");
            try{assertEquals(503,assertThrows(DraftFailure.class,()->publications().publish(actor("intern"),id,roles(),claims(),true)).status());}
            finally{execute(c,"DROP TRIGGER test_reject_publication ON opslens.publications");execute(c,"DROP FUNCTION opslens.test_reject_publication()");}
            var r=row(c,"SELECT count(*) AS count FROM opslens.document_versions WHERE metadata->>'path'=?","submission:"+id);assertEquals(0L,r.get("count"));
            execute(c,"ALTER TABLE opslens.submission_decisions DISABLE ROW LEVEL SECURITY");
            try{assertEquals(503,assertThrows(DraftFailure.class,()->submissions().queue(actor("security"))).status());}
            finally{execute(c,"ALTER TABLE opslens.submission_decisions ENABLE ROW LEVEL SECURITY");}
        }
        assertNotNull(publications().publish(actor("intern"),id,roles(),claims(),true));
    }
}
