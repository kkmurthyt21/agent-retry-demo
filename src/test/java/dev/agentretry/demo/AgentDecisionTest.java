package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The replaceable decision source must never become the authorization or recovery source. */
class AgentDecisionTest {
    @TempDir Path root;

    @Test void approvedScriptedDecisionIsVisibleAndCompletesOneAction() {
        var output = new ArrayList<String>();
        var provider = new ScriptedAgentDecisionProvider(ScriptedAgentDecisionProvider.Scenario.APPROVED);
        try (var app = new WorkflowEngine(root, Clock.systemUTC(), Faults.NONE, output::add, provider)) {
            app.start("wf-agent"); app.pump();
            assertEquals("COMPLETED", app.workflow("wf-agent").text("status"));
            assertEquals(1, app.gateway.count());
            assertEquals(1, app.agentDecisionCalls);
            assertTrue(output.stream().anyMatch(s -> s.contains("decisionSource=SCRIPTED")));
            assertTrue(output.stream().anyMatch(s -> s.contains("proposed: beneficiary=demo-beneficiary-001")));
        }
    }

    @Test void unsafeScriptedDecisionPassesContractButFailsApplicationPolicy() {
        var output = new ArrayList<String>();
        var provider = new ScriptedAgentDecisionProvider(ScriptedAgentDecisionProvider.Scenario.UNAUTHORIZED_BENEFICIARY);
        try (var app = new WorkflowEngine(root, Clock.systemUTC(), Faults.NONE, output::add, provider)) {
            app.start("wf-agent"); app.pump();
            assertEquals("REJECTED", app.workflow("wf-agent").text("status"));
            assertEquals(0, app.failures("VALIDATION"));
            assertEquals(1, app.failures("POLICY"));
            assertEquals(0, app.gateway.count());
            assertNull(app.intent("wf-agent"));
            assertEquals("demo-beneficiary-001", Json.read(app.workflow("wf-agent").text("approved_json")).path("beneficiaryRef").asText());
            assertTrue(output.stream().anyMatch(s -> s.contains("proposed: beneficiary=demo-beneficiary-999")));
        }
    }

    @Test void providerCannotChangeTrustedRequestByMutatingItsInput() {
        AgentDecisionProvider mutating = new AgentDecisionProvider() {
            @Override public String source() { return "UNTRUSTED"; }
            @Override public JsonNode propose(JsonNode request) {
                ((ObjectNode) request).put("beneficiaryRef", "demo-beneficiary-999");
                return request;
            }
        };
        try (var app = new WorkflowEngine(root, Clock.systemUTC(), Faults.NONE, ignored -> { }, mutating)) {
            app.start("wf-agent"); app.pump();
            assertEquals("demo-beneficiary-001", Json.read(app.workflow("wf-agent").text("approved_json")).path("beneficiaryRef").asText());
            assertEquals("REJECTED", app.workflow("wf-agent").text("status"));
            assertEquals(1, app.failures("POLICY"));
            assertEquals(0, app.gateway.count());
        }
    }

    @Test void malformedProviderProposalCannotPublishAnExecutablePlan() {
        var malformed = (ObjectNode) Json.normalInstruction(); malformed.remove("amountMinor");
        var provider = new ScriptedAgentDecisionProvider(malformed);
        try (var app = new WorkflowEngine(root, Clock.systemUTC(), Faults.NONE, ignored -> { }, provider)) {
            app.start("wf-agent"); app.pump();
            assertEquals(1, app.failures("VALIDATION"));
            assertEquals(0, app.failures("POLICY"));
            assertEquals(0, app.db.one("SELECT COUNT(*) AS n FROM event_queue").number("n"));
            assertEquals(0, app.gateway.count());
            assertNull(app.intent("wf-agent"));
        }
    }

    @Test void savedPlanRecoversEvenWhenDecisionProviderIsUnavailableAfterRestart() {
        Faults stop = (point, workflow) -> {
            if (point == Faults.Point.AFTER_GATEWAY_COMMIT) throw new Problems.Interrupted("PROCESS_STOPPED");
        };
        try (var app = new WorkflowEngine(root, Clock.systemUTC(), stop, ignored -> { })) {
            app.start("wf-agent");
            assertThrows(Problems.Interrupted.class, app::pump);
            assertEquals(1, app.gateway.count());
            assertNull(app.intent("wf-agent").text("receipt_json"));
        }
        AgentDecisionProvider unavailable = new AgentDecisionProvider() {
            @Override public String source() { throw new AssertionError("Recovery must not consult the decision provider"); }
            @Override public JsonNode propose(JsonNode request) { throw new AssertionError("Recovery must not replan"); }
        };
        try (var app = new WorkflowEngine(root, Clock.systemUTC(), Faults.NONE, ignored -> { }, unavailable)) {
            app.start("wf-agent"); app.pump();
            assertEquals("COMPLETED", app.workflow("wf-agent").text("status"));
            assertEquals(1, app.workflow("wf-agent").number("planner_runs"));
            assertEquals(0, app.agentDecisionCalls);
            assertEquals(0, app.gateway.submissionCalls.get());
            assertEquals(1, app.gateway.count());
        }
    }
}
