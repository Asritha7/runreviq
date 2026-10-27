package dev.runreviq.persistence;

import dev.runreviq.auth.PrivateConfiguration;
import java.nio.file.Path;
import java.sql.*;
import java.util.Set;
import org.postgresql.ds.PGSimpleDataSource;

/** No admin credentials, role switching, or connection pooling across identities. */
public final class ReaderConnections {
    public static final Set<String> ROLES = Set.of("platform", "security", "intern");
    private final Path configPath;
    public ReaderConnections(Path configPath) { this.configPath = configPath; }
    @FunctionalInterface interface Read<T> { T run(Connection connection) throws Exception; }

    <T> T read(String role, Read<T> operation) {
        if (role == null || !ROLES.contains(role)) throw new EvidenceUnavailable();
        try {
            var config = PrivateConfiguration.readOwnerOnly(configPath);
            if (!config.isObject() || !Set.copyOf(config.propertyNames()).equals(Set.of("database", "reader_passwords")))
                throw new EvidenceUnavailable();
            var database = config.get("database");
            var passwords = config.get("reader_passwords");
            if (!database.isString() || !database.asString().matches("[a-z][a-z0-9_]{0,62}")
                    || !passwords.isObject() || !Set.copyOf(passwords.propertyNames()).equals(ROLES)) throw new EvidenceUnavailable();
            for (String r : ROLES) if (!passwords.get(r).isString() || passwords.get(r).asString().isBlank()) throw new EvidenceUnavailable();
            var source = new PGSimpleDataSource();
            source.setServerNames(new String[]{"127.0.0.1"}); source.setPortNumbers(new int[]{55432});
            source.setDatabaseName(database.asString()); source.setUser("opslens_reader_" + role);
            source.setPassword(passwords.get(role).asString()); source.setConnectTimeout(5); source.setSocketTimeout(15);
            source.setApplicationName("runreviq-java-reader"); source.setReadOnlyMode("transaction");
            try (var connection = source.getConnection()) {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                try {
                    verifyBoundary(connection, role);
                    T result = operation.run(connection);
                    connection.commit();
                    return result;
                } catch (Exception failure) {
                    connection.rollback();
                    throw new EvidenceUnavailable();
                }
            }
        } catch (Exception ignored) { throw new EvidenceUnavailable(); }
    }
    private static void verifyBoundary(Connection connection, String role) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL statement_timeout = '10s'");
            statement.execute("SET LOCAL row_security = on");
            try (var row = statement.executeQuery("""
                    SELECT current_user, session_user, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls,
                           EXISTS (SELECT 1 FROM pg_auth_members WHERE member = r.oid)
                    FROM pg_roles r WHERE rolname = current_user
                    """)) {
                if (!row.next() || !row.getString(1).equals("opslens_reader_" + role)
                        || !row.getString(2).equals(row.getString(1))) throw new EvidenceUnavailable();
                for (int i = 3; i <= 7; i++) if (row.getBoolean(i)) throw new EvidenceUnavailable();
            }
            try (var rows = statement.executeQuery("""
                    SELECT c.relrowsecurity, c.relforcerowsecurity, c.relowner = r.oid,
                           has_table_privilege(current_user, c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE')
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_roles r ON r.rolname = current_user
                    WHERE n.nspname = 'opslens' AND c.relname IN ('documents','document_versions','document_withdrawals')
                    """)) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    if (!rows.getBoolean(1) || !rows.getBoolean(2) || rows.getBoolean(3) || rows.getBoolean(4))
                        throw new EvidenceUnavailable();
                }
                if (count != 3) throw new EvidenceUnavailable();
            }
        }
    }
}
