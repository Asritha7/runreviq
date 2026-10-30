package dev.runreviq.persistence;

import dev.runreviq.auth.PrivateConfiguration;
import dev.runreviq.domain.SelectionContext;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Test-classpath only. No web server, admin credential, gold labels or production database. */
@EnabledIfEnvironmentVariable(named="RUNREVIQ_OFFLINE_TRACE_DIR",matches=".+")
class OfflineTraceTest {
    @Test void replayFrozenRequestsWithReadOnlyAuthorizedTracing() throws Exception {
        Path dir=Path.of(System.getenv("RUNREVIQ_OFFLINE_TRACE_DIR"));
        var config=PrivateConfiguration.readOwnerOnly(dir.resolve("readers.json"));
        assertTrue(config.get("database").asString().matches("opslens_java_eval_[0-9a-f]{32}"),"Isolated database required");
        var readers=new ReaderConnections(dir.resolve("readers.json"));
        var input=PrivateConfiguration.readOwnerOnly(dir.resolve("requests.json"));
        var output=new ArrayList<Map<String,Object>>();
        for(var item:input){
            var n=item.get("context");String role=n.get("role").asString();
            var context=new SelectionContext(role,n.get("service").asString(),n.get("software_version").asString(),n.get("environment").asString(),
                n.hasNonNull("key_rotation_enabled")?n.get("key_rotation_enabled").asBoolean():null,n.hasNonNull("http_status")?n.get("http_status").asInt():null);
            String question=item.get("question").asString();boolean focused=item.get("mode").asString().equals("focused-v6");
            var request=SearchRepository.context(question,context);
            output.add(readers.read(role,c->{
                var sources=EvidenceRepository.snapshot(c);var trace=new ArrayList<Map<String,Object>>();var repeat=new ArrayList<Map<String,Object>>();
                var plain=SearchRepository.search(c,sources,question,request,context.httpStatus(),focused);
                var observed=SearchRepository.search(c,sources,question,request,context.httpStatus(),focused,trace::add);
                var again=SearchRepository.search(c,sources,question,request,context.httpStatus(),focused,repeat::add);
                assertTrue(plain.equals(observed)&&plain.equals(again),"Instrumentation changed response");
                assertTrue(trace.equals(repeat),"Nondeterministic trace");
                assertTrue(StoredEvidence.JSON.readTree(StoredEvidence.JSON.writeValueAsString(plain)).equals(item.get("frozen_response")),"Frozen response changed");
                var authorized=new HashSet<String>();sources.forEach(s->authorized.add(s.document().id()));
                checkReferences(trace,authorized);
                try(var q=c.createStatement();var r=q.executeQuery("SHOW transaction_read_only")){r.next();assertEquals("on",r.getString(1));}
                return Map.<String,Object>of("id",item.get("id").asString(),"mode",item.get("mode").asString(),"response",plain,"events",trace);
            }));
        }
        Path target=dir.resolve("traces.json");
        Files.createFile(target,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(target,StoredEvidence.JSON.writeValueAsString(output));
    }
    static void checkReferences(Object value,Set<String> authorized){
        if(value instanceof Map<?,?> map){if(map.containsKey("document_id"))assertTrue(authorized.contains(map.get("document_id")),"Unauthorized trace reference");map.values().forEach(v->checkReferences(v,authorized));}
        else if(value instanceof Iterable<?> list)list.forEach(v->checkReferences(v,authorized));
    }
}
