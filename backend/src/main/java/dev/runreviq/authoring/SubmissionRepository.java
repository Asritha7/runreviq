package dev.runreviq.authoring;

import static dev.runreviq.authoring.WorkflowSql.*;
import java.util.*;

public final class SubmissionRepository {
    private final DraftConnections drafts;private final WorkflowConnections reviewers;
    public SubmissionRepository(DraftConnections drafts,WorkflowConnections reviewers){this.drafts=drafts;this.reviewers=reviewers;}
    public Map<String,Object> submit(String owner,UUID draft,int revision) {
        if(revision<1||revision>100)throw invalid();
        return drafts.transaction(owner,c->{
            lockDraft(c,draft);var source=row(c,"SELECT content FROM opslens.upload_drafts WHERE id=?",draft);if(source==null)return null;
            var review=DraftRepository.latest(c,draft,(String)source.get("content"));
            if(review==null||!review.get("revision").equals(revision))throw conflict();complete(review);
            var previous=row(c,"SELECT s.id,EXISTS(SELECT 1 FROM opslens.publications p WHERE p.submission_id=s.id) AS published FROM opslens.draft_submissions s WHERE draft_id=? AND revision=?",draft,revision);
            UUID id=previous==null?UUID.randomUUID():uuid(previous,"id");
            if(previous==null)execute(c,"INSERT INTO opslens.draft_submissions(id,draft_id,revision) VALUES (?,?,?)",id,draft,revision);
            return map("id",id.toString(),"revision",revision,"published",previous!=null&&Boolean.TRUE.equals(previous.get("published")));
        });
    }
    public List<Map<String,Object>> own(String owner,UUID draft) {
        return drafts.transaction(owner,c->{
            if(row(c,"SELECT id FROM opslens.upload_drafts WHERE id=?",draft)==null)return null;
            var result=new ArrayList<Map<String,Object>>();
            for(var r:rows(c,"""
                SELECT s.id,s.revision,s.created_at AS submitted_at,d.decision,d.comment,d.created_at AS decided_at,
                p.document_id,p.revision AS published_revision,p.created_at AS published_at
                FROM opslens.draft_submissions s LEFT JOIN opslens.submission_decisions d ON d.submission_id=s.id
                LEFT JOIN opslens.publications p ON p.submission_id=s.id WHERE s.draft_id=? ORDER BY s.revision DESC
                """,draft))result.add(map("id",r.get("id"),"revision",r.get("revision"),"submitted_at",r.get("submitted_at"),
                    "status",r.get("decision")==null?"pending":r.get("decision"),"comment",r.get("comment"),"decided_at",r.get("decided_at"),
                    "publication",r.get("document_id")==null?null:map("document_id",r.get("document_id"),"revision",r.get("published_revision"),"published_at",r.get("published_at"))));
            return result;
        });
    }
    public List<Map<String,Object>> queue(String actor) {
        return reviewers.transaction(actor,c->rows(c,"""
            SELECT s.id,u.filename,s.revision,s.created_at AS submitted_at
            FROM opslens.draft_submissions s JOIN opslens.upload_drafts u ON u.id=s.draft_id
            LEFT JOIN opslens.submission_decisions d ON d.submission_id=s.id
            WHERE d.submission_id IS NULL AND u.owner<>? ORDER BY s.created_at,s.id LIMIT 100
            """,actor));
    }
    public Map<String,Object> detail(String actor,UUID id) {
        return reviewers.transaction(actor,c->{
            var r=row(c,"""
                SELECT s.id,s.revision,u.filename,u.content,u.proposal::text,r.metadata::text,d.decision,d.comment,
                EXISTS(SELECT 1 FROM opslens.publications p WHERE p.submission_id=s.id) AS published
                FROM opslens.draft_submissions s JOIN opslens.upload_drafts u ON u.id=s.draft_id
                JOIN opslens.draft_reviews r ON r.draft_id=s.draft_id AND r.revision=s.revision
                LEFT JOIN opslens.submission_decisions d ON d.submission_id=s.id WHERE s.id=? AND u.owner<>?
                """,id,actor);if(r==null)return null;
            return map("id",r.get("id"),"revision",r.get("revision"),"filename",r.get("filename"),"content",r.get("content"),
                    "proposal",json(r,"proposal"),"review",MetadataProposal.review(json(r,"metadata"),(String)r.get("content")),
                    "status",r.get("decision")==null?"pending":r.get("decision"),"comment",r.get("comment"),"published",r.get("published"));
        });
    }
    public Map<String,Object> decide(String actor,UUID id,String decision,String rawComment) {
        if(!Set.of("approved","changes_requested").contains(decision))throw invalid();String comment=comment(rawComment);
        return reviewers.transaction(actor,c->{
            execute(c,"SELECT pg_advisory_xact_lock(?)",-Long.parseLong(id.toString().replace("-","").substring(0,15),16)-1);
            var r=row(c,"""
                SELECT r.metadata::text,u.content FROM opslens.draft_submissions s JOIN opslens.upload_drafts u ON u.id=s.draft_id
                JOIN opslens.draft_reviews r ON r.draft_id=s.draft_id AND r.revision=s.revision WHERE s.id=? AND u.owner<>?
                """,id,actor);if(r==null)return null;
            if(row(c,"SELECT submission_id FROM opslens.submission_decisions WHERE submission_id=?",id)!=null)throw conflict();
            if(decision.equals("approved"))complete(MetadataProposal.review(json(r,"metadata"),(String)r.get("content")));
            var at=row(c,"INSERT INTO opslens.submission_decisions(submission_id,reviewer,decision,comment) VALUES (?,?,?,?) RETURNING created_at",id,actor,decision,comment);
            return map("id",id.toString(),"status",decision,"comment",comment,"decided_at",at.get("created_at"),"published",false);
        });
    }
}
