package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;

/** Durable application-owned model/tool loop; public evidence excludes model thought/signature content. */
final class AgenticPlanner implements AutoCloseable {
    final Database db;
    final Path root;
    final PlannerModel model;
    final int broker;
    final long waitMs;
    final Runnable supervise;
    final boolean completionFault;
    AgenticPlanner(Path root, PlannerModel model, int broker, String request, long waitMs, boolean completionFault, Runnable supervise) {
        this.root = root; this.model = model; this.broker = broker; this.waitMs = waitMs;
        this.completionFault = completionFault; this.supervise = supervise; db = new Database(root, "agentic-agent");
        if (meta("conversation") == null) {
            var contents = Json.MAPPER.createArrayNode().add(Json.MAPPER.createObjectNode().put("role", "user")
                .set("parts", Json.MAPPER.createArrayNode().add(Json.MAPPER.createObjectNode().put("text", request))));
            put("conversation", Json.write(contents)); put("turn", "0"); put("unknownTurn", "-1");
            put("source", model.source()); put("model", model.model()); put("integration", model.integration());
        } else if (!model.source().equals(meta("source")) || !model.model().equals(meta("model")) || !model.integration().equals(meta("integration")))
            throw new IllegalArgumentException("Resume must use the original mode, model and model integration");
    }
    String meta(String key) { var r = db.one("SELECT val FROM agent_meta WHERE name=?", key); return r == null ? null : r.text("val"); }
    void put(String key, String value) { db.execute("MERGE INTO agent_meta KEY(name) VALUES(?,?)", key, value); }
    int number(String key) { String n = meta(key); return n == null ? 0 : Integer.parseInt(n); }
    void increment(String key) { put(key, Integer.toString(number(key) + 1)); }
    void trace(String kind, Object details) {
        var n = Json.MAPPER.createObjectNode().put("kind", kind).put("source", model.source()).put("turn", number("turn"));
        n.set("details", Json.MAPPER.valueToTree(details));
        try { Files.writeString(root.resolve("agent-trace.jsonl"), Json.write(n) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
        System.out.println("[" + model.source() + "] " + kind + " " + Json.write(details));
    }
    ArrayNode conversation() { return (ArrayNode) Json.read(meta("conversation")); }
    void ask() {
        int turn = number("turn") + 1;
        if (turn > 10) throw new IllegalStateException("MODEL_TURN_BUDGET_EXHAUSTED");
        PlannerModel.Reply reply = null;
        while (number("apiAttempts") < 3) {
            increment("apiAttempts");
            long started = System.nanoTime();
            trace("MODEL_REQUEST_STARTED", Map.of("nextTurn", turn, "attempt", number("apiAttempts"), "model", model.model()));
            try {
                reply = model.generate(conversation());
                trace("MODEL_REQUEST_COMPLETED", Map.of("nextTurn", turn, "elapsedMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), "toolCalls", reply.calls().size()));
                break;
            }
            catch (Problems.Transient e) { System.out.println("[MODEL_API_RETRY] attempt=" + number("apiAttempts") + " reason=" + e.getMessage()); }
        }
        if (reply == null) throw new IllegalStateException("MODEL_API_BUDGET_EXHAUSTED");
        final var output = reply;
        db.transaction(c -> {
            var history = conversation(); history.add(output.content()); put("conversation", Json.write(history));
            put("turn", Integer.toString(turn)); put("apiAttempts", "0"); increment("modelReplies");
            if (output.calls().isEmpty()) { put("done", "true"); put("finalText", output.text()); return null; }
            int order = 0;
            for (var call : output.calls()) {
                String appId = UUID.randomUUID().toString(); String response = null;
                boolean valid = AgenticEvents.validTool(call.name(), call.args());
                if (!valid) response = Json.write(Map.of("error", "INVALID_TOOL_REQUEST", "channel", "VALIDATION"));
                Database.execute(c, "INSERT INTO pending VALUES(?,?,?,?,?,?,?)", appId, order++, turn, call.name(), call.providerId(), response, System.currentTimeMillis() + waitMs);
                if (valid) {
                    String type = switch (call.name()) { case "get_invoice_context" -> "ContextRequested"; case "get_payment_status" -> "StatusRequested"; default -> "ActionProposed"; };
                    var event = AgenticEvents.event(type, appId);
                    Database.execute(c, "INSERT INTO outbox(event_id,body) VALUES(?,?)", event.path("eventId").asText(), Json.write(event));
                    if ("submit_payment".equals(call.name())) {
                        increment("submitProposals");
                        if (number("unknownTurn") >= 0 && turn > number("unknownTurn")) increment("freshPaymentProposalsAfterUnknown");
                    }
                    if ("get_payment_status".equals(call.name()) && number("unknownTurn") >= 0 && turn > number("unknownTurn")) increment("statusQueriesAfterUnknown");
                }
            }
            // The completion consumer resumes after the model has made its next decision.
            if (number("unknownTurn") >= 0 && turn > number("unknownTurn")) put("paused", "false");
            return null;
        });
        for (var call : reply.calls()) trace("TOOL_PROPOSED", Map.of("name", call.name(), "args", call.args()));
        if (!reply.text().isBlank()) trace("MODEL_TEXT", Map.of("text", reply.text()));
    }
    void flush() {
        for (var r : db.rows("SELECT * FROM outbox WHERE status='READY'")) {
            if (r.number("attempts") >= 3) throw new IllegalStateException("AGENT_OUTBOX_BUDGET_EXHAUSTED");
            db.execute("UPDATE outbox SET attempts=attempts+1 WHERE event_id=?", r.text("event_id"));
            try { AgenticBroker.call(broker, "/publish", "workflow", Json.read(r.text("body"))); db.execute("UPDATE outbox SET status='SENT' WHERE event_id=?", r.text("event_id")); }
            catch (Problems.Transient e) { return; }
        }
    }
    boolean consume() {
        if ("true".equals(meta("paused"))) return false;
        var claim = AgenticBroker.call(broker, "/claim", "agent", AgenticHttp.empty());
        if (!claim.path("available").asBoolean()) return false;
        var e = claim.path("event"); AgenticEvents.validate(e);
        if (!java.util.Set.of("ToolResultReady", "WorkflowVerified").contains(e.path("type").asText())) throw new Problems.Contract("AGENT_EVENT_WRONG_DESTINATION");
        if (completionFault && "WorkflowVerified".equals(e.path("type").asText()) && meta("faultTriggered") == null) {
            put("faultTriggered", "true"); put("paused", "true");
            trace("COMPLETION_CONSUMER_PAUSED", Map.of("eventId", e.path("eventId").asText(), "saved", false, "acknowledged", false));
            return true;
        }
        String id = e.path("eventId").asText(), fp = Json.fingerprint(e);
        db.transaction(c -> {
            var seen = Database.one(c, "SELECT fingerprint FROM inbox WHERE event_id=?", id);
            if (seen != null && !fp.equals(seen.text("fingerprint"))) throw new Problems.Contract("AGENT_INBOX_CONFLICT");
            if (seen == null) {
                Database.execute(c, "INSERT INTO inbox VALUES(?,?)", id, fp);
                Database.execute(c, "UPDATE pending SET response=? WHERE call_id=? AND response IS NULL", Json.write(e.path("result")), e.path("toolCallId").asText());
            }
            put("maxCompletionDeliveryCount", Integer.toString(Math.max(number("maxCompletionDeliveryCount"), claim.path("deliveryCount").asInt())));
            return null;
        });
        AgenticBroker.call(broker, "/ack", "agent", claim);
        trace("EVENT_OBSERVED", Map.of("type", e.path("type").asText(), "toolCallId", e.path("toolCallId").asText(), "deliveryCount", claim.path("deliveryCount").asInt()));
        return true;
    }
    void awaitTools() throws Exception {
        long limit = System.currentTimeMillis() + 90000;
        while (db.one("SELECT COUNT(*) n FROM pending WHERE response IS NULL").number("n") > 0) {
            if (System.currentTimeMillis() > limit) throw new IllegalStateException("LOCAL_TOOL_WAIT_BUDGET_EXHAUSTED");
            supervise.run(); flush(); consume();
            for (var r : db.rows("SELECT * FROM pending WHERE response IS NULL AND deadline<=?", System.currentTimeMillis())) {
                // A timeout is an observation at the agent boundary, never a failed provider instruction.
                var unknown = Json.MAPPER.createObjectNode().put("status", "UNKNOWN").put("reason", "COMPLETION_NOT_OBSERVED").put("operationId", AgenticEvents.OPERATION);
                db.transaction(c -> { Database.execute(c, "UPDATE pending SET response=? WHERE call_id=? AND response IS NULL", Json.write(unknown), r.text("call_id"));
                    if ("submit_payment".equals(r.text("tool_name"))) put("unknownTurn", Integer.toString(r.number("turn_no"))); return null; });
                trace("TOOL_UNKNOWN", Map.of("name", r.text("tool_name"), "result", unknown));
            }
            Thread.sleep(40);
        }
        db.transaction(c -> {
            var parts = Json.MAPPER.createArrayNode();
            for (var r : db.rows("SELECT * FROM pending ORDER BY call_order")) {
                var response = Json.read(r.text("response"));
                var f = Json.MAPPER.createObjectNode().put("name", r.text("tool_name")); f.set("response", response);
                if (!r.text("provider_id").isBlank()) f.put("id", r.text("provider_id"));
                parts.add(Json.MAPPER.createObjectNode().set("functionResponse", f));
                if ("VERIFIED".equals(response.path("state").asText())) put("verifiedObserved", "true");
            }
            var history = conversation(); history.add(Json.MAPPER.createObjectNode().put("role", "user").set("parts", parts));
            put("conversation", Json.write(history)); Database.execute(c, "DELETE FROM pending"); return null;
        });
        var last = conversation().get(conversation().size() - 1);
        for (var part : last.path("parts")) trace("TOOL_RESULT_TO_MODEL", part.path("functionResponse"));
    }
    void run() throws Exception {
        while (!"true".equals(meta("done"))) {
            supervise.run();
            if (db.one("SELECT COUNT(*) n FROM pending").number("n") > 0) awaitTools(); else ask();
        }
        // Drain a completion redelivery even if the model stops immediately after UNKNOWN.
        put("paused", "false"); long until = System.currentTimeMillis() + 1800;
        while (System.currentTimeMillis() < until) { supervise.run(); consume(); Thread.sleep(40); }
    }
    ObjectNode summary() {
        String behavior = number("unknownTurn") < 0 ? "NO_UNKNOWN_TOOL_RESULT"
            : number("freshPaymentProposalsAfterUnknown") > 0 ? model.source() + "_REPROPOSAL"
            : number("statusQueriesAfterUnknown") > 0 ? model.source() + "_STATUS_RECONCILIATION" : "UNRESOLVED_AFTER_UNKNOWN";
        return Json.MAPPER.createObjectNode().put("decisionSource", model.source()).put("model", model.model())
            .put("modelIntegration", model.integration())
            .put("observedBehavior", behavior).put("modelReplies", number("modelReplies"))
            .put("submitProposals", number("submitProposals")).put("freshPaymentProposalsAfterUnknown", number("freshPaymentProposalsAfterUnknown"))
            .put("statusQueriesAfterUnknown", number("statusQueriesAfterUnknown"))
            .put("verifiedResultReturnedToModel", "true".equals(meta("verifiedObserved")))
            .put("completionFaultTriggered", meta("faultTriggered") != null).put("maxCompletionDeliveryCount", number("maxCompletionDeliveryCount"));
    }
    public void close() { db.close(); }
}
