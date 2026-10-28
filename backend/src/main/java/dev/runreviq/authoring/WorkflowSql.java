package dev.runreviq.authoring;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import tools.jackson.databind.JsonNode;

final class WorkflowSql {
    private WorkflowSql(){}
    static Map<String,Object> map(Object... pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    static PreparedStatement statement(Connection c,String sql,Object... args)throws SQLException {
        var q=c.prepareStatement(sql);try{for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);return q;}catch(SQLException e){q.close();throw e;}
    }
    static List<Map<String,Object>> rows(Connection c,String sql,Object... args)throws SQLException {
        try(var q=statement(c,sql,args);var r=q.executeQuery()){
            var result=new ArrayList<Map<String,Object>>();int columns=r.getMetaData().getColumnCount();
            while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=columns;i++){
                Object value=r.getObject(i);
                if(value instanceof java.sql.Array a){try{value=Arrays.asList((Object[])a.getArray());}finally{a.free();}}
                if(value instanceof UUID)value=value.toString();
                if(value instanceof Timestamp)value=time(r.getObject(i,OffsetDateTime.class));
                row.put(r.getMetaData().getColumnLabel(i),value);
            }result.add(row);}return result;
        }
    }
    static Map<String,Object> row(Connection c,String sql,Object... args)throws SQLException {var rows=rows(c,sql,args);return rows.isEmpty()?null:rows.getFirst();}
    static void execute(Connection c,String sql,Object... args)throws SQLException {try(var q=statement(c,sql,args)){q.execute();}}
    static String time(OffsetDateTime t){String s=t.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"));if(t.getNano()!=0)s+=String.format(Locale.ROOT,".%06d",t.getNano()/1000);return s+(t.getOffset().getId().equals("Z")?"+00:00":t.getOffset().getId());}
    static JsonNode json(Map<String,Object> r,String key){return DraftRepository.JSON.readTree((String)r.get(key));}
    static UUID uuid(Map<String,Object> r,String key){return UUID.fromString((String)r.get(key));}
    static int number(Map<String,Object> r,String key){return ((Number)r.get(key)).intValue();}
    static void lockDraft(Connection c,UUID id)throws SQLException{execute(c,"SELECT pg_advisory_xact_lock(?)",Long.parseLong(id.toString().replace("-","").substring(0,15),16));}
    static DraftFailure conflict(){return new DraftFailure(409,"Revision changed or submission already decided; reload");}
    static DraftFailure invalid(){return new DraftFailure(422,"Complete valid metadata or supply a nonempty decision comment");}
    static void complete(Map<String,Object> review){if(!((List<?>)review.get("issues")).isEmpty())throw invalid();}
    static String comment(String text){if(text==null||MetadataProposal.strip(text).isEmpty()||text.codePointCount(0,text.length())>2000||text.indexOf(0)>=0)throw invalid();return MetadataProposal.strip(text);}
}
