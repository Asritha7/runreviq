package dev.runreviq.authoring;

import dev.runreviq.persistence.PythonJson;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class DraftRepository {
    public static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final DraftConnections connections;
    public DraftRepository(DraftConnections connections){this.connections=connections;}
    public static String owner(String mode,String subject){return PythonJson.sha256(mode+":"+subject);}
    private static String timestamp(ResultSet r,int index)throws SQLException {
        var t=r.getObject(index,OffsetDateTime.class);String result=t.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"));
        if(t.getNano()!=0)result+=String.format(Locale.ROOT,".%06d",t.getNano()/1000);
        return result+(t.getOffset().getId().equals("Z")?"+00:00":t.getOffset().getId());
    }
    private static void lock(Connection c,long key)throws SQLException {
        try(var q=c.prepareStatement("SELECT pg_advisory_xact_lock(?)")){q.setLong(1,key);q.execute();}
    }
    private static Map<String,Object> summary(ResultSet r)throws SQLException {
        return new LinkedHashMap<>(Map.of("id",r.getString(1),"filename",r.getString(2),"created_at",timestamp(r,3),"status","draft"));
    }
    public Map<String,Object> create(String owner,String filename,String content) {
        if(filename==null||!filename.matches("(?i)[A-Za-z0-9][A-Za-z0-9 ._-]{0,116}\\.md"))throw new DraftFailure(422,"Expected nonempty UTF-8 Markdown with a simple .md filename");
        var proposal=MetadataProposal.extract(content);var id=UUID.randomUUID();
        return connections.transaction(owner,c->{
            lock(c,Long.parseLong(owner.substring(0,15),16));
            try(var q=c.createStatement();var r=q.executeQuery("SELECT count(*) FROM opslens.upload_drafts")){r.next();if(r.getInt(1)>=20)throw new DraftFailure(429,"Draft limit reached (20 per account); contact the local operator");}
            try(var q=c.prepareStatement("INSERT INTO opslens.upload_drafts(id,owner,filename,content,proposal) VALUES (?,?,?,?,?::jsonb) RETURNING id,filename,created_at")) {
                q.setObject(1,id);q.setString(2,owner);q.setString(3,filename);q.setString(4,content);q.setString(5,JSON.writeValueAsString(proposal));
                try(var r=q.executeQuery()){r.next();var result=summary(r);result.put("content",content);result.put("proposal",proposal);result.put("review",null);return result;}
            }
        });
    }
    public List<Map<String,Object>> list(String owner) {
        return connections.transaction(owner,c->{var results=new ArrayList<Map<String,Object>>();
            try(var q=c.createStatement();var r=q.executeQuery("SELECT id,filename,created_at FROM opslens.upload_drafts ORDER BY created_at DESC,id LIMIT 20")){while(r.next())results.add(summary(r));}return results;
        });
    }
    public Map<String,Object> get(String owner,UUID id) {
        return connections.transaction(owner,c->{
            try(var q=c.prepareStatement("SELECT id,filename,created_at,content,proposal::text FROM opslens.upload_drafts WHERE id=?")) {
                q.setObject(1,id);try(var r=q.executeQuery()){if(!r.next())return null;var result=summary(r);String content=r.getString(4);
                    result.put("content",content);result.put("proposal",JSON.readTree(r.getString(5)));result.put("review",latest(c,id,content));return result;}
            }
        });
    }
    private static Map<String,Object> latest(Connection c,UUID id,String content)throws SQLException {
        try(var q=c.prepareStatement("SELECT revision,metadata::text,created_at FROM opslens.draft_reviews WHERE draft_id=? ORDER BY revision DESC LIMIT 1")){
            q.setObject(1,id);try(var r=q.executeQuery()){
                if(!r.next())return null;
                // Invalid stored metadata is a store failure, not a caller validation error.
                try{var result=MetadataProposal.review(JSON.readTree(r.getString(2)),content);result.put("revision",r.getInt(1));result.put("created_at",timestamp(r,3));return result;}
                catch(Exception e){throw DraftFailure.unavailable();}
            }
        }
    }
    public Map<String,Object> save(String owner,UUID id,int expected,JsonNode metadata) {
        if(expected<0||expected>=100)throw DraftFailure.invalid();MetadataProposal.review(metadata,null);
        // Bound encoded metadata before PostgreSQL's jsonb size check (which includes spaces).
        if(PythonJson.encode(metadata).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>10_000)throw DraftFailure.invalid();
        return connections.transaction(owner,c->{
            lock(c,Long.parseLong(id.toString().replace("-","").substring(0,15),16));String content;
            try(var q=c.prepareStatement("SELECT content FROM opslens.upload_drafts WHERE id=?")) {q.setObject(1,id);try(var r=q.executeQuery()){if(!r.next())return null;content=r.getString(1);}}
            var result=MetadataProposal.review(metadata,content);var previous=latest(c,id,content);int current=previous==null?0:(Integer)previous.get("revision");
            if(current!=expected)throw new DraftFailure(409,"Draft review changed; reload before saving");
            try(var q=c.prepareStatement("INSERT INTO opslens.draft_reviews(draft_id,revision,metadata) VALUES (?,?,?::jsonb) RETURNING created_at")){
                q.setObject(1,id);q.setInt(2,current+1);q.setString(3,metadata.toString());try(var r=q.executeQuery()){r.next();result.put("revision",current+1);result.put("created_at",timestamp(r,1));return result;}
            }
        });
    }
}
