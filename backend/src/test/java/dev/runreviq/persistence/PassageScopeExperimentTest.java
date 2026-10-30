package dev.runreviq.persistence;

import dev.runreviq.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic mechanism tests, distinct from the frozen external benchmark. */
@EnabledIfEnvironmentVariable(named="RUNREVIQ_JDBC_TEST_DIR",matches=".+")
class PassageScopeExperimentTest {
    static EvidenceRepository.Source source(String id,String text,String role){
        var profile=new EvidenceProfile(1,new EvidenceProfile.HttpScope("general",List.of()),List.of(new EvidenceProfile.Unit(role,3,3)));
        var doc=new EvidenceDocument(id,"Synthetic scope evidence",1,"datasets/northstar/"+id+".md",Set.of("platform"),"approved","scope-test","staging","1.0.0","2.0.0",null,true,List.of(),profile);
        return new EvidenceRepository.Source(doc,"# Synthetic scope evidence\n\n"+text+"\n");
    }
    static Map<String,Object> search(List<EvidenceRepository.Source> sources,String question,boolean candidate,List<Map<String,Object>> trace){
        return JdbcIntegrationTest.readers().read("platform",c->SearchRepository.search(c,sources,question,
            new SelectionContext("platform","scope-test","1.0.0","staging",null,null),null,false,trace::add,candidate));
    }
    @Test void unrelatedScopeStatementCannotVetoAnotherPassage(){
        var sources=List.of(source("TEST-1","Earlier queue delays involved worker leases. This incident does not establish cause.","historical"),
            source("TEST-2","Inspect queue delay counters before changing worker leases.","procedure"));
        assertEquals("insufficient_evidence",search(sources,"queue delay cause",false,new ArrayList<>()).get("status"));
        var trace=new ArrayList<Map<String,Object>>();var candidate=search(sources,"queue delay cause",true,trace);
        assertEquals(List.of("TEST-2"),candidate.get("recommended_documents"));
        assertTrue(trace.stream().anyMatch(e->"passage_excluded_terms".equals(e.get("decision"))));
        assertTrue(((String)candidate.get("message")).contains("not an answer or proof of the cause"));
    }
    @Test void unsupportedOnlyQuestionStillAbstains(){
        var sources=List.of(source("TEST-1","Inspect queue delay counters. This procedure does not authorize export.","procedure"));
        assertEquals("insufficient_evidence",search(sources,"export",true,new ArrayList<>()).get("status"));
        assertEquals(List.of(),search(sources,"queue delay export",true,new ArrayList<>()).get("passages"));
    }
    @Test void siblingSupportCannotOverrideLocalScope(){
        var sources=List.of(source("TEST-1","Inspect queue delay counters. This procedure does not authorize export.","procedure"),
            source("TEST-2","Export queue delay counters through the approved exporter.","procedure"));
        var candidate=search(sources,"queue delay export",true,new ArrayList<>());
        assertEquals(List.of("TEST-2"),candidate.get("recommended_documents"));
    }
    @Test void noScopeExclusionsPreserveBaselineAndHistoricalWarning(){
        var sources=List.of(source("TEST-1","Earlier queue delays involved worker leases.","historical"));
        var baseline=new LinkedHashMap<>(search(sources,"queue delay",false,new ArrayList<>()));
        var candidate=new LinkedHashMap<>(search(sources,"queue delay",true,new ArrayList<>()));
        baseline.remove("retrieval_method");candidate.remove("retrieval_method");assertEquals(baseline,candidate);
        assertTrue(candidate.toString().contains("not confirmation of the current cause"));
    }
}
