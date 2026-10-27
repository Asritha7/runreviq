package dev.runreviq.authoring;

import dev.runreviq.auth.PrivateConfiguration;
import java.nio.file.Path;
import java.sql.*;
import java.util.Set;
import org.postgresql.ds.PGSimpleDataSource;

/** Dedicated writer login. Owner scope is transaction-local and always derived from authentication. */
public final class DraftConnections {
    private final Path path;
    public DraftConnections(Path path) {this.path=path;}
    @FunctionalInterface interface Operation<T> {T run(Connection c) throws Exception;}
    <T>T transaction(String owner,Operation<T> operation) {
        if(owner==null||!owner.matches("[0-9a-f]{64}"))throw DraftFailure.unavailable();
        try {
            var n=PrivateConfiguration.readOwnerOnly(path);
            if(!n.isObject()||!Set.copyOf(n.propertyNames()).equals(Set.of("database","draft_password"))
                    ||!n.get("database").isString()||!n.get("database").asString().matches("[a-z][a-z0-9_]{0,62}")
                    ||!n.get("draft_password").isString()||n.get("draft_password").asString().isBlank())throw DraftFailure.unavailable();
            var ds=new PGSimpleDataSource();ds.setServerNames(new String[]{"127.0.0.1"});ds.setPortNumbers(new int[]{55432});
            ds.setDatabaseName(n.get("database").asString());ds.setUser("opslens_draft_writer");ds.setPassword(n.get("draft_password").asString());
            ds.setConnectTimeout(5);ds.setSocketTimeout(15);ds.setApplicationName("runreviq-java-drafts");
            try(var c=ds.getConnection()) {
                // READ COMMITTED is deliberate: after acquiring the advisory lock, see the winning writer's commit.
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
                try {
                    verify(c);
                    try(var q=c.prepareStatement("SELECT set_config('opslens.draft_owner',?,true)")){q.setString(1,owner);q.execute();}
                    T result=operation.run(c);c.commit();return result;
                }catch(Exception e){c.rollback();throw e;}
            }
        }catch(DraftFailure e){throw e;}catch(Exception e){throw DraftFailure.unavailable();}
    }
    private static void verify(Connection c)throws SQLException {
        try(var q=c.createStatement()) {
            q.execute("SET LOCAL statement_timeout='10s'");q.execute("SET LOCAL row_security=on");
            try(var r=q.executeQuery("""
                SELECT current_user,session_user,rolsuper,rolcreatedb,rolcreaterole,rolbypassrls,
                EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid)
                FROM pg_roles r WHERE rolname=current_user
                """)) {
                if(!r.next()||!r.getString(1).equals("opslens_draft_writer")||!r.getString(2).equals(r.getString(1)))throw DraftFailure.unavailable();
                for(int i=3;i<=7;i++)if(r.getBoolean(i))throw DraftFailure.unavailable();
            }
            try(var r=q.executeQuery("""
                SELECT c.relrowsecurity,c.relforcerowsecurity,c.relowner=r.oid,
                has_table_privilege(current_user,c.oid,'UPDATE,DELETE,TRUNCATE'),
                has_table_privilege(current_user,c.oid,'SELECT'),has_table_privilege(current_user,c.oid,'INSERT')
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.rolname=current_user
                WHERE n.nspname='opslens' AND c.relname IN ('upload_drafts','draft_reviews')
                """)) {
                int count=0;while(r.next()){count++;if(!r.getBoolean(1)||!r.getBoolean(2)||r.getBoolean(3)||r.getBoolean(4)||!r.getBoolean(5)||!r.getBoolean(6))throw DraftFailure.unavailable();}
                if(count!=2)throw DraftFailure.unavailable();
            }
            try(var r=q.executeQuery("""
                SELECT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='opslens' AND c.relname IN ('documents','document_versions','publications','document_withdrawals','submission_decisions')
                AND has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE'))
                """)){r.next();if(r.getBoolean(1))throw DraftFailure.unavailable();}
        }
    }
}
