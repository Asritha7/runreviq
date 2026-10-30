package dev.runreviq.persistence;

import dev.runreviq.domain.*;
import dev.runreviq.search.*;
import dev.runreviq.authoring.MetadataProposal;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import static dev.runreviq.search.RetrievalTrace.*;
import java.util.regex.Pattern;
import static dev.runreviq.search.LexicalRanking.*;

/** All source loading, applicability and English stemming share the reader snapshot. */
final class SearchRepository {
    private SearchRepository() {}
    static SelectionContext context(String question,SelectionContext c){
        if(question==null||MetadataProposal.strip(question).isEmpty()||MetadataProposal.strip(question).codePointCount(0,MetadataProposal.strip(question).length())>500)throw new IllegalArgumentException();
        var codes=httpTerms(question);Integer status=c.httpStatus();
        if(status!=null&&!codes.isEmpty()&&!codes.equals(Set.of(status.toString())))throw new IllegalArgumentException();
        if(status==null&&codes.size()==1)status=Integer.valueOf(codes.iterator().next());
        return new SelectionContext(c.role(),c.service(),c.softwareVersion(),c.environment(),c.keyRotationEnabled(),status);
    }
    private static List<List<String>> stem(Connection c,List<String> texts)throws Exception{
        var result=new ArrayList<List<String>>();
        try(var q=c.prepareStatement("SELECT tsvector_to_array(to_tsvector('english',value)) FROM jsonb_array_elements_text(?::jsonb) WITH ORDINALITY t(value,position) ORDER BY position")){
            q.setString(1,StoredEvidence.JSON.writeValueAsString(texts));try(var rows=q.executeQuery()){while(rows.next()){var a=rows.getArray(1);try{result.add(Arrays.asList((String[])a.getArray()));}finally{a.free();}}}
        }return result;
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> search(Connection c,List<EvidenceRepository.Source> sources,String question,SelectionContext context,Integer explicit)throws Exception{
        return search(c,sources,question,context,explicit,false);
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> search(Connection c,List<EvidenceRepository.Source> sources,String question,SelectionContext context,Integer explicit,boolean focused)throws Exception{
        return search(c,sources,question,context,explicit,focused,null);
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> search(Connection c,List<EvidenceRepository.Source> sources,String question,SelectionContext context,Integer explicit,boolean focused,Consumer<Map<String,Object>> trace)throws Exception{
        return search(c,sources,question,context,explicit,focused,trace,false);
    }
    /** Isolated evaluator experiment only; not wired to runtime configuration or HTTP. */
    @SuppressWarnings("unchecked")
    static Map<String,Object> search(Connection c,List<EvidenceRepository.Source> sources,String question,SelectionContext context,Integer explicit,boolean focused,Consumer<Map<String,Object>> trace,boolean passageScoped)throws Exception{
        return search(c,sources,question,context,explicit,focused,trace,passageScoped,false);
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> search(Connection c,List<EvidenceRepository.Source> sources,String question,SelectionContext context,Integer explicit,boolean focused,Consumer<Map<String,Object>> trace,boolean passageScoped,boolean relaxed)throws Exception{
        if(relaxed&&(focused||passageScoped))throw new IllegalArgumentException("Experiment arms must remain separate");
        if(trace!=null)emit(trace,"authorized_snapshot","documents",sources.stream().map(s->Map.of("document_id",s.document().id(),"revision",s.document().revision(),"source_sha256",PythonJson.sha256(s.content()))).toList());
        var result=new LinkedHashMap<>(new ApplicabilitySelector().select(sources.stream().map(EvidenceRepository.Source::document).toList(),context));
        result.put("passages",List.of());result.put("retrieval_method",relaxed?"experimental_relaxed_lexical_v1":passageScoped?"experimental_passage_scope_v1":focused?"postgres_english_focused_v6":"postgres_english_reviewed_v5");result.put("http_status_context",context.httpStatus());result.put("http_status_source",explicit!=null?"provided":context.httpStatus()!=null?"question":"unknown");
        if(trace!=null)emit(trace,"applicability","status",result.get("status"),"applicable_documents",result.get("applicable_documents"),"recommended_documents",result.get("recommended_documents"),"uncertain_documents",result.get("uncertain_documents"));
        if(!result.get("status").equals("selected"))return result;
        var recommended=(List<String>)result.get("recommended_documents");var candidates=new ArrayList<Map<String,Object>>();var byId=new HashMap<String,EvidenceRepository.Source>();
        for(var source:sources){byId.put(source.document().id(),source);if(recommended.contains(source.document().id()))candidates.addAll(Passages.extract(source.document(),source.content()));}
        if(trace!=null)emit(trace,"candidate_generation","passages",candidates.stream().map(RetrievalTrace::key).toList());
        String scoring=pattern("(?<![\\w.-])"+Pattern.quote(context.service())+"(?![\\w.-])").matcher(question).replaceAll(" ");
        scoring=pattern("(?<![\\w.-])v?"+Pattern.quote(context.softwareVersion())+"(?![\\w.-])").matcher(scoring).replaceAll(" ");
        if(focused)scoring=pattern("\\bconfirms?\\b").matcher(scoring).replaceAll("verify");
        if(focused)scoring=pattern("\\bchecks\\b|\\b(?:is|are)\\s+working\\b").matcher(scoring).replaceAll(" ");
        var normalized=stem(c,List.of(normalize(query(scoring)))).getFirst();
        var clauses=pattern("\\b(?:while|whereas|as well as|and also)\\b").split(scoring,-1);
        var groups=clauses.length>1?stem(c,Arrays.stream(clauses).map(s->normalize(query(s))).toList()):List.<List<String>>of();
        var texts=new ArrayList<String>();var headings=new ArrayList<String>();
        for(var p:candidates){
            var source=byId.get((String)p.get("document_id"));int start=(Integer)p.get("start_line"),end=(Integer)p.get("end_line");String heading="";
            var lines=StoredEvidence.lines(source.content());for(int n=0;n<start;n++)if(MetadataProposal.strip(lines.get(n)).startsWith("#"))heading=MetadataProposal.strip(lines.get(n).replaceFirst("^[# ]+",""));
            headings.add(heading);
            var actions=new ArrayList<String>();for(var claim:source.document().claims())if(source.document().metadataVerified()&&claim.rule().equals("must_not")&&claim.citation()!=null
                &&start<=((Number)claim.citation().get("start_line")).intValue()&&((Number)claim.citation().get("end_line")).intValue()<=end)actions.add(claim.action().replace('_',' '));
            texts.add(normalize(searchable((String)p.get("quote"))));texts.add(normalize(source.document().title()+" "+heading));texts.add(normalize((String)p.get("quote")));texts.add(normalize(String.join(" ",actions)));
        }
        var terms=stem(c,texts);var required=exactTerms(question).stream().filter(t->!t.equals(context.softwareVersion())).toList();var codes=httpTerms(question);if(codes.isEmpty()&&context.httpStatus()!=null)codes=Set.of(context.httpStatus().toString());
        if(trace!=null)emit(trace,"lexical_query","normalized_terms",normalized,"exact_terms",required,"http_codes",codes);
        var matching=new HashSet<String>();var codeFree=new HashSet<String>();
        for(var source:sources){var d=source.document();boolean reviewed=d.metadataVerified()&&d.profile()!=null&&d.profile().httpScope()!=null;
            var nonCodes=new ArrayList<>(required);nonCodes.removeAll(codes);
            if(reviewed?hasExact(source.content(),nonCodes):numericMatch(source.content(),required,codes))matching.add(d.id());
            if(!codes.isEmpty()&&!hasExact(source.content(),codes))codeFree.add(d.id());
        }
        var supported=new HashSet<String>();var mentioned=new HashSet<String>();
        for(int i=0;i<candidates.size();i++)if(matching.contains(candidates.get(i).get("document_id"))){supported.addAll(terms.get(i*4));supported.addAll(terms.get(i*4+1));mentioned.addAll(terms.get(i*4+2));}
        mentioned.removeAll(supported);boolean excluded=!Collections.disjoint(normalized,mentioned);
        var generic=new HashSet<>(codes);generic.addAll(Set.of("http","status","respons","error","failur"));var substantive=new HashSet<>(normalized);substantive.removeAll(generic);
        var rankingQuery=new ArrayList<>(normalized);rankingQuery.removeAll(codes);rankingQuery.remove("http");var meaningful=new HashSet<>(rankingQuery);meaningful.removeAll(generic);if(codes.isEmpty()||meaningful.size()<2)rankingQuery=new ArrayList<>(normalized);
        var rankable=new ArrayList<Map<String,Object>>();var bodies=new ArrayList<List<String>>();var contexts=new ArrayList<List<String>>();var policies=new ArrayList<List<String>>();var rankHeadings=new ArrayList<String>();
        for(int i=0;i<candidates.size();i++){var p=candidates.get(i);
            Set<String> localUnsupported=Set.of();
            if(passageScoped){localUnsupported=new HashSet<>(terms.get(i*4+2));localUnsupported.removeAll(terms.get(i*4));localUnsupported.removeAll(terms.get(i*4+1));}
            boolean localExcluded=passageScoped&&!Collections.disjoint(normalized,localUnsupported);
            if(trace!=null)emit(trace,"lexical_gate","passage",key(p),"body_terms",terms.get(i*4),"full_quote_terms",terms.get(i*4+2),"context_terms",terms.get(i*4+1),"exact_match",matching.contains(p.get("document_id")),"http_code_absent",codeFree.contains(p.get("document_id")),"substantive_overlap",intersection(substantive,terms.get(i*4)),"decision",!matching.contains(p.get("document_id"))?"exact_terms":codeFree.contains(p.get("document_id"))&&intersection(substantive,terms.get(i*4)).size()<2?"http_substantive_overlap":localExcluded?"passage_excluded_terms":"eligible","passage_trigger_terms",passageScoped?intersection(normalized,localUnsupported):Set.of());
            if(matching.contains(p.get("document_id"))&&(!codeFree.contains(p.get("document_id"))||intersection(substantive,terms.get(i*4)).size()>=2)&&!localExcluded){rankable.add(p);bodies.add(terms.get(i*4));contexts.add(terms.get(i*4+1));policies.add(terms.get(i*4+3));rankHeadings.add(headings.get(i));}}
        boolean reviewed=candidates.stream().anyMatch(p->Boolean.TRUE.equals(p.get("role_reviewed")));
        if(trace!=null)emit(trace,"exclusion_gate","excluded",excluded&&!passageScoped,"scope",passageScoped?"passage":"global","trigger_terms",intersection(normalized,mentioned));
        var hits=excluded&&!passageScoped?List.<Map<String,Object>>of():rank(rankable,rankingQuery,bodies,contexts,policies,contains("\\b(?:commands?|sql|every|universal|exactly)\\b",question)?.50:.35,groups,reviewed?20:5,focused?question:null,rankHeadings,trace,relaxed);
        var beforeAssembly=trace==null?List.<Map<String,Object>>of():hits.stream().map(RetrievalTrace::key).toList();
        if(reviewed)hits=Passages.bundle(hits,trace);
        if(trace!=null)emit(trace,"assembly","reviewed_bundle",reviewed,"input",beforeAssembly,"output",hits.stream().map(RetrievalTrace::key).toList());
        for(var hit:hits){var d=byId.get((String)hit.get("document_id")).document();hit.put("usage_note",Passages.NOTES.get(hit.get("evidence_role")));
            var applicability=new LinkedHashMap<String,Object>();applicability.put("service",d.service());applicability.put("environment",d.environment());applicability.put("version_min",d.versionMin());applicability.put("version_max_exclusive",d.versionMaxExclusive());applicability.put("key_rotation_enabled",d.keyRotationEnabled());
            var scope=d.profile()==null?null:d.profile().httpScope();applicability.put("http_scope",scope==null?null:Map.of("mode",scope.mode(),"codes",scope.codes()));hit.put("applicability",applicability);
        }
        var summary=new LinkedHashMap<String,Object>();for(String role:Passages.NOTES.keySet()){int count=0;for(var hit:hits)if(role.equals(hit.get("evidence_role")))count++;summary.put(role,count);}
        result.put("evidence_summary",summary);result.put("passages",hits);var selected=hits.stream().map(p->(String)p.get("document_id")).distinct().toList();result.put("recommended_documents",selected);
        result.put("evidence",((List<Map<String,Object>>)result.get("evidence")).stream().filter(e->selected.contains(e.get("document_id"))).toList());
        if(!hits.isEmpty())result.put("message","Found matching source passages. These are excerpts, not an answer or proof of the cause.");
        else {result.put("status","insufficient_evidence");result.put("message","No sufficiently relevant passages in approved guidance accessible for this context.");result.put("next_steps",List.of("Try concrete terms from the incident, check the context, or request an approved source."));}
        if(trace!=null)emit(trace,"final","status",result.get("status"),"passages",hits.stream().map(RetrievalTrace::key).toList());
        return result;
    }
}
