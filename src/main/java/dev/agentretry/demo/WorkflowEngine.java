package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.function.Consumer;

/** Small, single-dispatcher application protocol with a persisted inbox/outbox and retry budgets. */
final class WorkflowEngine implements AutoCloseable {
    static final int MAX_ATTEMPTS = 3;
    static final long DEADLINE_MILLIS = 15 * 60 * 1000;
    final Database db;
    final MockPaymentGateway gateway;
    final Contracts contracts = new Contracts();
    private final RunLock lock;
    private final Clock clock;
    private final Faults faults;
    private final Consumer<String> out;
    private final AgentDecisionProvider decisions;
    int agentDecisionCalls;
    private final Policy policy = new Policy();
    private final Executor executor;
    private final Verifier verifier;

    WorkflowEngine(Path root, Clock clock, Faults faults, Consumer<String> output) {
        this(root, clock, faults, output,
                new ScriptedAgentDecisionProvider(ScriptedAgentDecisionProvider.Scenario.APPROVED));
    }
    WorkflowEngine(Path root, Clock clock, Faults faults, Consumer<String> output, AgentDecisionProvider decisions) {
        this.decisions = java.util.Objects.requireNonNull(decisions);
        this.clock = clock; this.faults = faults; this.out = output;
        lock = new RunLock(root);
        Database opened = null;
        try {
            opened = new Database(root, "workflow"); db = opened;
            gateway = new MockPaymentGateway(root, clock, faults);
        } catch (RuntimeException | Error e) { if (opened != null) opened.close(); lock.close(); throw e; }
        executor = new Executor(gateway); verifier = new Verifier(gateway);
        // Only the process owning the OS lock can reclaim work from the terminated predecessor.
        db.transaction(c -> {
            for (var row : Database.rows(c, "SELECT workflow_id,consumer FROM event_queue WHERE status='IN_FLIGHT'")) {
                Database.execute(c, "UPDATE stage_budgets SET next_at=? WHERE workflow_id=? AND stage=?", now(), row.text("workflow_id"), row.text("consumer"));
            }
            Database.execute(c, "UPDATE event_queue SET status='PENDING',next_at=? WHERE status='IN_FLIGHT'", now());
            return null;
        });
    }

    JsonNode plan(String workflow, JsonNode approved, JsonNode proposal, String version) {
        return plan(workflow, approved, new ScriptedAgentDecisionProvider(proposal), version);
    }
    JsonNode plan(String workflow, JsonNode approved, AgentDecisionProvider provider, String version) {
        var existing = workflow(workflow);
        if (existing == null) {
            db.execute("INSERT INTO workflows(workflow_id,tenant,subject_name,role_name,status,state_version,approved_json,deadline_ms) VALUES(?,?,?,?,?,?,?,?)",
                    workflow, Planner.TENANT, "demo-operator", "PAYMENT_SUBMITTER", "NEW", 0, Json.write(approved), now() + DEADLINE_MILLIS);
        } else {
            if (!Json.actionFingerprint(Json.read(existing.text("approved_json"))).equals(Json.actionFingerprint(approved)))
                throw new Problems.Permanent("WORKFLOW_REQUEST_CONFLICT");
            if (!"NEW".equals(existing.text("status"))) return originalPlanEvent(workflow);
        }
        agentDecisionCalls++;
        var event = new Planner(provider).propose(workflow, approved, version);
        var proposal = event.path("data");
        out.accept("[AGENT_DECISION] workflow=" + workflow + " decisionSource=" + provider.source());
        out.accept("  approved: beneficiary=" + approved.path("beneficiaryRef").asText()
                + " amountMinor=" + approved.path("amountMinor").asText() + " currency=" + approved.path("currency").asText());
        out.accept("  proposed: beneficiary=" + proposal.path("beneficiaryRef").asText()
                + " amountMinor=" + proposal.path("amountMinor").asText() + " currency=" + proposal.path("currency").asText());
        try { contracts.validate(event); }
        catch (Problems.Contract e) { rejectIngress(event, e.reason); return event; }
        try { authorize(workflow(workflow), proposal, "PLAN_APPROVAL"); }
        catch (Problems.Policy e) { terminate(null, event, "POLICY", e.reason, "REJECTED", 0); return event; }
        db.transaction(c -> {
            Database.execute(c, "UPDATE workflows SET plan_json=?,status='PLANNED',state_version=1,planner_runs=planner_runs+1 WHERE workflow_id=? AND status='NEW'",
                    Json.write(proposal), workflow);
            Database.execute(c, "INSERT INTO action_intents VALUES(?,?,?,?,?,?)", Planner.actionKey(workflow), workflow,
                    Planner.TENANT, Json.actionFingerprint(proposal), "PLANNED", null);
            queue(c, event);
            faults.at(Faults.Point.BEFORE_PLAN_COMMIT, workflow);
            return null;
        });
        out.accept("[PLANNER] workflow=" + workflow + " plan=AUTHORIZED state=PLANNED stateVersion=1");
        return event;
    }
    JsonNode start(String workflow) { return plan(workflow, Json.normalInstruction(), decisions, "1.0"); }
    JsonNode originalPlanEvent(String workflow) {
        var row = db.one("SELECT event_json FROM event_queue WHERE workflow_id=? AND consumer='Executor' ORDER BY delivery_id FETCH FIRST 1 ROW ONLY", workflow);
        return row == null ? null : Json.read(row.text("event_json"));
    }
    Database.Row workflow(String id) { return db.one("SELECT * FROM workflows WHERE workflow_id=?", id); }
    Database.Row intent(String id) { return db.one("SELECT * FROM action_intents WHERE workflow_id=?", id); }
    Database.Row budget(String workflow, String stage) { return db.one("SELECT * FROM stage_budgets WHERE workflow_id=? AND stage=?", workflow, stage); }
    int failures(String category) { return db.one("SELECT COUNT(*) AS n FROM failures WHERE category=?", category).number("n"); }

