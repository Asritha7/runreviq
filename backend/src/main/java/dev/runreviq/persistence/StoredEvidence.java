package dev.runreviq.persistence;

import dev.runreviq.domain.*;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validation happens only after SQL RLS has selected authorized rows. */
public final class StoredEvidence {
    static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private StoredEvidence() {}
    private static boolean space(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }
    private static boolean blank(String text) { return text.codePoints().allMatch(StoredEvidence::space); }
    private static String stripLeading(String text) {
        int index=0;
        while(index<text.length() && space(text.codePointAt(index))) index+=Character.charCount(text.codePointAt(index));
        return text.substring(index);
    }
    static String string(JsonNode n, String key) {
        var v = n.get(key); if (v == null || !v.isString() || blank(v.asString())) throw new EvidenceUnavailable();
        return v.asString();
    }
    static int integer(JsonNode n, String key) {
        var v = n.get(key); if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) throw new EvidenceUnavailable();
        return v.asInt();
    }
    static String nullableString(JsonNode n, String key) { return n.get(key).isNull() ? null : string(n,key); }
    static Boolean nullableBool(JsonNode n, String key) {
        var v=n.get(key); if(v==null || (!v.isBoolean() && !v.isNull())) throw new EvidenceUnavailable();
        return v.isNull() ? null : v.asBoolean();
    }
    static void fields(JsonNode n, Set<String> keys) {
        if (!n.isObject() || !Set.copyOf(n.propertyNames()).equals(keys)) throw new EvidenceUnavailable();
    }
    public static List<String> lines(String content) {
        // Python str.splitlines, including Unicode line separators and no terminal empty item.
        if (content.isEmpty()) return List.of();
        var result=new ArrayList<>(Arrays.asList(content.split("\\r\\n|[\\n\\r\\u000b\\f\\u001c-\\u001e\\u0085\\u2028\\u2029]",-1)));
        if(result.getLast().isEmpty()) result.removeLast(); return result;
    }
    @SuppressWarnings("unchecked")
    public static EvidenceDocument validate(JsonNode metadata, String content, String checksum,
            String id, int revision, Set<String> roles) {
        var expected=new HashSet<>(Set.of("id","title","revision","path","status","service","environment",
                "version_min","version_max_exclusive","key_rotation_enabled","metadata_verified","claims"));
        if(metadata.has("evidence_profile")) expected.add("evidence_profile"); fields(metadata,expected);
        if(!id.matches("[A-Z]+-[0-9]+") || !id.equals(string(metadata,"id")) || revision<1
                || revision!=integer(metadata,"revision") || content.length()>1_000_000
                || !ReaderConnections.ROLES.containsAll(roles)) throw new EvidenceUnavailable();
        var envelope=JSON.createObjectNode().set("metadata",metadata);
        envelope.put("content",content);
        if(!PythonJson.sha256(PythonJson.encode(envelope)).equals(checksum)) throw new EvidenceUnavailable();
        String environment=nullableString(metadata,"environment"), min=nullableString(metadata,"version_min"), max=nullableString(metadata,"version_max_exclusive");
        if(environment!=null && !Set.of("production","staging").contains(environment)) throw new EvidenceUnavailable();
        if(min!=null) SoftwareVersion.parse(min); if(max!=null) SoftwareVersion.parse(max);
        if(min!=null && max!=null && SoftwareVersion.parse(min).compareTo(SoftwareVersion.parse(max))>=0) throw new EvidenceUnavailable();
        String status=string(metadata,"status");
        if(!Set.of("approved","draft","superseded","revoked").contains(status) || !metadata.get("metadata_verified").isBoolean()) throw new EvidenceUnavailable();
        var sourceLines=lines(content); String sourceHash=PythonJson.sha256(content);
        var claims=new ArrayList<EvidenceDocument.Claim>();
        if(!metadata.get("claims").isArray()) throw new EvidenceUnavailable();
        for(var claim:metadata.get("claims")) {
            fields(claim,claim.has("citation") ? Set.of("action","rule","citation") : Set.of("action","rule"));
            String action=string(claim,"action"), rule=string(claim,"rule");
            if(!action.matches("[a-z_]+") || !Set.of("must","must_not").contains(rule)) throw new EvidenceUnavailable();
            Map<String,Object> citation=null;
            if(claim.has("citation")) {
                var c=claim.get("citation");fields(c,Set.of("start_line","end_line","quote","source_sha256"));
                int start=integer(c,"start_line"), end=integer(c,"end_line");
                if(start<1 || end<start || end>sourceLines.size() || !string(c,"source_sha256").equals(sourceHash)
                        || !string(c,"quote").equals(String.join("\n",sourceLines.subList(start-1,end)))) throw new EvidenceUnavailable();
                citation=JSON.convertValue(c,Map.class);
            }
            claims.add(new EvidenceDocument.Claim(action,rule,citation));
        }
        EvidenceProfile profile=null;
        if(metadata.has("evidence_profile")) {
            var p=metadata.get("evidence_profile");fields(p,Set.of("schema_version","http_scope","units"));
            EvidenceProfile.HttpScope scope=null;
            if(!p.get("http_scope").isNull()) {
                var h=p.get("http_scope");fields(h,Set.of("mode","codes"));
                if(!h.get("codes").isArray()) throw new EvidenceUnavailable();
                var codes=new ArrayList<Integer>();
                for(var code:h.get("codes")) {if(!code.isIntegralNumber() || !code.canConvertToInt()) throw new EvidenceUnavailable(); codes.add(code.asInt());}
                scope=new EvidenceProfile.HttpScope(string(h,"mode"),codes);
            }
            if(!p.get("units").isArray()) throw new EvidenceUnavailable();
            var units=new ArrayList<EvidenceProfile.Unit>();
            for(var u:p.get("units")) {
                fields(u,Set.of("role","start_line","end_line"));
                var unit=new EvidenceProfile.Unit(string(u,"role"),integer(u,"start_line"),integer(u,"end_line"));
                if(unit.endLine()>sourceLines.size() || sourceLines.subList(unit.startLine()-1,unit.endLine()).stream()
                        .noneMatch(line -> !blank(line) && !stripLeading(line).startsWith("#"))) throw new EvidenceUnavailable();
                if(unit.role().equals("prohibition") && claims.stream().noneMatch(c -> c.rule().equals("must_not") && c.citation()!=null
                        && unit.startLine()<=((Number)c.citation().get("start_line")).intValue()
                        && ((Number)c.citation().get("end_line")).intValue()<=unit.endLine())) throw new EvidenceUnavailable();
                units.add(unit);
            }
            profile=new EvidenceProfile(integer(p,"schema_version"),scope,units);
        }
        return new EvidenceDocument(id,string(metadata,"title"),revision,string(metadata,"path"),roles,status,
                string(metadata,"service"),environment,min,max,nullableBool(metadata,"key_rotation_enabled"),
                metadata.get("metadata_verified").asBoolean(),claims,profile);
    }
}
