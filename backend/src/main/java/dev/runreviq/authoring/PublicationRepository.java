package dev.runreviq.authoring;

import static dev.runreviq.authoring.WorkflowSql.*;
import dev.runreviq.persistence.*;
import java.math.BigInteger;
import java.sql.Connection;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class PublicationRepository {
    final WorkflowConnections publishers;
    public PublicationRepository(WorkflowConnections publishers){this.publishers=publishers;}
    public List<Map<String,Object>> queue(String actor){return publishers.transaction(actor,c->rows(c,"""
        SELECT s.id,u.filename,s.revision FROM opslens.draft_submissions s JOIN opslens.upload_drafts u ON u.id=s.draft_id
        LEFT JOIN opslens.publications p ON p.submission_id=s.id
        WHERE p.submission_id IS NULL AND NOT EXISTS(SELECT 1 FROM opslens.publications newer JOIN opslens.draft_submissions ns ON ns.id=newer.submission_id
        WHERE ns.draft_id=s.draft_id AND ns.revision>=s.revision) ORDER BY s.created_at,s.id LIMIT 100
        """));}
    private static Map<String,Object> snapshot(Connection c,UUID id)throws Exception{return row(c,"""
        SELECT s.draft_id,s.revision,u.filename,u.content,r.metadata::text,d.comment
        FROM opslens.draft_submissions s JOIN opslens.upload_drafts u ON u.id=s.draft_id
        JOIN opslens.draft_reviews r ON r.draft_id=s.draft_id AND r.revision=s.revision
        JOIN opslens.submission_decisions d ON d.submission_id=s.id AND d.decision='approved' WHERE s.id=?
        """,id);}
    public Map<String,Object> detail(String actor,UUID id){return publishers.transaction(actor,c->{var r=snapshot(c,id);return r==null?null:map("id",id.toString(),"revision",r.get("revision"),"filename",r.get("filename"),"content",r.get("content"),"metadata",json(r,"metadata"),"review_comment",r.get("comment"));});}
    public static List<Map<String,Object>> claims(JsonNode claims,String content){
        if(!claims.isArray()||claims.isEmpty()||claims.size()>20)throw invalid();
        var lines=StoredEvidence.lines(content);var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(var c:claims){
            if(!c.isObject()||!Set.copyOf(c.propertyNames()).equals(Set.of("action","rule","start_line","end_line"))
                    ||!c.get("action").isString()||!c.get("action").asString().matches("[a-z][a-z_]{0,99}")
                    ||!c.get("rule").isString()||!Set.of("must","must_not").contains(c.get("rule").asString()))throw invalid();
            for(String k:List.of("start_line","end_line"))if(!c.get(k).isIntegralNumber()||!c.get(k).canConvertToInt())throw invalid();
            int start=c.get("start_line").asInt(),end=c.get("end_line").asInt();
            if(start<1||end<start||end>lines.size()||!seen.add(c.get("action").asString()))throw invalid();
            String quote=String.join("\n",lines.subList(start-1,end));if(MetadataProposal.strip(quote).isEmpty())throw invalid();
            result.add(map("action",c.get("action").asString(),"rule",c.get("rule").asString(),"citation",map("start_line",start,"end_line",end,"quote",quote,"source_sha256",PythonJson.sha256(content))));
        }return result;
    }
    public Map<String,Object> publish(String actor,UUID id,JsonNode roles,JsonNode rawClaims,boolean verified) {
        if(!verified||!roles.isArray()||roles.isEmpty()||roles.size()>3||!rawClaims.isArray()||rawClaims.size()>20)throw invalid();
        var chosen=new TreeSet<String>();for(var r:roles)if(!r.isString()||!ReaderConnections.ROLES.contains(r.asString())||!chosen.add(r.asString()))throw invalid();
        return publishers.transaction(actor,c->{
            execute(c,"SELECT pg_advisory_xact_lock(710422)");var r=snapshot(c,id);if(r==null)return null;
            String content=(String)r.get("content");var review=MetadataProposal.review(json(r,"metadata"),content);complete(review);
            var metadata=(tools.jackson.databind.node.ObjectNode)DraftRepository.JSON.valueToTree(review.get("validated_metadata"));
            var profile=metadata.get("evidence_profile");
            var cited=rawClaims.isEmpty()&&profile!=null&&!profile.get("units").isEmpty()?List.<Map<String,Object>>of():claims(rawClaims,content);
            int revision=number(r,"revision");String doc="UPLOAD-"+new BigInteger(uuid(r,"draft_id").toString().replace("-",""),16);
            metadata.put("id",doc);metadata.put("revision",revision);metadata.put("status","approved");metadata.put("metadata_verified",true);metadata.set("claims",DraftRepository.JSON.valueToTree(cited));metadata.put("path","submission:"+id);
            String checksum=PythonJson.sha256(PythonJson.encode(DraftRepository.JSON.valueToTree(map("metadata",metadata,"content",content))));
            // Reuse the published-evidence boundary: every quote/hash/range and prohibition must validate before any write.
            try{StoredEvidence.validate(metadata,content,checksum,doc,revision,chosen);}catch(Exception e){throw invalid();}
            var previous=row(c,"SELECT p.document_id,p.revision,p.allowed_roles,v.checksum,p.created_at AS published_at FROM opslens.publications p JOIN opslens.document_versions v ON v.document_id=p.document_id AND v.revision=p.revision WHERE p.submission_id=?",id);
            if(previous!=null){if(!new ArrayList<>(chosen).equals(previous.get("allowed_roles"))||!checksum.equals(previous.get("checksum")))throw conflict();return map("document_id",previous.get("document_id"),"revision",previous.get("revision"),"published",true,"published_at",previous.get("published_at"));}
            var current=row(c,"SELECT current_revision FROM opslens.documents WHERE id=?",doc);if(current!=null&&number(current,"current_revision")>=revision)throw conflict();
            var array=c.createArrayOf("text",chosen.toArray(String[]::new));
            try {
                execute(c,"INSERT INTO opslens.documents(id,current_revision,allowed_roles) VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET current_revision=EXCLUDED.current_revision,allowed_roles=EXCLUDED.allowed_roles",doc,revision,array);
                execute(c,"INSERT INTO opslens.document_versions(document_id,revision,checksum,metadata,content) VALUES (?,?,?,?::jsonb,?)",doc,revision,checksum,metadata.toString(),content);
                var at=row(c,"INSERT INTO opslens.publications(submission_id,document_id,revision,publisher,allowed_roles) VALUES (?,?,?,?,?) RETURNING created_at",id,doc,revision,actor,array);
                return map("document_id",doc,"revision",revision,"published",true,"published_at",at.get("created_at"));
            }finally{array.free();}
        });
    }
}
