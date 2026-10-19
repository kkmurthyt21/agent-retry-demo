package dev.agentretry.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small JDBC wrapper. Each instance owns one database, never a cross-database transaction. */
final class Database implements AutoCloseable {
    record Row(Map<String, Object> values) {
        String text(String k) { var v = values.get(k); return v == null ? null : v.toString(); }
        int number(String k) { return ((Number) values.get(k)).intValue(); }
        long millis(String k) { return ((Number) values.get(k)).longValue(); }
    }
    @FunctionalInterface interface SqlWork<T> { T run(Connection c) throws SQLException; }
    final Connection connection;
    final String url;
    Database(Path root, String name) {
        try {
            Files.createDirectories(root);
            String base = root.resolve(name).toAbsolutePath().normalize().toString();
            if (base.contains(";")) throw new IllegalArgumentException("Database path must not contain a semicolon");
            url = "jdbc:h2:file:" + base + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;LOCK_TIMEOUT=5000";
            Class.forName("org.h2.Driver");
            connection = newConnection();
            try (var in = Database.class.getResourceAsStream("/db/" + name + ".sql")) {
                if (in == null) throw new IllegalStateException("Missing database schema " + name);
                String ddl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                for (String sql : ddl.split(";")) if (!sql.isBlank()) execute(connection, sql);
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot open " + name + " database", e); }
    }
    Connection newConnection() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    <T> T transaction(SqlWork<T> work) {
        try {
            connection.setAutoCommit(false);
            try { T result = work.run(connection); connection.commit(); return result; }
            catch (Throwable e) { connection.rollback(); throw e; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException e) { throw new IllegalStateException("Workflow transaction failed", e); }
    }
    int execute(String sql, Object... args) { try { return execute(connection, sql, args); }
        catch (SQLException e) { throw new IllegalStateException(e); } }
    Row one(String sql, Object... args) { try { return one(connection, sql, args); }
        catch (SQLException e) { throw new IllegalStateException(e); } }
    List<Row> rows(String sql, Object... args) { try { return rows(connection, sql, args); }
        catch (SQLException e) { throw new IllegalStateException(e); } }
    static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (var p = c.prepareStatement(sql)) { for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]); return p.executeUpdate(); }
    }
    static Row one(Connection c, String sql, Object... args) throws SQLException {
        var rows = rows(c, sql, args); return rows.isEmpty() ? null : rows.get(0);
    }
    static List<Row> rows(Connection c, String sql, Object... args) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            try (var r = p.executeQuery()) {
                var result = new ArrayList<Row>(); var meta = r.getMetaData();
                while (r.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object value = meta.getColumnType(i) == Types.CLOB ? r.getString(i) : r.getObject(i);
                        row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
                    }
                    result.add(new Row(row));
                }
                return result;
            }
        }
    }
    @Override public void close() { try { connection.close(); } catch (SQLException e) { throw new IllegalStateException(e); } }
}
