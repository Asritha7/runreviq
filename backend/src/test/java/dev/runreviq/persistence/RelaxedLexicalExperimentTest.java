package dev.runreviq.persistence;

import dev.runreviq.domain.SelectionContext;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Development controls, not independently authored validation. */
@EnabledIfEnvironmentVariable(named="RUNREVIQ_JDBC_TEST_DIR",matches=".+")
class RelaxedLexicalExperimentTest {
    static Map<String,Object> search(String question,boolean relaxed){
        var sources=List.of(PassageScopeExperimentTest.source("TEST-1","Inspect queue counters before restarting workers.","procedure"));
        return JdbcIntegrationTest.readers().read("platform",c->SearchRepository.search(c,sources,question,
            new SelectionContext("platform","scope-test","1.0.0","staging",null,null),null,false,null,false,relaxed));
    }
    @Test void retainsSupportedEvidenceDespiteVerboseQuestion(){
        String question="queue counters puzzling unexpected sluggish activity during morning traffic";
        assertEquals("insufficient_evidence",search(question,false).get("status"));
        assertEquals(List.of("TEST-1"),search(question,true).get("recommended_documents"));
    }
    @Test void noSharedTermsStillAbstains(){
        assertEquals("insufficient_evidence",search("photograph invoice layout",true).get("status"));
    }
    @Test void preservesKnownNegativeFindingSharedGenericTermCanRetrieveWrongProcedure(){
        // The requested procedure is unrelated, despite one shared word. Record the
        // false positive rather than weakening the control to manufacture success.
        String question="queue ticket printer paper cartridge replacement";
        assertEquals("insufficient_evidence",search(question,false).get("status"));
        assertEquals(List.of("TEST-1"),search(question,true).get("recommended_documents"));
    }
    @Test void exactIdentifierGuardStillRejectsUnmatchedIdentifier(){
        assertEquals("insufficient_evidence",search("queue TEST-999",true).get("status"));
    }
}