    boolean enqueue(JsonNode event) {
        try { contracts.validate(event); }
        catch (Problems.Contract e) { rejectIngress(event, e.reason); return false; }
        if (workflow(event.path("workflowId").asText()) == null) {
            failure(null, event, "VALIDATION", "UNKNOWN_WORKFLOW", "QUARANTINED", 0); return false;
        }
        db.transaction(c -> { queue(c, event); return null; });
        return true;
    }
    private void queue(Connection c, JsonNode event) throws SQLException {
        contracts.validate(event);
        String workflow = event.path("workflowId").asText(), stage = consumer(event);
        if (Database.one(c, "SELECT stage FROM stage_budgets WHERE workflow_id=? AND stage=?", workflow, stage) == null)
            Database.execute(c, "INSERT INTO stage_budgets(workflow_id,stage) VALUES(?,?)", workflow, stage);
        Database.execute(c, "INSERT INTO event_queue(workflow_id,consumer,source,event_id,event_json,status,next_at) VALUES(?,?,?,?,?,'PENDING',?)",
                workflow, stage, event.path("source").asText(), event.path("eventId").asText(), Json.write(event), now());
    }

    void pump() {
        for (int dispatched = 0; dispatched < 100; dispatched++) {
            var q = db.one("SELECT * FROM event_queue WHERE status IN('PENDING','RETRY_WAIT') AND next_at<=? ORDER BY delivery_id FETCH FIRST 1 ROW ONLY", now());
            if (q == null) return;
            deliver(q);
        }
        throw new IllegalStateException("Unexpected queue growth");
    }
    private void deliver(Database.Row q) {
        JsonNode e;
        try { e = contracts.parse(q.text("event_json")); }
        catch (Problems.Contract problem) {
            var identity = Json.MAPPER.createObjectNode().put("workflowId", q.text("workflow_id")).put("eventId", q.text("event_id"));
            terminate(q, identity, "VALIDATION", problem.reason, "REJECTED", 0); return;
        }
        String workflow = e.path("workflowId").asText(), stage = q.text("consumer");
        var w = workflow(workflow); var a = intent(workflow);
        var processed = db.one("SELECT * FROM processed_events WHERE consumer=? AND source=? AND event_id=?", stage, e.path("source").asText(), e.path("eventId").asText());
        if (processed != null) {
            if (!processed.text("fingerprint").equals(Json.fingerprint(e))) {
                terminate(q, e, "PROCESSING", "EVENT_ID_CONTENT_CONFLICT", "NEEDS_REVIEW", 0); return;
            }
            if ("DONE".equals(processed.text("status"))) {
                db.execute("UPDATE event_queue SET status='ACKED' WHERE delivery_id=?", q.millis("delivery_id"));
                out.accept("[DUPLICATE_EVENT] eventId=" + e.path("eventId").asText() + " ignored=true"); return;
            }
        }
        try {
            checkIdentity(e, w, a);
            if (knownOutcome(q, e, w, a)) return;
            if ("NEEDS_REVIEW".equals(w.text("status")) || "REJECTED".equals(w.text("status")))
                throw new Problems.Permanent("WORKFLOW_TERMINAL");
            checkState(e, w, a, stage);
        } catch (Problems.Policy problem) { terminate(q, e, "POLICY", problem.reason, "NEEDS_REVIEW", 0); return; }
        catch (Problems.Permanent problem) { terminate(q, e, "PROCESSING", problem.reason, "NEEDS_REVIEW", 0); return; }

        var b = budget(workflow, stage);
        if (now() < b.millis("next_at")) {
            db.execute("UPDATE event_queue SET status='RETRY_WAIT',next_at=? WHERE delivery_id=?", b.millis("next_at"), q.millis("delivery_id")); return;
        }
        if (now() >= w.millis("deadline_ms")) { terminate(q, e, "PROCESSING", "DEADLINE_EXPIRED", "NEEDS_REVIEW", b.number("processing_attempts")); return; }
        if (b.number("delivery_attempts") >= MAX_ATTEMPTS) { terminate(q, e, "DELIVERY", "DELIVERY_BUDGET_EXHAUSTED", "NEEDS_REVIEW", MAX_ATTEMPTS); return; }
        if (b.number("processing_attempts") >= MAX_ATTEMPTS) { terminate(q, e, "PROCESSING", "PROCESSING_BUDGET_EXHAUSTED", "NEEDS_REVIEW", MAX_ATTEMPTS); return; }
        db.transaction(c -> {
            Database.execute(c, "UPDATE stage_budgets SET delivery_attempts=delivery_attempts+1 WHERE workflow_id=? AND stage=?", workflow, stage);
            Database.execute(c, "UPDATE event_queue SET status='IN_FLIGHT' WHERE delivery_id=?", q.millis("delivery_id"));
            return null;
        });
        try { faults.at(Faults.Point.BEFORE_DELIVERY, workflow); }
        catch (Problems.Transient problem) { retry(q, e, "DELIVERY", problem.getMessage()); return; }

        db.transaction(c -> {
            Database.execute(c, "UPDATE stage_budgets SET processing_attempts=processing_attempts+1 WHERE workflow_id=? AND stage=?", workflow, stage);
            if (processed == null) Database.execute(c, "INSERT INTO processed_events VALUES(?,?,?,?,'IN_PROGRESS',NULL)", stage, e.path("source").asText(), e.path("eventId").asText(), Json.fingerprint(e));
            if ("Executor".equals(stage) && "PLANNED".equals(w.text("status"))) {
                int changed = Database.execute(c, "UPDATE workflows SET status='EXECUTING',state_version=2 WHERE workflow_id=? AND status='PLANNED' AND state_version=1", workflow);
                if (changed != 1) throw new Problems.Permanent("STATE_VERSION_CONFLICT");
                Database.execute(c, "UPDATE action_intents SET status='IN_PROGRESS' WHERE workflow_id=?", workflow);
            }
            return null;
        });
        try {
            if ("Executor".equals(stage)) execute(q, e);
            else if ("Verifier".equals(stage)) verify(q, e);
            else db.transaction(c -> { done(c, q, e, "OBSERVED"); return null; });
        } catch (Problems.Policy problem) { terminate(q, e, "POLICY", problem.reason, "NEEDS_REVIEW", budget(workflow, stage).number("processing_attempts")); }
        catch (Problems.Transient problem) { retry(q, e, "PROCESSING", problem.getMessage()); }
        catch (Problems.Permanent problem) { terminate(q, e, "PROCESSING", problem.reason, "NEEDS_REVIEW", budget(workflow, stage).number("processing_attempts")); }
    }
    private void execute(Database.Row q, JsonNode event) {
        String workflow = event.path("workflowId").asText(); var w = workflow(workflow); var a = intent(workflow);
        JsonNode plan = Json.read(w.text("plan_json"));
        authorize(w, plan, "PRE_EXECUTION");
        faults.at(Faults.Point.BEFORE_EXECUTION, workflow);
        var receipt = executor.execute(w.text("tenant"), a.text("action_key"), plan, out);
        faults.at(Faults.Point.AFTER_GATEWAY_COMMIT, workflow);
        var data = Json.MAPPER.createObjectNode().put("instructionId", receipt.instructionId()).put("status", "ACCEPTED");
        var next = Planner.event(workflow, "executor", "ActionCompleted", 3, "evt-" + workflow + "-action-completed",
                event.path("eventId").asText(), data, "1.0");
        db.transaction(c -> {
            Database.execute(c, "UPDATE action_intents SET status='EXECUTED',receipt_json=? WHERE workflow_id=?", Json.write(receipt), workflow);
            int changed = Database.execute(c, "UPDATE workflows SET status='EXECUTED',state_version=3 WHERE workflow_id=? AND status='EXECUTING' AND state_version=2", workflow);
            if (changed != 1) throw new Problems.Permanent("STATE_VERSION_CONFLICT");
            done(c, q, event, "EXECUTED"); queue(c, next); return null;
        });
        out.accept("[EXECUTOR] workflow=" + workflow + " state=EXECUTED receiptSaved=true inputAcknowledged=true");
        faults.at(Faults.Point.AFTER_LOCAL_RESULT_COMMIT, workflow);
    }
    private void verify(Database.Row q, JsonNode event) {
        String workflow = event.path("workflowId").asText(); var w = workflow(workflow); var a = intent(workflow);
        faults.at(Faults.Point.BEFORE_VERIFICATION, workflow);
        verifier.verify(w.text("tenant"), a.text("action_key"), Json.read(w.text("approved_json")), Json.read(a.text("receipt_json")));
        var nextData = Json.MAPPER.createObjectNode().put("instructionId", event.path("data").path("instructionId").asText()).put("verified", true);
        var next = Planner.event(workflow, "verifier", "WorkflowVerified", 4, "evt-" + workflow + "-verified", event.path("eventId").asText(), nextData, "1.0");
        db.transaction(c -> {
            int changed = Database.execute(c, "UPDATE workflows SET status='COMPLETED',state_version=4 WHERE workflow_id=? AND status='EXECUTED' AND state_version=3", workflow);
            if (changed != 1) throw new Problems.Permanent("STATE_VERSION_CONFLICT");
            done(c, q, event, "VERIFIED"); queue(c, next); return null;
        });
        out.accept("[VERIFIER] workflow=" + workflow + " evidence=MATCH state=COMPLETED");
    }
    private void checkIdentity(JsonNode event, Database.Row w, Database.Row a) {
        String id = event.path("workflowId").asText();
        if (w == null || a == null) throw new Problems.Permanent("UNKNOWN_ACTION");
        if (!Planner.actionKey(id).equals(event.path("idempotencyKey").asText()) || !id.equals(event.path("correlationId").asText()))
            throw new Problems.Permanent("ACTION_IDENTITY_MISMATCH");
        if ("TaskPlanned".equals(event.path("eventType").asText())) {
            if (!a.text("fingerprint").equals(Json.actionFingerprint(event.path("data")))) throw new Problems.Permanent("ACTION_KEY_PARAMETER_CONFLICT");
        } else {
            if (a.text("receipt_json") == null || !Json.read(a.text("receipt_json")).path("instructionId").asText().equals(event.path("data").path("instructionId").asText()))
                throw new Problems.Permanent("RECEIPT_IDENTITY_MISMATCH");
        }
    }
    private boolean knownOutcome(Database.Row q, JsonNode event, Database.Row w, Database.Row a) {
        boolean completedAction = "TaskPlanned".equals(event.path("eventType").asText()) && "EXECUTED".equals(a.text("status"));
        boolean completedVerification = "ActionCompleted".equals(event.path("eventType").asText()) && "COMPLETED".equals(w.text("status"));
        if (!completedAction && !completedVerification) return false;
        // No new side effect is authorized by returning a matching, previously committed outcome.
        db.transaction(c -> { done(c, q, event, "REUSED_ACTION"); return null; });
        out.accept("[REUSED_ACTION] newEventId=" + event.path("eventId").asText() + " sameActionKey=true externalActionRepeated=false");
        return true;
    }
    private void checkState(JsonNode event, Database.Row w, Database.Row a, String stage) {
        int expected = event.path("expectedStateVersion").asInt();
        boolean newExecution = "Executor".equals(stage) && "PLANNED".equals(w.text("status")) && w.number("state_version") == 1 && expected == 1;
        boolean resumeExecution = "Executor".equals(stage) && "EXECUTING".equals(w.text("status")) && w.number("state_version") == 2
                && expected == 1 && "IN_PROGRESS".equals(a.text("status"));
        boolean verification = "Verifier".equals(stage) && "EXECUTED".equals(w.text("status")) && w.number("state_version") == 3 && expected == 3;
        boolean audit = "Audit".equals(stage) && "COMPLETED".equals(w.text("status")) && expected == 4;
        if (!(newExecution || resumeExecution || verification || audit)) throw new Problems.Policy("STALE_OR_INVALID_STATE");
    }
    private void authorize(Database.Row w, JsonNode proposal, String checkpoint) {
        try { policy.authorize(w, proposal, now()); decision(w, checkpoint, "ALLOW", "APPROVED"); }
        catch (Problems.Policy problem) { decision(w, checkpoint, "DENY", problem.reason); throw problem; }
    }
    private void decision(Database.Row w, String checkpoint, String result, String reason) {
        db.execute("INSERT INTO policy_decisions(workflow_id,checkpoint,subject_name,policy_version,decision,reason,recorded_at) VALUES(?,?,?,?,?,?,?)",
                w.text("workflow_id"), checkpoint, w.text("subject_name"), Policy.VERSION, result, reason, now());
    }
    private void done(Connection c, Database.Row q, JsonNode event, String outcome) throws SQLException {
        Database.execute(c, "MERGE INTO processed_events KEY(consumer,source,event_id) VALUES(?,?,?,?,'DONE',?)",
                q.text("consumer"), event.path("source").asText(), event.path("eventId").asText(), Json.fingerprint(event), outcome);
        Database.execute(c, "UPDATE event_queue SET status='ACKED' WHERE delivery_id=?", q.millis("delivery_id"));
    }
    private void retry(Database.Row q, JsonNode event, String category, String reason) {
        String workflow = event.path("workflowId").asText(), stage = q.text("consumer"); var b = budget(workflow, stage);
        int attempt = b.number("DELIVERY".equals(category) ? "delivery_attempts" : "processing_attempts");
        long next = now() + (1000L << (attempt - 1));
        if (attempt >= MAX_ATTEMPTS || next >= workflow(workflow).millis("deadline_ms")) {
            terminate(q, event, category, reason + ":BUDGET_OR_DEADLINE_EXHAUSTED", "NEEDS_REVIEW", attempt); return;
        }
        db.transaction(c -> {
            Database.execute(c, "UPDATE event_queue SET status='RETRY_WAIT',next_at=? WHERE delivery_id=?", next, q.millis("delivery_id"));
            Database.execute(c, "UPDATE stage_budgets SET next_at=? WHERE workflow_id=? AND stage=?", next, workflow, stage);
            failure(c, q, event, category, reason, "RETRY_WAIT", attempt); return null;
        });
        out.accept("[" + category + "_FAILURE] workflow=" + workflow + " attempt=" + attempt + "/3 nextAttemptAt=" + next);
    }
    private void rejectIngress(JsonNode event, String reason) {
        terminate(null, event, "VALIDATION", reason, "REJECTED", 0);
    }
    private void terminate(Database.Row q, JsonNode event, String category, String reason, String status, int attempt) {
        String id = event == null ? null : event.path("workflowId").asText(null);
        db.transaction(c -> {
            if (q != null) Database.execute(c, "UPDATE event_queue SET status='QUARANTINED' WHERE delivery_id=?", q.millis("delivery_id"));
            // A bad replay cannot erase an already completed workflow or a recorded acceptance receipt.
            if (id != null) Database.execute(c, "UPDATE workflows SET status=? WHERE workflow_id=? AND status NOT IN('COMPLETED','REJECTED')", status, id);
            failure(c, q, event, category, reason, status, attempt); return null;
        });
        out.accept("[" + category + "_REJECTED] workflow=" + id + " reason=" + reason + " outcome=" + status);
    }
    private void failure(Database.Row q, JsonNode event, String category, String reason, String disposition, int attempt) {
        db.transaction(c -> { failure(c, q, event, category, reason, disposition, attempt); return null; });
    }
    private void failure(Connection c, Database.Row q, JsonNode event, String category, String reason, String disposition, int attempt) throws SQLException {
        Database.execute(c, "INSERT INTO failures(workflow_id,event_id,stage,category,reason,disposition,attempt_number,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                event == null ? null : event.path("workflowId").asText(null), event == null ? null : event.path("eventId").asText(null),
                q == null ? "Ingress" : q.text("consumer"), category, reason.length() > 3900 ? reason.substring(0, 3900) : reason,
                disposition, attempt, now());
    }
    private static String consumer(JsonNode event) {
        return switch (event.path("eventType").asText()) { case "TaskPlanned" -> "Executor"; case "ActionCompleted" -> "Verifier"; case "WorkflowVerified" -> "Audit"; default -> throw new Problems.Contract("UNKNOWN_EVENT_TYPE"); };
    }
    private long now() { return clock.millis(); }
    @Override public void close() { try { db.close(); } finally { try { gateway.close(); } finally { lock.close(); } } }
}
