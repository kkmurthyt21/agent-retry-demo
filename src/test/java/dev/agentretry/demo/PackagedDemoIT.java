package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the actual shaded release JAR in separate JVMs, including a hard halt. */
class PackagedDemoIT {
    @TempDir Path root;
    record Result(int exit, String output) { }
    String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    Result process(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Child JVM timed out: " + command); }
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
    void jar() throws Exception { Files.copy(Path.of(System.getProperty("demo.jar")), root.resolve("agent-retry-demo.jar")); }
    Result run(boolean offline, String scenario) throws Exception {
        var command = new ArrayList<String>(); command.add(java());
        if (offline) {
            command.add("-Djava.security.manager");
            command.add("-Djava.security.policy==" + root.resolve("offline.policy").toUri());
        }
        command.addAll(List.of("-jar", "agent-retry-demo.jar", "demo", scenario));
        return process(command);
    }
    @Test void hardProcessCrashLeavesIndependentGatewayCommitAndNewJvmRecovers() throws Exception {
        jar();
        assertEquals(0, run(false, "reset").exit());
        var normal = run(false, "normal"); assertEquals(0, normal.exit(), normal.output());
        assertTrue(normal.output().contains("state=COMPLETED")); assertTrue(normal.output().contains("totalInstructions=1"));
        assertTrue(normal.output().contains("decisionSource=SCRIPTED"), normal.output());
        assertTrue(normal.output().contains("proposed: beneficiary=demo-beneficiary-001"), normal.output());
        var boundaries = run(false, "boundaries"); assertEquals(0, boundaries.exit(), boundaries.output());
        for (String expected : List.of("DUPLICATE_EVENT", "REUSED_ACTION", "VALIDATION_REJECTED", "POLICY_REJECTED", "totalInstructions=1"))
            assertTrue(boundaries.output().contains(expected), boundaries.output());
        assertTrue(boundaries.output().contains("proposed: beneficiary=demo-beneficiary-999"), boundaries.output());
        var crash = run(false, "crash"); assertEquals(Main.PLANNED_CRASH_EXIT, crash.exit(), crash.output());
        assertTrue(crash.output().contains("toolCommitted=true localAcknowledged=false"));
        var data = root.resolve("data/devfest-demo");
        // Inspect the committed files before any recovery dispatcher can modify them.
        try (var workflow = new Database(data, "workflow"); var gateway = new Database(data, "payment-gateway")) {
            assertEquals("EXECUTING", workflow.one("SELECT status FROM workflows WHERE workflow_id='wf-crash'").text("status"));
            assertNull(workflow.one("SELECT receipt_json FROM action_intents WHERE workflow_id='wf-crash'").text("receipt_json"));
            assertEquals("IN_FLIGHT", workflow.one("SELECT status FROM event_queue WHERE workflow_id='wf-crash'").text("status"));
            assertEquals("IN_PROGRESS", workflow.one("SELECT status FROM processed_events WHERE event_id='evt-wf-crash-task-planned'").text("status"));
            assertEquals(1, workflow.one("SELECT processing_attempts FROM stage_budgets WHERE workflow_id='wf-crash'").number("processing_attempts"));
            assertEquals(2, gateway.one("SELECT COUNT(*) AS n FROM instructions").number("n"));
            assertEquals(2, gateway.one("SELECT COUNT(*) AS n FROM gateway_operations").number("n"));
        }
        var recovered = run(false, "recover"); assertEquals(0, recovered.exit(), recovered.output());
        for (String expected : List.of("savedState=EXECUTING", "RECOVERED_RECEIPT", "state=COMPLETED", "plannerRuns=1",
                "instructionsForWorkflow=1 totalInstructions=2", "additionalInstructionsDuringRecovery=0", "gatewaySubmissionCallsThisRun=0",
                "agentDecisionCallsThisRun=0"))
            assertTrue(recovered.output().contains(expected), recovered.output());
        assertFalse(recovered.output().contains("[AGENT_DECISION]"), recovered.output());
        var restartedAgain = run(false, "preflight"); assertEquals(0, restartedAgain.exit(), restartedAgain.output());
        assertTrue(restartedAgain.output().contains("totalInstructions=2"));
    }
    @Test void packagedFourCommandsWorkWhileSocketAccessIsDenied() throws Exception {
        jar();
        try (var in = getClass().getResourceAsStream("/offline.policy")) { Files.copy(in, root.resolve("offline.policy")); }
        var probe = process(List.of(java(), "-Djava.security.manager", "-Djava.security.policy==" + root.resolve("offline.policy").toUri(),
                "-cp", System.getProperty("java.class.path"), NetworkProbe.class.getName()));
        assertEquals(0, probe.exit(), probe.output()); assertTrue(probe.output().contains("NETWORK_ACCESS_DENIED"), probe.output());
        assertEquals(0, run(true, "reset").exit());
        var normal = run(true, "normal"); assertEquals(0, normal.exit(), normal.output());
        var boundaries = run(true, "boundaries"); assertEquals(0, boundaries.exit(), boundaries.output());
        var crash = run(true, "crash"); assertEquals(23, crash.exit(), crash.output());
        var recovered = run(true, "recover"); assertEquals(0, recovered.exit(), recovered.output());
        assertTrue(recovered.output().contains("additionalInstructionsDuringRecovery=0"));
        assertTrue(recovered.output().contains("gatewaySubmissionCallsThisRun=0"));
        assertTrue(normal.output().contains("decisionSource=SCRIPTED"));
        assertTrue(boundaries.output().contains("proposed: beneficiary=demo-beneficiary-999"));
        assertTrue(recovered.output().contains("agentDecisionCallsThisRun=0"));
    }
    @Test void resetRefusesUnmarkedDirectoryAndPreservesUnrelatedFiles() throws Exception {
        jar(); var data = root.resolve("data/devfest-demo"); Files.createDirectories(data);
        Files.writeString(data.resolve("keep.txt"), "unrelated");
        var refused = run(false, "reset"); assertEquals(2, refused.exit(), refused.output());
        assertEquals("unrelated", Files.readString(data.resolve("keep.txt")));
        Files.writeString(data.resolve(".devfest-demo-fixture"), "test fixture\n");
        Files.writeString(data.resolve("workflow.mv.db"), "synthetic placeholder");
        var reset = run(false, "reset"); assertEquals(0, reset.exit(), reset.output());
        assertFalse(Files.exists(data.resolve("workflow.mv.db")));
        assertEquals("unrelated", Files.readString(data.resolve("keep.txt")));
    }
}
