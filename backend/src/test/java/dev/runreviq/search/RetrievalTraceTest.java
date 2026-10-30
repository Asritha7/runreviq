package dev.runreviq.search;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalTraceTest {
    static Map<String,Object> passage(int number,String quote){return new LinkedHashMap<>(Map.of("document_id","TEST-"+number,"revision",1,"start_line",2,"end_line",2,"quote",quote,"evidence_role","procedure"));}
    @Test void scoresRejectionsAndTopKPreserveRanking(){
        var candidates=List.of(passage(1,"alpha beta"),passage(2,"alpha beta"),passage(3,"unrelated"));
        var bodies=List.of(List.of("alpha","beta"),List.of("alpha","beta"),List.of("unrelated"));
        var contexts=List.of(List.<String>of(),List.<String>of(),List.<String>of());
        for(String mode:List.of("reviewed","focused")){
            String question=mode.equals("focused")?"inspect alpha beta":null;
            var events=new ArrayList<Map<String,Object>>();
            var expected=LexicalRanking.rank(candidates,List.of("alpha","beta"),bodies,contexts,contexts,.35,List.of(),1,question,List.of("","",""));
            var actual=LexicalRanking.rank(candidates,List.of("alpha","beta"),bodies,contexts,contexts,.35,List.of(),1,question,List.of("","",""),events::add);
            assertEquals(expected,actual);assertEquals(List.of(candidates.getFirst()),actual);
            assertTrue(events.stream().anyMatch(e->"top_k".equals(e.get("decision"))));
            assertTrue(events.stream().anyMatch(e->"document_coverage".equals(e.get("decision"))));
            assertEquals(2,events.stream().filter(e->"score".equals(e.get("stage"))).count());
        }
    }
    @Test void bodyFloorAndRelativeScoreAreExplicit(){
        var candidates=List.of(passage(1,"strong"),passage(2,"weak"));
        var query=List.of("a","b","c","d","e","f","g","h");
        var empty=List.of(List.<String>of(),List.<String>of());
        var events=new ArrayList<Map<String,Object>>();
        LexicalRanking.rank(candidates,query,List.of(query,List.of("a")),List.of(query,query),empty,.35,List.of(),5,null,List.of("",""),events::add);
        assertTrue(events.stream().anyMatch(e->"body_coverage".equals(e.get("decision"))));
        events.clear();var shortQuery=List.of("a","b","c","d");
        var bodies=List.of(shortQuery,List.of("a"));
        var actual=LexicalRanking.rank(candidates,shortQuery,bodies,empty,empty,.1,List.of(),5,null,List.of("",""),events::add);
        assertEquals(LexicalRanking.rank(candidates,shortQuery,bodies,empty,empty,.1,List.of(),5,null,List.of("","")),actual);
        assertTrue(events.stream().anyMatch(e->"relative_score".equals(e.get("decision"))));
    }
    @Test void bundleExplainsDedupAndLimitWithoutChangingAssembly(){
        var hits=new ArrayList<Map<String,Object>>();for(int i=0;i<7;i++)hits.add(passage(i,"quote "+i));hits.add(passage(8,"quote 0"));
        var events=new ArrayList<Map<String,Object>>();
        assertEquals(Passages.bundle(hits),Passages.bundle(hits,events::add));
        assertTrue(events.stream().anyMatch(e->"duplicate_role_quote".equals(e.get("decision"))));
        assertTrue(events.stream().anyMatch(e->"bundle_limit".equals(e.get("decision"))));
    }
    @Test void observerCannotMutateRankingInputsThroughEvents(){
        var original=new ArrayList<>(List.of("alpha"));var events=new ArrayList<Map<String,Object>>();
        RetrievalTrace.emit(events::add,"test","terms",original);
        original.add("beta");assertEquals(List.of("alpha"),events.getFirst().get("terms"));
        assertThrows(UnsupportedOperationException.class,()->events.getFirst().put("terms",List.of()));
        assertThrows(UnsupportedOperationException.class,()->((List<?>)events.getFirst().get("terms")).clear());
    }
}
