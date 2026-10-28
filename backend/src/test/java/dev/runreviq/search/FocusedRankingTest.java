package dev.runreviq.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FocusedRankingTest {
    private static Map<String,Object> p(String id,int line){return Map.of("document_id",id,"start_line",line);}
    @Test void preferredHeadingCannotTurnTitleOnlyTextIntoEvidence(){
        var result=LexicalRanking.rank(List.of(p("EXAMPLE-1",1)),List.of("cache","verifi"),List.of(List.of("unrelated")),List.of(List.of("cache","verifi")),List.of(List.of()),.35,List.of(),5,"Which checks confirm cache recovery is working?",List.of("Verification"));
        assertTrue(result.isEmpty());
    }
    @Test void verificationIntentKeepsTopicAndBodyEvidence(){
        var candidates=List.of(p("EXAMPLE-1",1),p("EXAMPLE-2",1));
        var result=LexicalRanking.rank(candidates,List.of("cache","verifi","recover"),List.of(List.of("verifi","hit","ratio"),List.of("recover","queue")),List.of(List.of("cache","verifi"),List.of("queue","recover")),List.of(List.of(),List.of()),.35,List.of(),5,"Which checks confirm cache recovery is working?",List.of("Verification","Recovery"));
        assertEquals(candidates.getFirst(),result.getFirst());
    }
    @Test void missingConceptsAreNotWaivedByHeadingOrPolicy(){
        var result=LexicalRanking.rank(List.of(p("EXAMPLE-1",1)),List.of("cache","encrypt","key","command"),List.of(List.of("cache")),List.of(List.of("cache","verifi")),List.of(List.of("encrypt","key")),.5,List.of(),5,"Verify cache encryption commands",List.of("Verification"));
        assertTrue(result.isEmpty());
    }
    @Test void complementaryEvidenceAndReviewedProhibitionStayEligible(){
        var candidates=List.of(p("EXAMPLE-1",1),p("EXAMPLE-2",1));var groups=List.of(List.of("jwks","failur"),List.of("sign","key","identifi"));
        var result=LexicalRanking.rank(candidates,List.of("jwks","failur","sign","key","identifi"),groups,List.of(List.of(),List.of()),List.of(List.of(),List.of()),.5,groups,5,"Inspect JWKS failures while comparing signing key identifiers",List.of("Diagnostics","Diagnostics"));
        assertEquals(new HashSet<>(candidates),new HashSet<>(result));
        result=LexicalRanking.rank(candidates,List.of("signatur","verifi"),List.of(List.of("signatur","verifi"),List.of("signatur","verifi")),List.of(List.of(),List.of()),List.of(List.of(),List.of("signatur","verifi")),.35,List.of(),5,"Can signature verification be disabled?",List.of("Procedure","Restriction"));
        assertEquals(candidates.get(1),result.getFirst());assertEquals(2,result.size());
    }
    @Test void unknownModeCannotSilentlyUseBaseline(){
        assertThrows(IllegalArgumentException.class,()->new dev.runreviq.persistence.EvidenceRepository(null,"typo"));
    }
}
