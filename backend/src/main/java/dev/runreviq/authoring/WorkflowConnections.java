package dev.runreviq.authoring;

import dev.runreviq.auth.PrivateConfiguration;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.postgresql.ds.PGSimpleDataSource;

/** Fixed restricted operator identities. Never accepts a caller-supplied SQL login. */
public final class WorkflowConnections {
    enum Kind { REVIEWER, PUBLISHER }
    private final Path path;private final Kind kind;
    WorkflowConnections(Path path,Kind kind){this.path=path;this.kind=kind;}
    @FunctionalInterface interface Operation<T>{T run(Connection c)throws Exception;}
    <T>T transaction(String actor,Operation<T> op){return transaction(actor,false,op);}
    <T>T transaction(String actor,boolean snapshot,Operation<T> op) {
        if(actor==null||!actor.matches("[0-9a-f]{64}"))throw unavailable();
        String login=kind==Kind.REVIEWER?"opslens_review_operator":"opslens_publish_operator";
        try {
            var n=PrivateConfiguration.readOwnerOnly(path);
            if(!n.isObject()||!Set.copyOf(n.propertyNames()).equals(Set.of("database","password"))||!n.get("database").isString()
                    ||!n.get("database").asString().matches("[a-z][a-z0-9_]{0,62}")||!n.get("password").isString()||n.get("password").asString().isBlank())throw unavailable();
            var ds=new PGSimpleDataSource();ds.setServerNames(new String[]{"127.0.0.1"});ds.setPortNumbers(new int[]{55432});ds.setDatabaseName(n.get("database").asString());
            ds.setUser(login);ds.setPassword(n.get("password").asString());ds.setConnectTimeout(5);ds.setSocketTimeout(15);ds.setApplicationName("runreviq-java-workflows");
            try(var c=ds.getConnection()) {
                c.setReadOnly(snapshot);c.setTransactionIsolation(snapshot?Connection.TRANSACTION_REPEATABLE_READ:Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
                try {
                    verify(c,login);
                    WorkflowSql.execute(c,"SELECT set_config(?,?,true)",kind==Kind.REVIEWER?"opslens.reviewer":"opslens.publisher",actor);
                    T value=op.run(c);c.commit();return value;
                }catch(Exception e){c.rollback();throw e;}
            }
        }catch(DraftFailure e){throw e;}catch(Exception e){throw unavailable();}
    }
    static DraftFailure unavailable(){return new DraftFailure(503,"Workflow store unavailable");}
    private void verify(Connection c,String login)throws SQLException {
        WorkflowSql.execute(c,"SET LOCAL statement_timeout='10s'");WorkflowSql.execute(c,"SET LOCAL row_security=on");
        try(var q=c.createStatement();var r=q.executeQuery("SELECT current_user,session_user,rolsuper,rolcreatedb,rolcreaterole,rolbypassrls,EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid) FROM pg_roles r WHERE rolname=current_user")){
            if(!r.next()||!login.equals(r.getString(1))||!login.equals(r.getString(2)))throw unavailable();for(int i=3;i<=7;i++)if(r.getBoolean(i))throw unavailable();
        }
        var insert=kind==Kind.REVIEWER?Set.of("submission_decisions"):Set.of("documents","document_versions","publications","document_withdrawals");
        try(var q=c.createStatement();var r=q.executeQuery("""
            SELECT c.relname,c.relrowsecurity,c.relforcerowsecurity,c.relowner=r.oid,
            has_table_privilege(current_user,c.oid,'INSERT'),has_table_privilege(current_user,c.oid,'DELETE,TRUNCATE'),
            has_any_column_privilege(current_user,c.oid,'UPDATE')
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.rolname=current_user
            WHERE n.nspname='opslens' AND c.relname IN ('documents','document_versions','upload_drafts','draft_reviews','draft_submissions','submission_decisions','publications','document_withdrawals')
            """)){
            int count=0;while(r.next()){count++;String table=r.getString(1);
                if(!r.getBoolean(2)||!r.getBoolean(3)||r.getBoolean(4)||r.getBoolean(5)!=insert.contains(table)||r.getBoolean(6)
                        ||r.getBoolean(7)!=(kind==Kind.PUBLISHER&&table.equals("documents")))throw unavailable();
            }if(count!=8)throw unavailable();
        }
        if(kind==Kind.PUBLISHER){var r=WorkflowSql.row(c,"SELECT has_column_privilege(current_user,'opslens.documents','id','UPDATE') AS forbidden");if(Boolean.TRUE.equals(r.get("forbidden")))throw unavailable();}
    }
}
