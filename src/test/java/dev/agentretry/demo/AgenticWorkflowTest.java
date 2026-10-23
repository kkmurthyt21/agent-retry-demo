package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgenticWorkflowTest {
    @TempDir Path root;
    @Test void unchangedStatusDoesNotRewriteItsExportButStateChangesAreSaved() throws Exception {
        try (var p = new MockPaymentGateway(root.resolve("provider"), Clock.systemUTC(), Faults.NONE);
             var w = new AgenticWorkflow(root, 0, p, false, false)) {
            Path status = root.resolve("workflow-status.json"); w.snapshot();
            Files.setLastModifiedTime(status, FileTime.fromMillis(2000));
            var unchangedTime = Files.getLastModifiedTime(status);
            w.snapshot(); assertEquals(unchangedTime, Files.getLastModifiedTime(status));
            w.handle(AgenticEvents.event("ActionProposed", "call")); w.snapshot();
            assertEquals("PLANNED", Json.read(Files.readString(status)).path("state").asText());
            assertNotEquals(unchangedTime, Files.getLastModifiedTime(status));
        }
    }
    @Test void failedStatusExportDoesNotMarkTheNewSnapshotAsSaved() throws Exception {
        try (var p = new MockPaymentGateway(root.resolve("provider"), Clock.systemUTC(), Faults.NONE);
             var w = new AgenticWorkflow(root, 0, p, false, false)) {
            Path status = root.resolve("workflow-status.json"); w.snapshot(); String previous = Files.readString(status);
            Files.delete(status); Files.createDirectory(status);
            w.handle(AgenticEvents.event("ActionProposed", "call"));
            var error = assertThrows(IllegalStateException.class, w::snapshot);
            assertTrue(error.getMessage().contains("workflow-status.json"));
            assertEquals("PLANNED", w.operation().text("state"));
            Files.delete(status); Files.writeString(status, previous); w.snapshot();
            assertEquals("PLANNED", Json.read(Files.readString(status)).path("state").asText());
        }
    }
    com.fasterxml.jackson.databind.JsonNode next(AgenticWorkflow w, String type) {
        return w.db.rows("SELECT body FROM outbox").stream().map(r -> Json.read(r.text("body"))).filter(e -> type.equals(e.path("type").asText())).findFirst().orElseThrow();
    }
    @Test void newModelProposalAfterVerificationReusesApprovedOperationAndReceipt() {
        try (var provider = new MockPaymentGateway(root.resolve("provider"), Clock.systemUTC(), Faults.NONE);
             var w = new AgenticWorkflow(root, 0, provider, false, false)) {
            var proposal = AgenticEvents.event("ActionProposed", "call-1");
            w.handle(proposal); w.handle(proposal); w.handle(AgenticEvents.event("ActionProposed", "parallel-call"));
            w.handle(next(w, "TaskPlanned")); w.handle(next(w, "ActionCompleted"));
            assertEquals("VERIFIED", w.operation().text("state"));
            w.handle(AgenticEvents.event("ActionProposed", "new-model-call"));
            assertEquals(1, provider.count()); assertEquals(1, provider.submissionCalls.get());
            assertEquals(1, w.operation().number("attempts"));
            assertEquals(3, w.db.rows("SELECT body FROM outbox WHERE topic='agent'").size());
        }
    }
    @Test void staleExecutionEventCannotAdvancePlannedVersionOne() {
        try (var p = new MockPaymentGateway(root.resolve("provider"), Clock.systemUTC(), Faults.NONE);
             var w = new AgenticWorkflow(root, 0, p, false, false)) {
            w.handle(AgenticEvents.event("ActionProposed", "call"));
            assertThrows(Problems.Contract.class, () -> w.handle(AgenticEvents.event("TaskPlanned", "call").put("expectedStateVersion", 0)));
            assertEquals("PLANNED", w.operation().text("state")); assertEquals(0, p.count());
        }
    }
    @Test void heldInvoiceIsRejectedEvenIfModelRequestsPayment() {
        try (var p = new MockPaymentGateway(root.resolve("provider"), Clock.systemUTC(), Faults.NONE);
             var w = new AgenticWorkflow(root, 0, p, true, false)) {
            assertThrows(Problems.Policy.class, () -> w.handle(AgenticEvents.event("ActionProposed", "call")));
            assertEquals(0, p.count()); assertNull(w.operation());
        }
    }
    @Test void rechecksHoldAfterPlanningBeforeExternalEffect() {
        try (var p = new MockPaymentGateway(root.resolve("provider"), Clock.systemUTC(), Faults.NONE)) {
            com.fasterxml.jackson.databind.JsonNode planned;
            try (var w = new AgenticWorkflow(root, 0, p, false, false)) { w.handle(AgenticEvents.event("ActionProposed", "call")); planned = next(w, "TaskPlanned"); }
            try (var w = new AgenticWorkflow(root, 0, p, true, false)) { assertThrows(Problems.Policy.class, () -> w.handle(planned)); assertEquals(0, p.count()); }
        }
    }
    @Test void providerEvidenceMustMatchImmutableApprovedIntent() {
        try (var p = new MockPaymentGateway(root.resolve("provider"), Clock.systemUTC(), Faults.NONE);
             var w = new AgenticWorkflow(root, 0, p, false, false)) {
            w.handle(AgenticEvents.event("ActionProposed", "call")); w.handle(next(w, "TaskPlanned"));
            w.db.execute("UPDATE operations SET intent=?", Json.write(Json.instruction("different-beneficiary", 25000)));
            assertThrows(Problems.Permanent.class, () -> w.handle(next(w, "ActionCompleted")));
            assertEquals("EXECUTED", w.operation().text("state"));
        }
    }
    @Test void stageBudgetSurvivesRestartAndFreshProposals() {
        PaymentGateway unavailable = new PaymentGateway() {
            public Receipt find(String t, String k) { throw new Problems.Transient("DOWN"); }
            public Submission submit(String t, String k, com.fasterxml.jackson.databind.JsonNode n) { fail("Lookup failure must not proceed to submit"); return null; }
            public com.fasterxml.jackson.databind.JsonNode readInstruction(String id) { return null; }
        };
        com.fasterxml.jackson.databind.JsonNode planned;
        try (var w = new AgenticWorkflow(root, 0, unavailable, false, false)) {
            w.handle(AgenticEvents.event("ActionProposed", "first")); planned = next(w, "TaskPlanned");
            assertThrows(Problems.Transient.class, () -> w.handle(planned));
        }
        try (var w = new AgenticWorkflow(root, 0, unavailable, false, false)) {
            w.handle(AgenticEvents.event("ActionProposed", "new-envelope"));
            assertThrows(Problems.Transient.class, () -> w.handle(planned));
            assertThrows(Problems.Transient.class, () -> w.handle(planned));
            assertThrows(Problems.Permanent.class, () -> w.handle(planned));
            assertEquals(3, w.operation().number("attempts"));
        }
    }
}
