package dev.runreviq.persistence;

import dev.runreviq.domain.EvidenceDocument;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class EvidenceRepository {
    private final ReaderConnections readers;
    private final boolean focused;
    public EvidenceRepository(ReaderConnections readers) { this(readers,"reviewed-v5"); }
    public EvidenceRepository(ReaderConnections readers,String mode) {
        if(!Set.of("reviewed-v5","focused-v6").contains(mode))throw new IllegalArgumentException("Unknown search mode");
        this.readers=readers;this.focused=mode.equals("focused-v6");
    }
    public List<EvidenceDocument> current(String role) {
        return readers.read(role,connection -> snapshot(connection).stream().map(Source::document).toList());
    }
    record Source(EvidenceDocument document,String content) {}
    static List<Source> snapshot(Connection connection) throws Exception {
        var documents=new ArrayList<Source>();
        try(var query=connection.prepareStatement("""
            SELECT v.document_id, v.revision, v.metadata::text, v.content, v.checksum, d.allowed_roles
            FROM opslens.documents d JOIN opslens.document_versions v
              ON v.document_id=d.id AND v.revision=d.current_revision
            WHERE NOT EXISTS (SELECT 1 FROM opslens.document_withdrawals w
              WHERE w.document_id=d.id AND w.revision=d.current_revision)
            ORDER BY d.id
            """); var rows=query.executeQuery()) {
            while(rows.next()) documents.add(new Source(StoredEvidence.validate(StoredEvidence.JSON.readTree(rows.getString(3)),
                    rows.getString(4),rows.getString(5),rows.getString(1),rows.getInt(2),roles(rows.getArray(6))),rows.getString(4)));
        }
        return List.copyOf(documents);
    }
    public Map<String,Object> search(String question,dev.runreviq.domain.SelectionContext context) {
        // Validate/infer context before opening SQL; reader failures remain fixed 503s.
        var request=SearchRepository.context(question,context);
        return readers.read(context.role(),connection -> SearchRepository.search(connection,snapshot(connection),question,request,context.httpStatus(),focused));
    }
    public Map<String,Object> revision(String role,String id,int revision) {
        if(id==null || !id.matches("[A-Z]+-[0-9]+") || revision<1) throw new IllegalArgumentException("Invalid revision");
        return readers.read(role,connection -> {
            try(var query=connection.prepareStatement("""
                SELECT v.metadata::text, v.content, v.checksum, d.current_revision, w.reason, w.created_at, d.allowed_roles
                FROM opslens.document_versions v JOIN opslens.documents d ON d.id=v.document_id
                LEFT JOIN opslens.document_withdrawals w ON w.document_id=v.document_id AND w.revision=v.revision
                WHERE v.document_id=? AND v.revision=?
                """)) {
                query.setString(1,id);query.setInt(2,revision);
                try(var row=query.executeQuery()) {
                    if(!row.next()) return null;
                    var metadata=StoredEvidence.JSON.readTree(row.getString(1));String content=row.getString(2),checksum=row.getString(3);
                    var document=StoredEvidence.validate(metadata,content,checksum,id,revision,roles(row.getArray(7)));
                    int current=row.getInt(4);String reason=row.getString(5);
                    var result=new LinkedHashMap<String,Object>();
                    result.put("document_id",id);result.put("revision",revision);result.put("title",document.title());
                    result.put("content",content);result.put("checksum",checksum);result.put("evidence_profile",metadata.get("evidence_profile"));
                    result.put("current_revision",current);result.put("is_current",current==revision);result.put("revision_status",document.status());
                    result.put("guidance_status",reason!=null ? "withdrawn" : current!=revision ? "superseded" : document.status().equals("approved") ? "active" : document.status());
                    result.put("withdrawal",reason==null ? null : Map.of("reason",reason,"withdrawn_at",pythonTimestamp(row.getObject(6,OffsetDateTime.class))));
                    return result;
                }
            }
        });
    }
    private static Set<String> roles(java.sql.Array array) throws SQLException {
        try {return new HashSet<>(Arrays.asList((String[])array.getArray()));} finally {array.free();}
    }
    static String pythonTimestamp(OffsetDateTime time) {
        String base=time.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"));
        if(time.getNano()!=0) base+=String.format(Locale.ROOT,".%06d",time.getNano()/1000);
        return base+(time.getOffset().getId().equals("Z") ? "+00:00" : time.getOffset().getId());
    }
}
