package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Release-JAR tests launch independent real JVMs and inspect durable evidence after shutdown. */
class AgenticDemoIT {
    @TempDir Path root;
    com.fasterxml.jackson.databind.JsonNode run(String fault, String fixture) throws Exception {
        var data = root.resolve("run"); var log = root.resolve("console.log");
        var command = List.of(AgenticDemo.javaCommand(), "-Xmx192m", "-jar", Path.of(System.getProperty("demo.jar")).toAbsolutePath().toString(), "agentic", "--mode", "scripted", "--fault", fault, "--case", fixture, "--data-dir", data.toString());
        var p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(75, TimeUnit.SECONDS)) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); fail("Timed out; " + Files.readString(log)); }
        assertEquals(0, p.exitValue(), Files.readString(log));
        var summary = Json.read(Files.readString(data.resolve("run-summary.json")));
        assertTrue(summary.path("invariantsPassed").asBoolean(), summary.toString());
        assertFalse(summary.path("liveAgentRetryProven").asBoolean());
        assertEquals("SCRIPTED_REHEARSAL_ONLY", summary.path("modelEvidence").asText());
        assertTrue(Json.read(Files.readString(data.resolve("workflow-ready.json"))).path("pid").asLong() > 0);
        return summary;
    }
    @Test void normalToolLoopVerifiesOneInstruction() throws Exception { assertEquals(1, run("none", "approved").path("provider").path("instructions").asInt()); }
    @Test void completionGapProducesFreshScriptedCallAndRedeliversOriginalEvent() throws Exception {
        var n = run("completion", "approved"); assertEquals("SCRIPTED_REPROPOSAL", n.path("observedBehavior").asText());
        assertEquals(1, n.path("freshPaymentProposalsAfterUnknown").asInt()); assertEquals(1, n.path("provider").path("submissionCalls").asInt());
        assertTrue(n.path("maxCompletionDeliveryCount").asInt() >= 2);
    }
    @Test void onlyWorkflowWorkerCrashesAndRecoversProviderReceipt() throws Exception {
        var n = run("worker", "approved"); assertEquals(1, n.path("workerRestarts").asInt());
        assertEquals(2, n.path("workflow").path("executionAttempts").asInt()); assertEquals(1, n.path("provider").path("submissionCalls").asInt());
    }
    @Test void holdStopsModelFixtureWithNoProviderInstruction() throws Exception { assertEquals(0, run("none", "hold").path("provider").path("instructions").asInt()); }
    @Test void missingGeminiCredentialsFailWithoutStartingScriptedFixture() throws Exception {
        var log = root.resolve("missing.log");
        var b = new ProcessBuilder(AgenticDemo.javaCommand(), "-jar", Path.of(System.getProperty("demo.jar")).toAbsolutePath().toString(), "agentic", "preflight", "--mode", "gemini").redirectErrorStream(true).redirectOutput(log.toFile());
        b.environment().remove("GEMINI_API_KEY"); b.environment().remove("GOOGLE_API_KEY");
        var p = b.start(); assertTrue(p.waitFor(20, TimeUnit.SECONDS)); assertEquals(2, p.exitValue());
        assertTrue(Files.readString(log).contains("no scripted fallback"));
    }
    @Test void checkpointStartupFailureStopsBeforeAModelTurnAndReportsTheChildCause() throws Exception {
        Path data = root.resolve("blocked"); Path log = root.resolve("blocked.log");
        Files.createDirectories(data.resolve("workflow-status.json"));
        var model = new ScriptedPlannerModel();
        AgenticHttp.save(data.resolve("run-config.json"), java.util.Map.of("mode", "scripted", "model", model.model(),
            "integration", model.integration(), "fault", "none", "case", "approved"));
        var process = new ProcessBuilder(AgenticDemo.javaCommand(), "-Ddemo.debug=true", "-jar",
            Path.of(System.getProperty("demo.jar")).toAbsolutePath().toString(), "agentic", "--mode", "scripted",
            "--fault", "none", "--data-dir", data.toString(), "--resume")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(40, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); fail("Startup failure was not reported promptly");
        }
        String console = Files.readString(log); assertEquals(2, process.exitValue(), console);
        assertFalse(console.contains("MODEL_REQUEST_STARTED"), console);
        assertTrue(console.contains("Cannot save run checkpoint"), console);
        assertTrue(console.contains("workflow-status.json"), console);
        String child = Files.readString(data.resolve("workflow.log"));
        assertTrue(child.contains("Caused by:"), child);
        assertTrue(child.contains("CheckpointFiles.write"), child);
        assertFalse(Files.exists(data.resolve("workflow-ready.json")));
    }
}
