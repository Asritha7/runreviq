package dev.runreviq.authoring;

import static dev.runreviq.authoring.WorkflowSql.*;
import dev.runreviq.persistence.StoredEvidence;
import java.util.*;

public final class LifecycleRepository {
    private final WorkflowConnections publishers;
    public LifecycleRepository(WorkflowConnections publishers){this.publishers=publishers;}
    static void validId(String id){if(id==null||id.length()>100||!id.matches("[A-Z]+-[0-9]+"))throw invalid();}
    private static String status(String recorded,int revision,int current,Object reason){return reason!=null?"withdrawn":revision!=current?"superseded":recorded.equals("approved")?"active":recorded;}
    private static Object withdrawal(Map<String,Object> r,boolean actor){if(r.get("reason")==null)return null;var w=map("reason",r.get("reason"),"withdrawn_at",r.get("withdrawn_at"));if(actor)w.put("actor",r.get("actor"));return w;}
    public Map<String,Object> documents(String actor,String after){
        if(!after.isEmpty())validId(after);
        return publishers.transaction(actor,c->{
            var all=rows(c,"""
                SELECT d.id,d.current_revision,d.allowed_roles,v.metadata::text,v.created_at AS published_at,w.reason,w.created_at AS withdrawn_at
                FROM opslens.documents d JOIN opslens.document_versions v ON v.document_id=d.id AND v.revision=d.current_revision
                LEFT JOIN opslens.document_withdrawals w ON w.document_id=d.id AND w.revision=d.current_revision
                WHERE d.id>? ORDER BY d.id LIMIT 51
                """,after);var result=new ArrayList<Map<String,Object>>();
            for(var r:all.subList(0,Math.min(50,all.size()))){var m=json(r,"metadata");int rev=number(r,"current_revision");
                result.add(map("document_id",r.get("id"),"current_revision",rev,"allowed_roles",r.get("allowed_roles"),"title",m.get("title").asString(),"status",status(m.get("status").asString(),rev,rev,r.get("reason")),"published_at",r.get("published_at"),"withdrawal",withdrawal(r,false)));}
            return map("documents",result,"next_after",all.size()>50?result.getLast().get("document_id"):null);
        });
    }
    public Map<String,Object> history(String actor,String id){
        validId(id);return publishers.transaction(actor,true,c->{
            var doc=row(c,"SELECT current_revision,allowed_roles FROM opslens.documents WHERE id=?",id);if(doc==null)return null;int current=number(doc,"current_revision");
            var result=new ArrayList<Map<String,Object>>();
            for(var r:rows(c,"""
                SELECT v.revision,v.metadata::text,v.created_at AS published_at,w.reason,w.created_at AS withdrawn_at,w.actor
                FROM opslens.document_versions v LEFT JOIN opslens.document_withdrawals w ON w.document_id=v.document_id AND w.revision=v.revision
                WHERE v.document_id=? ORDER BY v.revision DESC
                """,id)){var m=json(r,"metadata");int rev=number(r,"revision");
                result.add(map("revision",rev,"title",m.get("title").asString(),"recorded_status",m.get("status").asString(),
                        "status",status(m.get("status").asString(),rev,current,r.get("reason")),"is_current",rev==current,"superseded_by",rev!=current?current:null,"published_at",r.get("published_at"),"withdrawal",withdrawal(r,true)));}
            return map("document_id",id,"current_revision",current,"allowed_roles",doc.get("allowed_roles"),"revisions",result);
        });
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> revision(String actor,String id,int number){
        validId(id);if(number<1)throw invalid();return publishers.transaction(actor,c->{
            var r=row(c,"""
                SELECT v.metadata::text,v.content,v.checksum,d.current_revision,d.allowed_roles,w.reason,w.created_at AS withdrawn_at
                FROM opslens.document_versions v JOIN opslens.documents d ON d.id=v.document_id
                LEFT JOIN opslens.document_withdrawals w ON w.document_id=v.document_id AND w.revision=v.revision WHERE v.document_id=? AND v.revision=?
                """,id,number);if(r==null)return null;var m=json(r,"metadata");
            StoredEvidence.validate(m,(String)r.get("content"),(String)r.get("checksum"),id,number,new HashSet<>((List<String>)r.get("allowed_roles")));
            return map("document_id",id,"revision",number,"metadata",m,"content",r.get("content"),"checksum",r.get("checksum"),"current_revision",r.get("current_revision"),"status",status(m.get("status").asString(),number,number(r,"current_revision"),r.get("reason")),"withdrawal",withdrawal(r,false));
        });
    }
    public Map<String,Object> withdraw(String actor,String id,int expected,String rawReason){
        validId(id);if(expected<1)throw invalid();String reason=comment(rawReason);
        return publishers.transaction(actor,c->{
            execute(c,"SELECT pg_advisory_xact_lock(710422)");
            var r=row(c,"SELECT d.current_revision,v.metadata::text FROM opslens.documents d JOIN opslens.document_versions v ON v.document_id=d.id AND v.revision=d.current_revision WHERE d.id=?",id);
            if(r==null)return null;if(number(r,"current_revision")!=expected)throw conflict();if(!json(r,"metadata").get("status").asString().equals("approved"))throw invalid();
            var previous=row(c,"SELECT actor,reason,created_at FROM opslens.document_withdrawals WHERE document_id=? AND revision=?",id,expected);
            if(previous!=null){if(!actor.equals(previous.get("actor"))||!reason.equals(previous.get("reason")))throw conflict();}
            else previous=row(c,"INSERT INTO opslens.document_withdrawals(document_id,revision,actor,reason) VALUES (?,?,?,?) RETURNING created_at",id,expected,actor,reason);
            return map("document_id",id,"revision",expected,"status","withdrawn","reason",reason,"withdrawn_at",previous.get("created_at"));
        });
    }
}
