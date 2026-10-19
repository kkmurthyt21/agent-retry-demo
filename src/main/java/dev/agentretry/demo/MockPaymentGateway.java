package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

/** Durable mock provider. Unique keys and the business mutation commit in this provider's transaction. */
final class MockPaymentGateway implements PaymentGateway, AutoCloseable {
    final Database db;
    private final Clock clock;
    private final Faults faults;
    final AtomicInteger submissionCalls = new AtomicInteger();
    final AtomicInteger lookupCalls = new AtomicInteger();
    MockPaymentGateway(Path root, Clock clock, Faults faults) {
        db = new Database(root, "payment-gateway"); this.clock = clock; this.faults = faults;
    }
    @Override public Submission submit(String tenant, String key, JsonNode instruction) {
        submissionCalls.incrementAndGet();
        String fingerprint = Json.actionFingerprint(instruction);
        Receipt prior = find(tenant, key);
        if (prior != null) return replay(prior, fingerprint);
        try (var c = db.newConnection()) {
            c.setAutoCommit(false);
            try {
                String id = "pi-" + Json.fingerprint(Json.MAPPER.createObjectNode().put("tenant", tenant).put("key", key)).substring(0, 24);
                long accepted = clock.millis();
                var receipt = new Receipt(tenant, key, fingerprint, id, accepted);
                Database.execute(c, "INSERT INTO instructions VALUES(?,?,?,?,?,?)", id, tenant, key, Json.write(instruction), "ACCEPTED", accepted);
                faults.at(Faults.Point.GATEWAY_BETWEEN_WRITES, key);
                Database.execute(c, "INSERT INTO gateway_operations VALUES(?,?,?,?,?)", tenant, key, fingerprint, id, Json.write(receipt));
                c.commit();
                return new Submission(receipt, true);
            } catch (SQLException e) {
                c.rollback();
                // A concurrent same-key transaction may have committed while this one waited on its unique constraint.
                if ("23505".equals(e.getSQLState())) {
                    Receipt concurrent = find(tenant, key);
                    if (concurrent != null) return replay(concurrent, fingerprint);
                }
                throw e;
            } catch (RuntimeException | Error e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new Problems.Transient("GATEWAY_DATABASE_UNAVAILABLE: " + e.getSQLState()); }
    }
    private static Submission replay(Receipt receipt, String fingerprint) {
        if (!receipt.fingerprint().equals(fingerprint)) throw new Problems.Permanent("ACTION_KEY_PARAMETER_CONFLICT");
        return new Submission(receipt, false);
    }
    @Override public Receipt find(String tenant, String key) {
        lookupCalls.incrementAndGet();
        try (var c = db.newConnection()) {
            var row = Database.one(c, "SELECT receipt_json FROM gateway_operations WHERE tenant=? AND action_key=?", tenant, key);
            if (row == null) return null;
            var n = Json.read(row.text("receipt_json"));
            return new Receipt(n.path("tenant").asText(), n.path("actionKey").asText(), n.path("fingerprint").asText(),
                    n.path("instructionId").asText(), n.path("acceptedAt").asLong());
        } catch (SQLException e) { throw new Problems.Transient("GATEWAY_LOOKUP_UNAVAILABLE"); }
    }
    @Override public JsonNode readInstruction(String id) {
        try (var c = db.newConnection()) {
            var row = Database.one(c, "SELECT * FROM instructions WHERE instruction_id=?", id);
            if (row == null) return null;
            var result = Json.MAPPER.createObjectNode();
            result.put("tenant", row.text("tenant")); result.put("actionKey", row.text("action_key"));
            result.put("status", row.text("status")); result.set("request", Json.read(row.text("request_json")));
            return result;
        } catch (SQLException e) { throw new Problems.Transient("GATEWAY_READ_UNAVAILABLE"); }
    }
    int count() { return db.one("SELECT COUNT(*) AS n FROM instructions").number("n"); }
    int count(String key) { return db.one("SELECT COUNT(*) AS n FROM instructions WHERE action_key=?", key).number("n"); }
    @Override public void close() { db.close(); }
}
