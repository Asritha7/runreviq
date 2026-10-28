package dev.runreviq.search;

import static org.junit.jupiter.api.Assertions.*;
import static dev.runreviq.search.LexicalRanking.*;
import dev.runreviq.domain.*;
import dev.runreviq.persistence.PythonJson;
import java.util.*;
import org.junit.jupiter.api.Test;

class LexicalRulesTest {
    private static EvidenceDocument document(EvidenceProfile profile){return new EvidenceDocument("TEST-1","Example",1,"test",Set.of("platform"),"approved","identity-service","production","2.4.0","2.5.0",true,true,List.of(),profile);}
    @Test void exactSourceBoundariesUnicodeAndLongParagraphs(){
        String content="# Title\r\n\r\n first café 🔑\r\nsecond \r\n\r\n## Heading\r\nthird\u0085fourth\u2029";
        var p=Passages.extract(document(null),content);assertEquals(2,p.size());assertEquals(3,p.get(0).get("start_line"));assertEquals(4,p.get(0).get("end_line"));assertEquals(" first café 🔑\nsecond ",p.get(0).get("quote"));assertEquals("third\nfourth",p.get(1).get("quote"));assertEquals(PythonJson.sha256(content),p.get(1).get("source_sha256"));
        assertTrue(Passages.extract(document(null),"# Heading\n\n").isEmpty());
        var longSource=Passages.extract(document(null),String.join("\n",Collections.nCopies(45,"line")));assertEquals(List.of(20,40,45),longSource.stream().map(x->x.get("end_line")).toList());
        assertEquals(1,Passages.extract(document(null),"x".repeat(10000)).size());
    }
    @Test void reviewedUnitsExcludeUnclassifiedAppendix(){
        var d=document(new EvidenceProfile(1,null,List.of(new EvidenceProfile.Unit("procedure",2,2))));var p=Passages.extract(d,"# Heading\nInspect signing keys.\nAppendix unrelated.");
        assertEquals(1,p.size());assertEquals("Inspect signing keys.",p.getFirst().get("quote"));assertEquals(true,p.getFirst().get("role_reviewed"));assertEquals("procedure",p.getFirst().get("evidence_role"));
    }
    @Test void bundlesDeduplicateOnlyWhitespaceAndRoleWithoutErasingNegation(){
        var hits=new ArrayList<Map<String,Object>>();for(var role:List.of("procedure","procedure","procedure","prohibition","monitoring","historical")){
            String quote=hits.size()==1?"Inspect  keys.":hits.size()==2?"Do not inspect keys.":"Inspect keys.";
            hits.add(new LinkedHashMap<>(Map.of("quote",quote,"evidence_role",role,"document_id","TEST-"+hits.size())));
        }
        var chosen=Passages.bundle(hits);assertEquals(5,chosen.size());assertFalse(chosen.contains(hits.get(1)));assertTrue(chosen.contains(hits.get(2)));assertEquals(hits.getFirst(),chosen.getFirst());
    }
    @Test void disclaimersDoNotScoreAndNegationRemains(){
        assertEquals("Inspect resolution. Do not restart all replicas.",searchable("Inspect resolution. Certificate renewal is outside this procedure. Do not restart all replicas."));
        assertEquals("",searchable("This does not cover passwords."));assertEquals("I JWKS failures",query("How should I inspect JWKS failures?"));
        assertEquals("recover verify verify",normalize("Recovery verification validation"));assertEquals("Do not retry tasks on new replicas.",normalize("Do not requeue jobs on new instances."));assertEquals("jobless instantaneous",normalize("jobless instantaneous"));
    }
    @Test void exactTermsAndHttpRestrictionBoundaries(){
        var terms=exactTerms("HTTP 503 for BUILD-9 at version 2.4.0?");assertEquals(List.of("503","BUILD-9","2.4.0"),terms);assertTrue(hasExact("503: build-9, version 2.4.0.",terms));
        for(String s:List.of("5030 BUILD-9 2.4.0","503 BUILD-99 2.4.0","503 BUILD-9 2.4.01"))assertFalse(hasExact(s,terms));
        assertEquals(Set.of("401"),httpTerms("401 authentication errors"));assertTrue(httpTerms("Set capacity to 401").isEmpty());
        assertTrue(numericMatch("Inspect authentication diagnostics and JWKS.",List.of("401"),Set.of("401")));
        for(String s:List.of("For HTTP 503 errors, restart.","For HTTP 503 incidents only. For 401 see another document.","Restricted to HTTP 503. A 401 incident is different.","Only for HTTP 503 errors; 401 requires other diagnostics.","Not for HTTP 401 incidents."))assertFalse(numericMatch(s,List.of("401"),Set.of("401")),s);
        assertTrue(numericMatch("For HTTP 401 errors only, inspect keys.",List.of("401"),Set.of("401")));assertFalse(numericMatch("General authentication procedure BUILD-99.",List.of("401","BUILD-9"),Set.of("401")));
    }
    private static Map<String,Object> candidate(String id){return Map.of("document_id",id);}
    @Test void titleOnlyAndMissingConceptsCannotQualify(){
        var candidates=List.of(candidate("A-1"));
        assertTrue(rank(candidates,List.of("certificate","renew"),List.of(List.of("observe")),List.of(List.of("certificate","renew")),List.of(List.of()),.5,List.of(),5).isEmpty());
        assertTrue(rank(candidates,List.of("database","upgrade","command","schema"),List.of(List.of("database")),List.of(List.of()),List.of(List.of()),.5,List.of(),5).isEmpty());
    }
    @Test void resultCapTiesAndPolicyPriority(){
        var candidates=new ArrayList<Map<String,Object>>();for(int i=0;i<8;i++)candidates.add(candidate("A-"+i));
        assertEquals(candidates.subList(0,5),rank(candidates,List.of("pool"),Collections.nCopies(8,List.of("pool")),Collections.nCopies(8,List.of()),Collections.nCopies(8,List.of()),.5,List.of(),5));
        var two=candidates.subList(0,2);var terms=List.of("signature","verify");
        assertEquals(two.get(1),rank(two,terms,List.of(terms,terms),List.of(List.of(),List.of()),List.of(List.of(),terms),.5,List.of(),5).getFirst());
        assertEquals(two.get(0),rank(two,terms,List.of(terms,terms),List.of(List.of(),List.of()),List.of(List.of(),List.of("signature")),.5,List.of(),5).getFirst());
    }
    @Test void complementaryClausesStillNeedBodyEvidence(){
        var candidates=List.of(candidate("A-1"),candidate("B-1"));var groups=List.of(List.of("jwks","failure"),List.of("sign","key","identifier"));
        assertEquals(2,rank(candidates,List.of("jwks","failure","sign","key","identifier"),groups,List.of(List.of(),List.of()),List.of(List.of(),List.of()),.5,groups,5).size());
        assertTrue(rank(List.of(candidates.getFirst()),List.of("jwks","failure"),List.of(List.of("unrelated")),List.of(List.of("jwks","failure")),List.of(List.of()),.5,groups,5).isEmpty());
    }
}
