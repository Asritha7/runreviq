package dev.runreviq.search;

import dev.runreviq.domain.EvidenceDocument;
import dev.runreviq.persistence.*;
import dev.runreviq.authoring.MetadataProposal;
import java.util.*;

public final class Passages {
    private Passages() {}
    public static final Map<String,String> NOTES;
    static {
        var notes=new LinkedHashMap<String,String>();
        notes.put("procedure","Reviewed procedure; follow the source prerequisites and approvals.");
        notes.put("prohibition","Reviewed restriction; this passage does not authorize the prohibited action.");
        notes.put("historical","Historical context is a hypothesis, not confirmation of the current cause.");
        notes.put("monitoring","Monitoring reference only; Runreviq has not queried live telemetry.");
        notes.put("unclassified","Evidence type has not been reviewed; inspect the full source before acting.");
        NOTES=Collections.unmodifiableMap(notes);
    }
    public static List<Map<String,Object>> extract(EvidenceDocument doc,String content){
        var lines=StoredEvidence.lines(content);var result=new ArrayList<Map<String,Object>>();String hash=PythonJson.sha256(content);
        if(doc.profile()!=null&&!doc.profile().units().isEmpty()){
            for(var unit:doc.profile().units())result.add(passage(doc,lines,hash,unit.startLine(),unit.endLine(),unit.role(),true));
        }else{
            int start=-1;
            for(int i=0;i<=lines.size();i++){
                boolean boundary=i==lines.size()||MetadataProposal.strip(lines.get(i)).isEmpty()||MetadataProposal.strip(lines.get(i)).startsWith("#");
                if(start>=0&&(boundary||i-start==20)){result.add(passage(doc,lines,hash,start+1,i,"unclassified",false));start=-1;}
                if(!boundary&&start<0)start=i;
            }
        }return result;
    }
    private static Map<String,Object> passage(EvidenceDocument doc,List<String> lines,String hash,int start,int end,String role,boolean reviewed){
        var p=new LinkedHashMap<String,Object>();p.put("start_line",start);p.put("end_line",end);p.put("quote",String.join("\n",lines.subList(start-1,end)));p.put("source_sha256",hash);
        p.put("evidence_role",role);p.put("role_reviewed",reviewed);p.put("document_id",doc.id());p.put("revision",doc.revision());p.put("title",doc.title());return p;
    }
    public static List<Map<String,Object>> bundle(List<Map<String,Object>> hits){
        var unique=new ArrayList<Map<String,Object>>();var seen=new HashSet<List<String>>();
        for(var p:hits){var key=List.of((String)p.get("evidence_role"),LexicalRanking.pattern("[\\s\\x1c-\\x1f]+").matcher(MetadataProposal.strip((String)p.get("quote"))).replaceAll(" "));if(seen.add(key))unique.add(p);}
        var chosen=new ArrayList<Map<String,Object>>();for(String role:List.of("procedure","prohibition","monitoring","historical"))unique.stream().filter(p->role.equals(p.get("evidence_role"))).findFirst().ifPresent(chosen::add);
        for(var p:unique){if(chosen.size()>=5)break;if(!chosen.contains(p))chosen.add(p);}return unique.stream().filter(chosen::contains).limit(5).toList();
    }
}
