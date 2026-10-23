package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Deterministic domain worker: policy, immutable intent, execution, verification and outbox. */
final class AgenticWorkflow implements AutoCloseable {
    final Database db;
    final Path root;
    final int broker;
    final PaymentGateway gateway;
    final boolean hold, crash;
    private String lastSnapshot;
    AgenticWorkflow(Path root, int broker, PaymentGateway gateway, boolean hold, boolean crash) {
        this.root = root; this.broker = broker; this.gateway = gateway; this.hold = hold; this.crash = crash;
        db = new Database(root, "agentic-workflow");
    }
    void emit(Connection c, String topic, JsonNode e) throws SQLException {
        Database.execute(c, "INSERT INTO outbox(event_id,topic,body) VALUES(?,?,?)", e.path("eventId").asText(), topic, Json.write(e));
    }
    void result(Connection c, String type, String call, JsonNode result) throws SQLException {
        var e = AgenticEvents.event(type, call); e.set("result", result); emit(c, "agent", e);
    }
    JsonNode status(Database.Row row) {
        var n = Json.MAPPER.createObjectNode().put("state", row == null ? "NOT_STARTED" : row.text("state"))
            .put("operationId", AgenticEvents.OPERATION).put("settled", false);
        if (row != null) {
            n.put("stateVersion", row.number("state_version"));
            if (row.text("receipt") != null) n.set("receipt", Json.read(row.text("receipt")));
        }
        return n;
    }
    Database.Row operation() { return db.one("SELECT * FROM operations WHERE operation_id=?", AgenticEvents.OPERATION); }
    boolean seen(JsonNode e) {
        var r = db.one("SELECT fingerprint FROM inbox WHERE event_id=?", e.path("eventId").asText());
        if (r == null) return false;
        if (!Json.fingerprint(e).equals(r.text("fingerprint"))) throw new Problems.Contract("EVENT_ID_CONTENT_CONFLICT");
        return true;
    }
    void inbox(Connection c, JsonNode e) throws SQLException { Database.execute(c, "INSERT INTO inbox VALUES(?,?)", e.path("eventId").asText(), Json.fingerprint(e)); }
    void handle(JsonNode e) {
        AgenticEvents.validate(e); if (seen(e)) return;
        String type = e.path("type").asText(), call = e.path("toolCallId").asText();
        if ("ContextRequested".equals(type) || "StatusRequested".equals(type)) {
            db.transaction(c -> { result(c, "ToolResultReady", call, "ContextRequested".equals(type) ? AgenticEvents.facts(hold) : status(operation())); inbox(c, e); return null; }); return;
        }
        if ("ActionProposed".equals(type)) {
            if (hold) throw new Problems.Policy("INVOICE_ON_HOLD");
            db.transaction(c -> {
                var r = operation();
                if (r == null) {
                    Database.execute(c, "INSERT INTO operations(operation_id,state,state_version,intent) VALUES(?,'PLANNED',1,?)", AgenticEvents.OPERATION, Json.write(Json.normalInstruction()));
                    var planned = AgenticEvents.event("TaskPlanned", call).put("expectedStateVersion", 1);
                    emit(c, "workflow", planned);
                } else if ("VERIFIED".equals(r.text("state"))) {
                    result(c, "WorkflowVerified", call, status(r));
                    Database.execute(c, "MERGE INTO counters KEY(name) VALUES('verifiedReuse',COALESCE((SELECT n FROM counters WHERE name='verifiedReuse'),0)+1)");
                } else if ("FAILED".equals(r.text("state"))) result(c, "ToolResultReady", call, Json.MAPPER.createObjectNode().put("error", "WORKFLOW_FAILED"));
                if (r == null || !("VERIFIED".equals(r.text("state")) || "FAILED".equals(r.text("state"))))
                    Database.execute(c, "MERGE INTO waiters KEY(call_id) VALUES(?,?)", call, AgenticEvents.OPERATION);
                inbox(c, e); return null;
            }); return;
        }
        if ("TaskPlanned".equals(type)) {
            var r = operation();
            if (e.path("expectedStateVersion").asInt() != 1 || r == null
                    || !("PLANNED".equals(r.text("state")) || ("EXECUTING".equals(r.text("state")) && e.path("eventId").asText().equals(r.text("execution_event")))))
                throw new Problems.Contract("STALE_EXECUTION_PRECONDITION");
            if (hold) throw new Problems.Policy("APPROVAL_REVOKED_BEFORE_EFFECT");
            if (r.number("attempts") >= 3) throw new Problems.Permanent("EXECUTION_BUDGET_EXHAUSTED");
            db.execute("UPDATE operations SET state='EXECUTING',state_version=2,attempts=attempts+1,execution_event=? WHERE operation_id=?", e.path("eventId").asText(), AgenticEvents.OPERATION);
            var approved = Json.read(r.text("intent"));
            var receipt = new Executor(gateway).execute(AgenticEvents.TENANT, AgenticEvents.OPERATION, approved, System.out::println);
            if (crash && !Files.exists(root.resolve("worker-crash-marker.json"))) {
                AgenticHttp.save(root.resolve("worker-crash-marker.json"), Map.of("pid", ProcessHandle.current().pid(), "point", "PROVIDER_COMMITTED_BEFORE_WORKFLOW_RECEIPT"));
                System.out.println("[WORKER_CRASH] provider committed; workflow receipt absent; exit=23"); System.out.flush(); Runtime.getRuntime().halt(23);
            }
            db.transaction(c -> {
                Database.execute(c, "UPDATE operations SET state='EXECUTED',state_version=3,receipt=? WHERE operation_id=?", Json.write(receipt), AgenticEvents.OPERATION);
                inbox(c, e); emit(c, "workflow", AgenticEvents.event("ActionCompleted", call).put("expectedStateVersion", 3)); return null;
            }); return;
        }
        if ("ActionCompleted".equals(type)) {
            var r = operation();
            if (r == null || e.path("expectedStateVersion").asInt() != 3 || !"EXECUTED".equals(r.text("state"))) throw new Problems.Contract("STALE_VERIFICATION_PRECONDITION");
            new Verifier(gateway).verify(AgenticEvents.TENANT, AgenticEvents.OPERATION, Json.read(r.text("intent")), Json.read(r.text("receipt")));
            db.transaction(c -> {
                Database.execute(c, "UPDATE operations SET state='VERIFIED',state_version=4 WHERE operation_id=?", AgenticEvents.OPERATION);
                for (var waiter : db.rows("SELECT * FROM waiters")) result(c, "WorkflowVerified", waiter.text("call_id"), status(operation()));
                Database.execute(c, "DELETE FROM waiters"); inbox(c, e); return null;
            }); return;
        }
        throw new Problems.Contract("EVENT_WRONG_DESTINATION");
    }
    void reject(JsonNode e, String channel, String reason, boolean terminal) {
        db.transaction(c -> {
            Database.execute(c, "INSERT INTO failures VALUES(?,?,?)", e.path("eventId").asText(), channel, reason);
            var response = Json.MAPPER.createObjectNode().put("error", reason).put("channel", channel);
            if (terminal && operation() != null) {
                Database.execute(c, "UPDATE operations SET state='FAILED',state_version=state_version+1 WHERE operation_id=?", AgenticEvents.OPERATION);
                for (var waiter : db.rows("SELECT * FROM waiters")) result(c, "ToolResultReady", waiter.text("call_id"), response);
                Database.execute(c, "DELETE FROM waiters");
            }
            if (AgenticEvents.REQUESTS.contains(e.path("type").asText()) && !e.path("toolCallId").asText().isBlank()) result(c, "ToolResultReady", e.path("toolCallId").asText(), response);
            if (db.one("SELECT * FROM inbox WHERE event_id=?", e.path("eventId").asText()) == null) inbox(c, e);
            return null;
        });
        System.out.println("[" + channel + "] " + reason);
    }
    void flush() {
        for (var r : db.rows("SELECT * FROM outbox WHERE status='READY' ORDER BY event_id")) {
            if (r.number("attempts") >= 3) {
                db.transaction(c -> {
                    Database.execute(c, "UPDATE outbox SET status='FAILED' WHERE event_id=?", r.text("event_id"));
                    Database.execute(c, "INSERT INTO failures VALUES(?,'DELIVERY','OUTBOX_BUDGET_EXHAUSTED')", r.text("event_id")); return null;
                }); continue;
            }
            db.execute("UPDATE outbox SET attempts=attempts+1 WHERE event_id=?", r.text("event_id"));
            try { AgenticBroker.call(broker, "/publish", r.text("topic"), Json.read(r.text("body"))); db.execute("UPDATE outbox SET status='SENT' WHERE event_id=?", r.text("event_id")); }
            catch (Problems.Transient failure) { return; }
        }
    }
    void tick() {
        snapshot(); flush();
        var claim = AgenticBroker.call(broker, "/claim", "workflow", AgenticHttp.empty());
        if (!claim.path("available").asBoolean()) { snapshot(); return; }
        var e = claim.path("event"); boolean ack = true;
        try { handle(e); }
        catch (Problems.Permanent failure) { reject(e, failure instanceof Problems.Policy ? "POLICY" : failure instanceof Problems.Contract ? "VALIDATION" : "PROCESSING", failure.reason, !(failure instanceof Problems.Contract)); }
        catch (Problems.Transient failure) {
            var r = operation();
            if ((r != null && r.number("attempts") >= 3) || claim.path("deliveryCount").asInt() >= 3) reject(e, "PROCESSING", "RETRY_BUDGET_EXHAUSTED", true);
            else ack = false;
        }
        AgenticBroker.call(broker, ack ? "/ack" : "/nack", "workflow", claim);
        // Export the committed state before a fast consumer can finish and read the run summary.
        snapshot(); flush();
    }
    void snapshot() {
        var n = (com.fasterxml.jackson.databind.node.ObjectNode) status(operation());
        var r = operation(); n.put("executionAttempts", r == null ? 0 : r.number("attempts"));
        var reuse = db.one("SELECT n FROM counters WHERE name='verifiedReuse'"); n.put("verifiedReuse", reuse == null ? 0 : reuse.number("n"));
        n.put("failureCount", db.one("SELECT COUNT(*) n FROM failures").number("n"));
        String serialized = Json.write(n);
        Path checkpoint = root.resolve("workflow-status.json");
        if (!serialized.equals(lastSnapshot) || !Files.exists(checkpoint)) {
            AgenticHttp.save(checkpoint, n);
            lastSnapshot = serialized;
        }
    }
    public void close() { db.close(); }
}
