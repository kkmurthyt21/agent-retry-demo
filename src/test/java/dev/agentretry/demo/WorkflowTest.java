package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkflowTest {
    @TempDir Path root;
    final MutableClock clock = new MutableClock();
    WorkflowEngine open(Faults f) { return new WorkflowEngine(root, clock, f, ignored -> { }); }
    WorkflowEngine open() { return open(Faults.NONE); }
    static final class MutableClock extends Clock {
        long time = Instant.parse("2026-10-18T13:00:00Z").toEpochMilli();
        void advance(long millis) { time += millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(time); }
        @Override public long millis() { return time; }
    }
    static ObjectNode copy(JsonNode n) { return (ObjectNode) n.deepCopy(); }
    static void replacePendingDelivery(WorkflowEngine app, JsonNode event) {
        // Emulate a producer/transport delivering different JSON at the consumer boundary.
        app.db.execute("UPDATE event_queue SET event_json=? WHERE workflow_id=? AND consumer='Executor' AND status='PENDING'",
                Json.write(event), event.path("workflowId").asText());
    }

    @Test void normalFlowPersistsPlanReceiptAndVerifiedOutcome() {
        try (var app = open()) {
            app.start("wf-test"); app.pump();
            assertEquals("COMPLETED", app.workflow("wf-test").text("status"));
            assertEquals(4, app.workflow("wf-test").number("state_version"));
            assertEquals(1, app.workflow("wf-test").number("planner_runs"));
            assertNotNull(app.intent("wf-test").text("receipt_json"));
            assertEquals(1, app.gateway.count());
            assertEquals(3, app.db.one("SELECT COUNT(*) AS n FROM event_queue WHERE status='ACKED'").number("n"));
        }
    }
    @Test void repeatedEventDoesNotInvokeCompletedStageAgain() {
        try (var app = open()) {
            var event = app.start("wf-test"); app.pump();
            app.enqueue(event); app.pump();
            assertEquals(1, app.gateway.count());
            assertEquals(1, app.budget("wf-test", "Executor").number("processing_attempts"));
            assertEquals(1, app.gateway.submissionCalls.get());
        }
    }
    @Test void newEnvelopeWithSameCompletedActionReusesOutcomeEvenAfterDeadline() {
        try (var app = open()) {
            var event = copy(app.start("wf-test")); app.pump();
            clock.advance(WorkflowEngine.DEADLINE_MILLIS + 1);
            event.put("eventId", "fresh-event"); app.enqueue(event); app.pump();
            assertEquals(1, app.gateway.count());
            assertEquals("COMPLETED", app.workflow("wf-test").text("status"));
            assertEquals("REUSED_ACTION", app.db.one("SELECT outcome FROM processed_events WHERE event_id='fresh-event'").text("outcome"));
        }
    }
    @Test void reusedEventIdentityWithDifferentContentIsRejected() {
        try (var app = open()) {
            var event = copy(app.start("wf-test")); app.pump();
            event.put("causationId", "different-cause"); app.enqueue(event); app.pump();
            assertEquals(1, app.failures("PROCESSING"));
            assertEquals(1, app.gateway.count());
            assertEquals("COMPLETED", app.workflow("wf-test").text("status"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"1.0", "1.1"})
    void supportedContractVersionsComplete(String version) {
        var instruction = Json.normalInstruction();
        if ("1.1".equals(version)) ((ObjectNode) instruction).put("clientReference", "client-42");
        try (var app = open()) {
            app.plan("wf-test", instruction, instruction, version); app.pump();
            assertEquals("COMPLETED", app.workflow("wf-test").text("status"));
            assertEquals(1, app.gateway.count());
        }
    }
    @Test void unsupportedVersionIsRejectedBeforePlanPublication() {
        try (var app = open()) {
            app.plan("wf-test", Json.normalInstruction(), Json.normalInstruction(), "2.0"); app.pump();
            assertEquals("REJECTED", app.workflow("wf-test").text("status"));
            assertEquals(0, app.gateway.count()); assertNull(app.intent("wf-test"));
            assertEquals(1, app.failures("VALIDATION"));
        }
    }
    static Stream<Arguments> invalidInstructions() {
        var missing = copy(Json.normalInstruction()); missing.remove("amountMinor");
        var type = copy(Json.normalInstruction()); type.put("amountMinor", "25000");
        var extra = copy(Json.normalInstruction()); extra.put("permission", "I approve myself");
        var optional = copy(Json.normalInstruction()); optional.put("clientReference", "new-field");
        return Stream.of(Arguments.of(missing), Arguments.of(type), Arguments.of(extra), Arguments.of(optional));
    }
    @ParameterizedTest @MethodSource("invalidInstructions")
    void missingWrongTypeExtraAndUnsupportedOptionalFieldsDoNotExecute(JsonNode proposal) {
        try (var app = open()) {
            app.plan("wf-test", Json.normalInstruction(), proposal, "1.0"); app.pump();
            assertEquals(0, app.gateway.count()); assertEquals(1, app.failures("VALIDATION"));
            assertNull(app.intent("wf-test"));
        }
    }
    @Test void malformedDuplicatePropertiesAndTrailingJsonAreRejected() {
        var c = new Contracts();
        assertThrows(Problems.Contract.class, () -> c.parse("{"));
        var event = new Planner().propose("wf-test", Json.normalInstruction(), "1.0");
        String valid = Json.write(event);
        assertThrows(Problems.Contract.class, () -> c.parse(valid + " {}"));
        assertThrows(Problems.Contract.class, () -> c.parse("{\"eventId\":\"duplicate\"," + valid.substring(1)));
    }
    @ParameterizedTest @ValueSource(strings = {"beneficiaryRef", "debitAccountRef", "currency", "route", "amountMinor"})
    void policyDenialProducesNoExternalAction(String field) {
        var proposal = copy(Json.normalInstruction());
        if ("amountMinor".equals(field)) proposal.put(field, 100001);
        else if ("currency".equals(field)) proposal.put(field, "EUR");
        else proposal.put(field, "not-authorized");
        try (var app = open()) {
            app.plan("wf-test", Json.normalInstruction(), proposal, "1.0"); app.pump();
            assertEquals(0, app.gateway.count()); assertEquals(1, app.failures("POLICY"));
            assertEquals("REJECTED", app.workflow("wf-test").text("status"));
            assertEquals(1, app.db.one("SELECT COUNT(*) AS n FROM policy_decisions WHERE decision='DENY'").number("n"));
        }
    }
    @Test void staleExpectedVersionCannotAuthorizeNewExecution() {
        try (var app = open()) {
            var event = copy(app.start("wf-test")); event.put("expectedStateVersion", 0);
            replacePendingDelivery(app, event); app.pump();
            assertEquals(0, app.gateway.count()); assertEquals(1, app.failures("POLICY"));
            assertEquals("NEEDS_REVIEW", app.workflow("wf-test").text("status"));
        }
    }
    @Test void changedIntentCannotReuseOriginalActionKey() {
        try (var app = open()) {
            var event = copy(app.start("wf-test")); ((ObjectNode) event.path("data")).put("amountMinor", 50000);
            replacePendingDelivery(app, event); app.pump();
            assertEquals(0, app.gateway.count()); assertEquals(1, app.failures("PROCESSING"));
        }
    }
    @Test void identityAndPolicyComeFromTrustedState() {
        try (var app = open()) {
            app.start("wf-test");
            app.db.execute("UPDATE workflows SET role_name='UNPRIVILEGED' WHERE workflow_id='wf-test'");
            app.pump(); assertEquals(0, app.gateway.count()); assertEquals(1, app.failures("POLICY"));
        }
    }
    @Test void alteredActionKeyIsRejected() {
        try (var app = open()) {
            var event = copy(app.start("wf-test")); event.put("idempotencyKey", "model-generated-key");
            replacePendingDelivery(app, event); app.pump();
            assertEquals(0, app.gateway.count()); assertEquals(1, app.failures("PROCESSING"));
        }
    }
    @Test void deliveryFailuresNeverRunTheHandlerAndBudgetSurvivesRestart() {
        Faults fail = (p, id) -> { if (p == Faults.Point.BEFORE_DELIVERY) throw new Problems.Transient("HANDOFF_UNAVAILABLE"); };
        try (var app = open(fail)) {
            app.start("wf-test"); app.pump();
            assertEquals(1, app.failures("DELIVERY")); assertEquals(0, app.failures("PROCESSING"));
            assertEquals(0, app.budget("wf-test", "Executor").number("processing_attempts"));
        }
        try (var app = open(fail)) { app.pump(); assertEquals(1, app.budget("wf-test", "Executor").number("delivery_attempts")); }
        clock.advance(1000);
        try (var app = open(fail)) { app.pump(); assertEquals(2, app.budget("wf-test", "Executor").number("delivery_attempts")); }
        clock.advance(2000);
        try (var app = open(fail)) {
            app.pump(); assertEquals(3, app.budget("wf-test", "Executor").number("delivery_attempts"));
            assertEquals(0, app.budget("wf-test", "Executor").number("processing_attempts"));
            assertEquals("NEEDS_REVIEW", app.workflow("wf-test").text("status")); assertEquals(0, app.gateway.count());
        }
    }
    @Test void transientProcessingResumesSavedIntentWithSameKey() {
        Faults fail = (p, id) -> { if (p == Faults.Point.BEFORE_EXECUTION) throw new Problems.Transient("TOOL_UNAVAILABLE"); };
        String key;
        try (var app = open(fail)) {
            app.start("wf-test"); app.pump(); key = app.intent("wf-test").text("action_key");
            assertEquals(1, app.failures("PROCESSING")); assertEquals(0, app.failures("DELIVERY"));
        }
        clock.advance(1000);
        try (var app = open()) {
            app.pump(); assertEquals("COMPLETED", app.workflow("wf-test").text("status"));
            assertEquals(key, app.intent("wf-test").text("action_key"));
            assertEquals(2, app.budget("wf-test", "Executor").number("processing_attempts"));
            assertEquals(1, app.workflow("wf-test").number("planner_runs")); assertEquals(1, app.gateway.count());
        }
    }
    @Test void threeProcessingAttemptsExhaustAcrossRestartsAndNewEnvelopeCannotResetThem() {
        Faults fail = (p, id) -> { if (p == Faults.Point.BEFORE_EXECUTION) throw new Problems.Transient("UNAVAILABLE"); };
        JsonNode event;
        try (var app = open(fail)) { event = app.start("wf-test"); app.pump(); }
        clock.advance(1000);
        try (var app = open(fail)) { app.pump(); }
        clock.advance(2000);
        try (var app = open(fail)) { app.pump(); assertEquals(3, app.budget("wf-test", "Executor").number("processing_attempts")); }
        clock.advance(10000);
        try (var app = open()) {
            var fresh = copy(event); fresh.put("eventId", "redelivered-with-fresh-id"); app.enqueue(fresh); app.pump();
            assertEquals(3, app.budget("wf-test", "Executor").number("processing_attempts"));
            assertEquals("NEEDS_REVIEW", app.workflow("wf-test").text("status")); assertEquals(0, app.gateway.count());
        }
    }
    @Test void persistedBackoffCannotBeBypassedWithFreshEnvelope() {
        Faults fail = (p, id) -> { if (p == Faults.Point.BEFORE_EXECUTION) throw new Problems.Transient("UNAVAILABLE"); };
        try (var app = open(fail)) {
            var event = copy(app.start("wf-test")); app.pump();
            event.put("eventId", "fresh-before-backoff"); app.enqueue(event); app.pump();
            assertEquals(1, app.budget("wf-test", "Executor").number("processing_attempts"));
            clock.advance(1000); app.pump();
            assertEquals(2, app.budget("wf-test", "Executor").number("processing_attempts"));
            assertEquals(0, app.gateway.count());
        }
    }
    @Test void deadlineExpiryPreventsNewSideEffect() {
        try (var app = open()) {
            app.start("wf-test"); clock.advance(WorkflowEngine.DEADLINE_MILLIS + 1); app.pump();
            assertEquals(0, app.gateway.count()); assertEquals("NEEDS_REVIEW", app.workflow("wf-test").text("status"));
            assertEquals(0, app.budget("wf-test", "Executor").number("processing_attempts"));
        }
    }
    @Test void localCompletionAndNextEventCommitTogetherAndRecoveryOnlyVerifies() {
        Faults stop = (p, id) -> { if (p == Faults.Point.AFTER_LOCAL_RESULT_COMMIT) throw new Problems.Interrupted("POWER_LOSS"); };
        try (var app = open(stop)) {
            app.start("wf-test"); assertThrows(Problems.Interrupted.class, app::pump);
            assertEquals("EXECUTED", app.workflow("wf-test").text("status")); assertNotNull(app.intent("wf-test").text("receipt_json"));
            assertEquals("ACKED", app.db.one("SELECT status FROM event_queue WHERE consumer='Executor'").text("status"));
            assertEquals("PENDING", app.db.one("SELECT status FROM event_queue WHERE consumer='Verifier'").text("status"));
        }
        try (var app = open()) {
            app.pump(); assertEquals("COMPLETED", app.workflow("wf-test").text("status"));
            assertEquals(0, app.gateway.submissionCalls.get()); assertEquals(1, app.gateway.count());
        }
    }
    @Test void interruptedVerifierDoesNotRepeatExecutor() {
        Faults fail = (p, id) -> { if (p == Faults.Point.BEFORE_VERIFICATION) throw new Problems.Transient("READ_UNAVAILABLE"); };
        try (var app = open(fail)) {
            app.start("wf-test"); app.pump(); assertEquals("EXECUTED", app.workflow("wf-test").text("status"));
            assertEquals(1, app.gateway.count());
        }
        clock.advance(1000);
        try (var app = open()) { app.pump(); assertEquals("COMPLETED", app.workflow("wf-test").text("status")); assertEquals(0, app.gateway.submissionCalls.get()); }
    }
    @Test void verifierChecksProviderRecordRatherThanTrustingSuccessMessage() {
        Faults stop = (p, id) -> { if (p == Faults.Point.AFTER_LOCAL_RESULT_COMMIT) throw new Problems.Interrupted("STOP"); };
        try (var app = open(stop)) {
            app.start("wf-test"); assertThrows(Problems.Interrupted.class, app::pump);
            app.gateway.db.execute("UPDATE instructions SET request_json=?", Json.write(Json.instruction("demo-beneficiary-001", 50000)));
        }
        try (var app = open()) {
            app.pump(); assertEquals("NEEDS_REVIEW", app.workflow("wf-test").text("status"));
            assertEquals("EXECUTED", app.intent("wf-test").text("status")); assertEquals(1, app.gateway.count());
            assertEquals("VERIFICATION_MISMATCH", app.db.one("SELECT reason FROM failures WHERE category='PROCESSING'").text("reason"));
        }
    }
    @Test void failedPlanningTransactionPublishesNothingAndCanBeResumed() {
        Faults stop = (p, id) -> { if (p == Faults.Point.BEFORE_PLAN_COMMIT) throw new Problems.Interrupted("STOP"); };
        try (var app = open(stop)) {
            assertThrows(Problems.Interrupted.class, () -> app.start("wf-test"));
            assertEquals("NEW", app.workflow("wf-test").text("status")); assertNull(app.intent("wf-test"));
            assertEquals(0, app.db.one("SELECT COUNT(*) AS n FROM event_queue").number("n"));
            assertEquals(0, app.workflow("wf-test").number("planner_runs"));
        }
        try (var app = open()) { app.start("wf-test"); app.pump(); assertEquals(1, app.gateway.count()); assertEquals(1, app.workflow("wf-test").number("planner_runs")); }
    }
    @Test void oneDispatcherOwnsDatabaseDirectory() {
        try (var app = open()) { assertThrows(IllegalStateException.class, this::open); }
        try (var app = open()) { app.start("wf-test"); app.pump(); assertEquals(1, app.gateway.count()); }
    }
}
