package dev.runreviq.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class StoredEvidenceTest {
    static final String CONTENT="# Guidance\nDo not restart.\nCheck keys.\n";
    ObjectNode metadata() {
        return (ObjectNode)StoredEvidence.JSON.readTree("""
            {"id":"RB-001","title":"Guidance","revision":1,"path":"submission:test","status":"approved",
             "service":"identity-service","environment":"production","version_min":"2.4.0","version_max_exclusive":"2.5.0",
             "key_rotation_enabled":true,"metadata_verified":true,"claims":[],
             "evidence_profile":{"schema_version":1,"http_scope":{"mode":"only","codes":[401]},
              "units":[{"role":"procedure","start_line":2,"end_line":3}]}}
            """);
    }
    String checksum(ObjectNode n,String content) {
        var envelope=StoredEvidence.JSON.createObjectNode().set("metadata",n);envelope.put("content",content);
        return PythonJson.sha256(PythonJson.encode(envelope));
    }
    void validate(ObjectNode n,String content,String hash) {StoredEvidence.validate(n,content,hash,"RB-001",1,Set.of("platform"));}
    @Test void rejectsTamperingAndUnsupportedMetadata() {
        var n=metadata();String hash=checksum(n,CONTENT);validate(n,CONTENT,hash);
        assertThrows(EvidenceUnavailable.class,()->validate(n,CONTENT+"tamper",hash));
        n.put("title","changed");assertThrows(EvidenceUnavailable.class,()->validate(n,CONTENT,hash));
        n.put("unknown_authority","security");assertThrows(EvidenceUnavailable.class,()->validate(n,CONTENT,checksum(n,CONTENT)));
    }
    @Test void validatesCitedQuotesFingerprintsAndProhibitionRanges() {
        var n=metadata();
        var claims=n.putArray("claims");var claim=claims.addObject().put("action","restart").put("rule","must_not");
        var citation=claim.putObject("citation").put("start_line",2).put("end_line",2).put("quote","Do not restart.").put("source_sha256",PythonJson.sha256(CONTENT));
        ((ObjectNode)n.get("evidence_profile").get("units").get(0)).put("role","prohibition");
        validate(n,CONTENT,checksum(n,CONTENT));
        citation.put("quote","Invented instruction");assertThrows(EvidenceUnavailable.class,()->validate(n,CONTENT,checksum(n,CONTENT)));
        citation.put("quote","Do not restart.").put("source_sha256","0".repeat(64));
        assertThrows(EvidenceUnavailable.class,()->validate(n,CONTENT,checksum(n,CONTENT)));
        citation.put("source_sha256",PythonJson.sha256(CONTENT));claim.put("rule","must");
        assertThrows(EvidenceUnavailable.class,()->validate(n,CONTENT,checksum(n,CONTENT)));
    }
    @Test void rejectsOutOfSourceHeadingOnlyOverlappingAndBooleanRanges() {
        for(int[] range:List.of(new int[]{1,1},new int[]{2,4})) {
            var n=metadata();var u=(ObjectNode)n.get("evidence_profile").get("units").get(0);u.put("start_line",range[0]).put("end_line",range[1]);
            assertThrows(EvidenceUnavailable.class,()->validate(n,CONTENT,checksum(n,CONTENT)));
        }
        var heading=metadata();
        String unicodeHeading="# Guidance\n\u00a0# Not source text\n\u0085";
        assertThrows(EvidenceUnavailable.class,()->validate(heading,unicodeHeading,checksum(heading,unicodeHeading)));
        var n=metadata();((ObjectNode)n.get("evidence_profile")).put("schema_version",true);
        assertThrows(EvidenceUnavailable.class,()->validate(n,CONTENT,checksum(n,CONTENT)));
        assertEquals(List.of("a","b","c","d"),StoredEvidence.lines("a\r\nb\u0085c\u2028d\n"));
    }
}
