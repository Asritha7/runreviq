package dev.runreviq.authoring;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

class MetadataProposalTest {
    @TestFactory Stream<DynamicTest> pythonParity() throws Exception {
        var fixture=DraftRepository.JSON.readTree(getClass().getResourceAsStream("/authoring-parity.json"));var tests=new ArrayList<DynamicTest>();
        for(var c:fixture.get("extraction"))tests.add(DynamicTest.dynamicTest("extract "+tests.size(),()->assertEquals(c.get("expected"),DraftRepository.JSON.valueToTree(MetadataProposal.extract(c.get("source").asString())))));
        for(var c:fixture.get("reviews"))tests.add(DynamicTest.dynamicTest("review "+tests.size(),()->assertEquals(c.get("expected"),DraftRepository.JSON.valueToTree(MetadataProposal.review(c.get("metadata"),c.get("source").asString())))));
        return tests.stream();
    }
    @Test void rejectsAuthorityFieldsInvalidProfilesAndUnpairedSurrogates()throws Exception {
        var fixture=DraftRepository.JSON.readTree(getClass().getResourceAsStream("/authoring-parity.json"));
        var metadata=fixture.get("reviews").get(0).get("metadata").deepCopy();
        ((tools.jackson.databind.node.ObjectNode)metadata).put("metadata_verified",true);
        assertThrows(DraftFailure.class,()->MetadataProposal.review(metadata,null));
        for(String profile:List.of("null","{}","{\"schema_version\":true,\"http_scope\":null,\"units\":[]}",
                "{\"schema_version\":1,\"http_scope\":null,\"units\":[{\"role\":\"procedure\",\"start_line\":1,\"end_line\":1}]}",
                "{\"schema_version\":1,\"http_scope\":null,\"units\":[{\"role\":\"procedure\",\"start_line\":99,\"end_line\":99}]}")){
            var m=(tools.jackson.databind.node.ObjectNode)fixture.get("reviews").get(0).get("metadata").deepCopy();m.set("evidence_profile",DraftRepository.JSON.readTree(profile));
            assertThrows(DraftFailure.class,()->MetadataProposal.review(m,"# Heading\ncontent"));
        }
        assertThrows(DraftFailure.class,()->MetadataProposal.extract("\ud800"));
        assertThrows(DraftFailure.class,()->MetadataProposal.extract("é".repeat(500_001)));
        assertNotEquals(DraftRepository.owner("demo","same"),DraftRepository.owner("google","same"));
    }
}
