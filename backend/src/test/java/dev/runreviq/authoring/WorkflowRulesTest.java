package dev.runreviq.authoring;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowRulesTest {
    @TempDir Path dir;
    private Path config(String body)throws Exception{Path p=dir.resolve(UUID.randomUUID()+".json");Files.writeString(p,body);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));return p;}
    @Test void independentAllowlistsAreModeBoundAndReloaded()throws Exception{
        Path reviewers=config("{\"google\":[\"person\"],\"demo\":[]}"),publishers=config("{\"google\":[],\"demo\":[\"person\"]}");
        var access=new WorkflowAccess(reviewers,publishers);assertTrue(access.allowed(false,"google","person"));assertFalse(access.allowed(false,"demo","person"));assertFalse(access.allowed(true,"google","person"));assertTrue(access.allowed(true,"demo","person"));
        Files.writeString(reviewers,"{\"google\":[],\"demo\":[]}");assertFalse(access.allowed(false,"google","person"));
        assertFalse(new WorkflowAccess(null,null).allowed(true,"google","person"));assertFalse(new WorkflowAccess(dir.resolve("absent"),null).allowed(false,"google","person"));
    }
    @Test void badOrUnsafeAssignmentsFailClosed()throws Exception{
        for(String body:List.of("{", "{\"google\":[123],\"demo\":[]}","{\"google\":[],\"demo\":[],\"platform\":[\"person\"]}","{\"google\":[],\"google\":[\"person\"],\"demo\":[]}")){
            var access=new WorkflowAccess(config(body),null);assertEquals(503,assertThrows(DraftFailure.class,()->access.allowed(false,"google","person")).status());
        }
        Path p=config("{\"google\":[\"person\"],\"demo\":[]}");Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(DraftFailure.class,()->new WorkflowAccess(p,null).allowed(false,"google","person"));
        Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));Path link=dir.resolve("link.json");Files.createSymbolicLink(link,p);assertThrows(DraftFailure.class,()->new WorkflowAccess(link,null).allowed(false,"google","person"));
    }
    @Test void claimQuotesAndHashUseExactPythonLineRules(){
        String content="# Heading\r\nInspect café 🔑.\u0085Do not restart.\n";
        var input=DraftRepository.JSON.readTree("[{\"action\":\"inspect_keys\",\"rule\":\"must\",\"start_line\":2,\"end_line\":3}]");
        var claims=PublicationRepository.claims(input,content);var citation=(Map<?,?>)claims.getFirst().get("citation");
        assertEquals("Inspect café 🔑.\nDo not restart.",citation.get("quote"));assertEquals(dev.runreviq.persistence.PythonJson.sha256(content),citation.get("source_sha256"));
        var duplicate=DraftRepository.JSON.createArrayNode().add(input.get(0)).add(input.get(0));assertThrows(DraftFailure.class,()->PublicationRepository.claims(duplicate,content));
        assertThrows(DraftFailure.class,()->PublicationRepository.claims(input,"short"));
    }
}
