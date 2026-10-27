package dev.runreviq.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SelectorParityTest {
    final JsonMapper mapper = JsonMapper.builder().build();
    static String string(JsonNode n, String key) {
        return n.get(key) == null || n.get(key).isNull() ? null : n.get(key).asString();
    }
    static Boolean bool(JsonNode n, String key) {
        return n.get(key) == null || n.get(key).isNull() ? null : n.get(key).asBoolean();
    }
    @SuppressWarnings("unchecked")
    EvidenceDocument document(JsonNode n) {
        var roles = new HashSet<String>();
        n.get("allowed_roles").forEach(v -> roles.add(v.asString()));
        var claims = new ArrayList<EvidenceDocument.Claim>();
        n.get("claims").forEach(c -> claims.add(new EvidenceDocument.Claim(string(c,"action"), string(c,"rule"),
                c.has("citation") ? mapper.convertValue(c.get("citation"), Map.class) : null)));
        EvidenceProfile profile = null;
        if (n.has("evidence_profile")) {
            var p = n.get("evidence_profile");
            var scope = p.get("http_scope");
            var codes = new ArrayList<Integer>();
            scope.get("codes").forEach(v -> codes.add(v.asInt()));
            profile = new EvidenceProfile(1, new EvidenceProfile.HttpScope(string(scope,"mode"), codes), List.of());
        }
        return new EvidenceDocument(string(n,"id"), string(n,"title"), n.get("revision").asInt(),
                string(n,"path"), roles, string(n,"status"), string(n,"service"), string(n,"environment"),
                string(n,"version_min"), string(n,"version_max_exclusive"), bool(n,"key_rotation_enabled"),
                n.get("metadata_verified").asBoolean(), claims, profile);
    }
    @TestFactory Stream<DynamicTest> pythonDecisionsMatch() throws Exception {
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream("/parity/selector.json")) { fixture = mapper.readTree(input); }
        var tests = new ArrayList<DynamicTest>();
        for (var c : fixture.get("cases")) {
            var docs = new ArrayList<EvidenceDocument>();
            fixture.get("catalogs").get(c.get("catalog").asString()).forEach(d -> docs.add(document(d)));
            var ctx = c.get("context");
            var context = new SelectionContext(string(ctx,"role"), string(ctx,"service"), string(ctx,"software_version"),
                    string(ctx,"environment"), bool(ctx,"key_rotation_enabled"),
                    ctx.get("http_status").isNull() ? null : ctx.get("http_status").asInt());
            tests.add(DynamicTest.dynamicTest(c.get("catalog").asString() + " " + context,
                    () -> assertEquals(c.get("expected"), mapper.valueToTree(new ApplicabilitySelector().select(docs, context)))));
        }
        return tests.stream();
    }
    @Test void unauthorizedMalformedMetadataIsNeverInspected() {
        var secret = new EvidenceDocument("secret", "Restricted title", 1, "restricted.md", Set.of("security"),
                "approved", "identity-service", "production", "malformed", "malformed", true, true, List.of(), null);
        var context = new SelectionContext("platform","identity-service","2.4.0","production",true,401);
        assertEquals(new ApplicabilitySelector().select(List.of(), context),
                     new ApplicabilitySelector().select(List.of(secret), context));
    }
    @Test void rejectsInvalidStructures() {
        assertThrows(IllegalArgumentException.class, () -> SoftwareVersion.parse("2.4"));
        assertTrue(SoftwareVersion.parse("99999999999999999999.0.0").compareTo(SoftwareVersion.parse("2.4.0")) > 0);
        assertThrows(IllegalArgumentException.class, () -> new EvidenceProfile.HttpScope("only", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceProfile.HttpScope("general", List.of(401)));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceProfile.HttpScope("only", List.of(401,401)));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceProfile(1,null,List.of(
                new EvidenceProfile.Unit("procedure",1,3), new EvidenceProfile.Unit("monitoring",3,4))));
        assertThrows(IllegalArgumentException.class, () -> new SelectionContext("admin","x","2.4.0","production",null,null));
    }
}
