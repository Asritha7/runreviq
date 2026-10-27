package dev.runreviq.authoring;

import dev.runreviq.domain.*;
import dev.runreviq.persistence.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Explicit Markdown fields are proposals only; neither text nor review grants authority. */
public final class MetadataProposal {
    public static final List<String> FIELDS=List.of("service","environment","version_min","version_max_exclusive","key_rotation_enabled");
    private MetadataProposal() {}
    public static boolean space(int c) { return Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85; }
    public static String strip(String s) {
        int a=0,b=s.length();
        while(a<b && space(s.codePointAt(a))) a+=Character.charCount(s.codePointAt(a));
        while(b>a && space(s.codePointBefore(b))) b-=Character.charCount(s.codePointBefore(b));
        return s.substring(a,b);
    }
    public static void validText(String text) {
        if(text==null || strip(text).isEmpty() || text.indexOf(0)>=0 || text.getBytes(StandardCharsets.UTF_8).length>1_000_000)
            throw new DraftFailure(422,"Expected nonempty UTF-8 Markdown with a simple .md filename");
        // Reject unpaired surrogate input instead of allowing encoder replacement to alter a fingerprint.
        for(int i=0;i<text.length();i++) if(Character.isSurrogate(text.charAt(i))) {
            if(!Character.isHighSurrogate(text.charAt(i)) || i+1>=text.length() || !Character.isLowSurrogate(text.charAt(++i))) throw DraftFailure.invalid();
        }
    }
    public static Object parse(String field,String raw) {
        switch(field) {
            case "service": if(raw.matches("[A-Za-z0-9_-]{1,100}")) return raw; break;
            case "environment": if(Set.of("production","staging").contains(raw)) return raw; break;
            case "version_min", "version_max_exclusive": if(raw.length()<=32) {SoftwareVersion.parse(raw);return raw;} break;
            case "key_rotation_enabled": if(raw.equals("none")) return null; if(raw.equals("true"))return true;if(raw.equals("false"))return false;break;
        }
        throw new IllegalArgumentException("Invalid metadata");
    }
    private static Map<String,String> issue(String field,String reason) {return Map.of("field",field,"reason",reason);}
    private static void range(Map<String,Object> values,List<Map<String,String>> issues) {
        if(values.containsKey("version_min") && values.containsKey("version_max_exclusive") &&
                SoftwareVersion.parse((String)values.get("version_min")).compareTo(SoftwareVersion.parse((String)values.get("version_max_exclusive")))>=0) {
            values.remove("version_min");values.remove("version_max_exclusive");issues.add(issue("version_range","empty_or_inverted"));
        }
    }
    public static Map<String,Object> extract(String text) {
        validText(text);
        var candidates=new LinkedHashMap<String,List<Map<String,Object>>>();
        FIELDS.forEach(f->candidates.put(f,new ArrayList<>()));
        var titles=new ArrayList<Map<String,Object>>();boolean active=false;String fence=null;
        var marker=Pattern.compile("^(`{3,}|~{3,})");
        var heading=Pattern.compile("^#{1,2}\\s",Pattern.UNICODE_CHARACTER_CLASS);
        var fieldPattern=Pattern.compile("\\s*(?:- )?([a-z_]+):\\s*(.*?)\\s*",Pattern.UNICODE_CHARACTER_CLASS);
        var lines=StoredEvidence.lines(text);
        for(int i=0;i<lines.size();i++) {
            String line=lines.get(i),stripped=strip(line);var m=marker.matcher(stripped);
            if(m.find()) {String value=m.group(1);if(fence==null)fence=value;else if(value.charAt(0)==fence.charAt(0)&&value.length()>=fence.length())fence=null;continue;}
            if(fence!=null)continue;
            if(line.startsWith("# "))titles.add(Map.of("value",strip(line.substring(2)),"line",i+1,"quote",line));
            if(heading.matcher(line).find()) {active=stripped.equals("## Applicability");continue;}
            if(!active)continue;
            m=fieldPattern.matcher(line);
            if(m.matches()&&candidates.containsKey(m.group(1)))candidates.get(m.group(1)).add(Map.of("raw",m.group(2),"line",i+1,"quote",line));
        }
        var values=new LinkedHashMap<String,Object>();var provenance=new LinkedHashMap<String,Object>();
        var issues=new ArrayList<Map<String,String>>();provenance.put("title",titles);
        if(titles.size()==1&&!titles.getFirst().get("value").equals(""))values.put("title",titles.getFirst().get("value"));
        else issues.add(issue("title","missing_or_ambiguous"));
        candidates.forEach((field,entries)->{
            provenance.put(field,entries);
            if(entries.size()!=1)issues.add(issue(field,entries.isEmpty()?"missing":"ambiguous"));
            else try {values.put(field,parse(field,(String)entries.getFirst().get("raw")));}catch(IllegalArgumentException e){issues.add(issue(field,"invalid_value"));}
        });
        range(values,issues);
        return Map.of("schema_version",1,"extractor","explicit-markdown-v1","source_sha256",PythonJson.sha256(text),
                "review_required",true,"proposed_metadata",values,"provenance",provenance,"issues",issues);
    }
    public static Map<String,Object> review(JsonNode metadata,String content) {
        try {
            var required=new HashSet<>(FIELDS);required.add("title");var allowed=new HashSet<>(required);allowed.add("evidence_profile");
            if(!metadata.isObject()||!metadata.propertyNames().containsAll(required)||!allowed.containsAll(metadata.propertyNames()))throw DraftFailure.invalid();
            var values=new LinkedHashMap<String,Object>();var issues=new ArrayList<Map<String,String>>();
            if(metadata.has("evidence_profile")){validateProfile(metadata.get("evidence_profile"),content);values.put("evidence_profile",metadata.get("evidence_profile"));}
            for(String field:metadata.propertyNames()) {
                if(field.equals("evidence_profile"))continue;
                var node=metadata.get(field);
                if(!node.isString()||node.asString().codePointCount(0,node.asString().length())>200||node.asString().indexOf(0)>=0)throw DraftFailure.invalid();
                String raw=strip(node.asString());
                if(raw.isEmpty()){issues.add(issue(field,"missing"));continue;}
                try {values.put(field,field.equals("title")?raw:parse(field,raw));}catch(IllegalArgumentException e){issues.add(issue(field,"invalid_value"));}
            }
            range(values,issues);
            return new LinkedHashMap<>(Map.of("metadata",metadata,"validated_metadata",values,"issues",issues,"review_required",true,"status","in_progress"));
        } catch(DraftFailure e){throw e;}catch(Exception e){throw DraftFailure.invalid();}
    }
    private static void fields(JsonNode n,Set<String> fields) {if(n==null||!n.isObject()||!Set.copyOf(n.propertyNames()).equals(fields))throw DraftFailure.invalid();}
    private static int integer(JsonNode n){if(n==null||!n.isIntegralNumber()||!n.canConvertToInt())throw DraftFailure.invalid();return n.asInt();}
    private static String string(JsonNode n){if(n==null||!n.isString())throw DraftFailure.invalid();return n.asString();}
    private static void validateProfile(JsonNode p,String content) {
        fields(p,Set.of("schema_version","http_scope","units"));EvidenceProfile.HttpScope scope=null;
        var h=p.get("http_scope");
        if(!h.isNull()) {fields(h,Set.of("mode","codes"));if(!h.get("codes").isArray())throw DraftFailure.invalid();
            var codes=new ArrayList<Integer>();h.get("codes").forEach(n->codes.add(integer(n)));scope=new EvidenceProfile.HttpScope(string(h.get("mode")),codes);}
        if(!p.get("units").isArray())throw DraftFailure.invalid();var units=new ArrayList<EvidenceProfile.Unit>();
        for(var u:p.get("units")) {
            fields(u,Set.of("role","start_line","end_line"));var unit=new EvidenceProfile.Unit(string(u.get("role")),integer(u.get("start_line")),integer(u.get("end_line")));
            if(content!=null) {var lines=StoredEvidence.lines(content);
                if(unit.endLine()>lines.size()||lines.subList(unit.startLine()-1,unit.endLine()).stream().noneMatch(l->!strip(l).isEmpty()&&!strip(l).startsWith("#")))throw DraftFailure.invalid();}
            units.add(unit);
        }
        new EvidenceProfile(integer(p.get("schema_version")),scope,units);
    }
}
