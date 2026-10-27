package dev.runreviq.persistence;

import static org.junit.jupiter.api.Assertions.*;
import dev.runreviq.auth.PrivateConfiguration;
import dev.runreviq.domain.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="RUNREVIQ_JDBC_TEST_DIR",matches=".+")
class JdbcIntegrationTest {
    static Path dir() {return Path.of(System.getenv("RUNREVIQ_JDBC_TEST_DIR"));}
    static ReaderConnections readers() {return new ReaderConnections(dir().resolve("readers.json"));}
    static EvidenceRepository repository() {return new EvidenceRepository(readers());}
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("runreviq.auth.mode",()->"demo");r.add("runreviq.auth.config-path",()->dir().resolve("auth.json").toString());
        r.add("runreviq.database.enabled",()->"true");r.add("runreviq.database.config-path",()->dir().resolve("readers.json").toString());
    }
    static Connection admin() throws Exception {
        var n=PrivateConfiguration.readOwnerOnly(dir().resolve("admin.json"));String db=n.get("database").asString();
        if(!db.matches("opslens_java_test_[0-9a-f]{32}") || !db.equals(System.getenv("RUNREVIQ_JDBC_TEST_DATABASE"))) throw new IllegalStateException("Not a disposable test database");
        var source=new PGSimpleDataSource();source.setServerNames(new String[]{"127.0.0.1"});source.setPortNumbers(new int[]{55432});
        source.setDatabaseName(db);source.setUser("opslens_admin");source.setPassword(n.get("password").asString());
        return source.getConnection();
    }
    static JsonNode expected() throws Exception {return StoredEvidence.JSON.readTree(Files.readString(dir().resolve("expected.json")));}
    HttpResponse<String> request(String path,String role,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(role!=null) builder.header("Authorization","Bearer "+role+"-test-"+"x".repeat(40));
        if(body!=null) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @TestFactory Stream<DynamicTest> pythonSelectionAndRevisionParityOverHttp() throws Exception {
        var tests=new ArrayList<DynamicTest>();
        for(var c:expected().get("cases")) tests.add(DynamicTest.dynamicTest(c.get("role")+" "+c.get("context"),()->{
            var response=request("/v1/select",c.get("role").asString(),c.get("context").toString());
            assertEquals(200,response.statusCode(),response.body());assertEquals(c.get("expected"),StoredEvidence.JSON.readTree(response.body()));
        }));
        for(var c:expected().get("revisions")) tests.add(DynamicTest.dynamicTest("revision "+c.get("role")+c.get("document")+c.get("revision"),()->{
            var response=request("/v1/documents/"+c.get("document").asString()+"/revisions/"+c.get("revision").asInt(),c.get("role").asString(),null);
            if(c.get("expected").isNull()) {assertEquals(404,response.statusCode());assertEquals("{\"detail\":\"Document revision not found\"}",response.body());}
            else {assertEquals(200,response.statusCode(),response.body());assertEquals(c.get("expected"),StoredEvidence.JSON.readTree(response.body()));}
        }));return tests.stream();
    }
    @Test void pythonCanonicalUnicodeChecksumsMatchExactly() throws Exception {
        for(var c:expected().get("canonical")) {
            String encoded=PythonJson.encode(c.get("value"));assertEquals(c.get("encoded").asString(),encoded);
            assertEquals(c.get("sha256").asString(),PythonJson.sha256(encoded));
        }
    }
    @Test void readersCannotWriteSwitchRolesOrSeeRestrictedRows() {
        assertEquals("on",readers().read("platform",c->{try(var q=c.createStatement();var row=q.executeQuery("SHOW transaction_read_only")){row.next();return row.getString(1);}}));
        assertEquals("repeatable read",readers().read("platform",c->{try(var q=c.createStatement();var row=q.executeQuery("SHOW transaction_isolation")){row.next();return row.getString(1);}}));
        assertThrows(EvidenceUnavailable.class,()->readers().read("intern",c->{c.createStatement().execute("SET ROLE opslens_reader_security");return null;}));
        assertThrows(EvidenceUnavailable.class,()->readers().read("platform",c->{c.createStatement().execute("UPDATE opslens.documents SET allowed_roles=ARRAY['intern']");return null;}));
        assertEquals(List.of("RB-044"),readers().read("intern",c->{
            var ids=new ArrayList<String>();try(var q=c.createStatement();var rows=q.executeQuery("SELECT id FROM opslens.documents ORDER BY id")){while(rows.next())ids.add(rows.getString(1));}return ids;
        }));
        assertEquals("opslens_reader_platform",readers().read("platform",c->{try(var q=c.createStatement();var rows=q.executeQuery("SELECT current_user")){rows.next();return rows.getString(1);}}));
        assertThrows(EvidenceUnavailable.class,()->repository().current("admin"));
    }
    @Test void permissionChangesApplyNextRequestAndSnapshotsStayConsistent() throws Exception {
        try(var admin=admin();var update=admin.createStatement()) {
            try {
                assertNotNull(repository().revision("platform","RB-042",1));
                readers().read("platform",c->{
                    try(var q=c.createStatement();var first=q.executeQuery("SELECT count(*) FROM opslens.documents WHERE id='RB-042'")){first.next();assertEquals(1,first.getInt(1));}
                    update.execute("UPDATE opslens.documents SET allowed_roles=ARRAY['security'] WHERE id='RB-042'");
                    try(var q=c.createStatement();var second=q.executeQuery("SELECT count(*) FROM opslens.documents WHERE id='RB-042'")){second.next();assertEquals(1,second.getInt(1));}
                    return null;
                });
                assertNull(repository().revision("platform","RB-042",1));assertNull(repository().revision("platform","RB-042",2));
                assertNotNull(repository().revision("security","RB-042",1));
                assertEquals(404,request("/v1/documents/RB-042/revisions/1","platform",null).statusCode());
            } finally {update.execute("UPDATE opslens.documents SET allowed_roles=ARRAY['platform','security'] WHERE id='RB-042'");}
        }
    }
    @Test void databaseTriggerPreservesRevisionsAndTransactionRollsBack() throws Exception {
        try(var c=admin()) {
            c.setAutoCommit(false);
            try(var q=c.createStatement()) {
                q.execute("UPDATE opslens.documents SET allowed_roles=ARRAY['intern'] WHERE id='RB-042'");
                assertThrows(SQLException.class,()->q.execute("UPDATE opslens.document_versions SET content='tampered' WHERE document_id='RB-042'"));
                c.rollback();
            }
        }
        assertNotNull(repository().revision("platform","RB-042",1));assertNull(repository().revision("intern","RB-042",1));
    }
    @Test void unauthorizedCorruptRowsAreNeverInspectedOrDisclosed() throws Exception {
        try(var c=admin()) {
            c.setAutoCommit(false);
            try(var q=c.createStatement()) {
                q.execute("INSERT INTO opslens.documents VALUES ('CORRUPT-999',1,ARRAY['security'])");
                q.execute("INSERT INTO opslens.document_versions(document_id,revision,checksum,metadata,content) VALUES ('CORRUPT-999',1,'invalid','{}','restricted-corrupt-source')");
                c.commit();
                try {
                    assertFalse(repository().current("platform").stream().anyMatch(d->d.id().equals("CORRUPT-999")));
                    assertThrows(EvidenceUnavailable.class,()->repository().current("security"));
                    var hidden=request("/v1/documents/CORRUPT-999/revisions/1","platform",null);
                    assertEquals(404,hidden.statusCode());assertFalse(hidden.body().contains("restricted"));
                    assertEquals(503,request("/v1/documents/CORRUPT-999/revisions/1","security",null).statusCode());
                } finally {
                    q.execute("UPDATE opslens.documents SET allowed_roles=ARRAY[]::text[] WHERE id='CORRUPT-999'");c.commit();
                }
            }
        }
    }
    @Test void removedRlsFailsClosed() throws Exception {
        try(var c=admin();var q=c.createStatement()) {
            try {q.execute("ALTER TABLE opslens.documents DISABLE ROW LEVEL SECURITY");assertThrows(EvidenceUnavailable.class,()->repository().current("platform"));}
            finally {q.execute("ALTER TABLE opslens.documents ENABLE ROW LEVEL SECURITY");}
        }
    }
    @Test void identityAndStrictInputAreRequiredBeforeEvidenceReads() throws Exception {
        assertEquals(401,request("/v1/select",null,"{}").statusCode());
        for(String body:List.of("{\"role\":\"security\"}","{\"service\":\"x\",\"software_version\":\"2.4.0\",\"environment\":\"production\",\"key_rotation_enabled\":\"true\"}","{\"service\":\"x\",\"software_version\":\"2.4.0\",\"environment\":\"production\",\"http_status\":true}"))
            assertEquals(422,request("/v1/select","platform",body).statusCode());
        assertEquals(422,request("/v1/documents/RB-042/revisions/2147483648","platform",null).statusCode());
        assertEquals(403,request("/v1/submissions","security",null).statusCode());
    }
    @Test void unavailableStoreDoesNotExposeSecretsOrFallback() throws Exception {
        Path path=dir().resolve("readers.json");String saved=Files.readString(path);
        try {Files.writeString(path,"{invalid secret-config}");
            var response=request("/v1/documents/RB-042/revisions/1","platform",null);
            assertEquals(503,response.statusCode());assertEquals("{\"detail\":\"Evidence store unavailable\"}",response.body());
        } finally {Files.writeString(path,saved);}
    }
}
