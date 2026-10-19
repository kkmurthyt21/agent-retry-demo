package dev.agentretry.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/** Four stage commands, plus offstage fixture reset and preflight. */
public final class Main {
    public static final int PLANNED_CRASH_EXIT = 23;
    private static final String MARKER = ".devfest-demo-fixture";
    private Main() { }
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) {
            System.err.println("DEMO_ERROR: " + e.getMessage());
            if (Boolean.getBoolean("demo.debug")) e.printStackTrace();
            System.exit(2);
        }
    }
    static void run(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0])) { help(); return; }
        if (args.length != 2 && args.length != 4) throw new IllegalArgumentException("Use demo <scenario> [--data-dir <directory>]");
        if (!"demo".equals(args[0])) throw new IllegalArgumentException("First argument must be demo");
        if (args.length == 4 && !"--data-dir".equals(args[2])) throw new IllegalArgumentException("Unknown option");
        Path root = (args.length == 4 ? Path.of(args[3]) : Path.of("data", "devfest-demo")).toAbsolutePath().normalize();
        String scenario = args[1];
        if (!java.util.Set.of("reset", "preflight", "normal", "boundaries", "crash", "recover").contains(scenario))
            throw new IllegalArgumentException("Unknown scenario " + scenario);
        prepare(root);
        if ("reset".equals(scenario)) { reset(root); return; }
        var holder = new WorkflowEngine[1];
        Faults faults = (point, workflow) -> {
            if ("crash".equals(scenario) && point == Faults.Point.AFTER_GATEWAY_COMMIT && "wf-crash".equals(workflow)) {
                var app = holder[0];
                System.out.println("[CRASH_CHECKPOINT] workflow=wf-crash toolCommitted=true localAcknowledged=false");
                System.out.println("  savedState=" + app.workflow(workflow).text("status") + " localReceiptSaved=" + (app.intent(workflow).text("receipt_json") != null));
                System.out.println("  instructionsForWorkflow=" + app.gateway.count(Planner.actionKey(workflow)) + " totalInstructions=" + app.gateway.count());
                System.out.println("[INTENTIONAL_HALT] exitCode=23 (planned demo fault)");
                System.out.flush(); System.err.flush();
                Runtime.getRuntime().halt(PLANNED_CRASH_EXIT);
            }
        };
        try (var app = new WorkflowEngine(root, Clock.systemUTC(), faults, System.out::println)) {
            holder[0] = app;
            switch (scenario) {
                case "preflight" -> {
                    System.out.println("PREFLIGHT java=" + System.getProperty("java.version") + " schemas=1.0,1.1 H2_WRITE_DELAY=0");
                    System.out.println("  dataDirectory=" + root);
                    System.out.println("  totalInstructions=" + app.gateway.count());
                    System.out.println("  offlineRuntime=true singleDispatcherLock=ACQUIRED");
                }
                case "normal" -> { app.start("wf-normal"); app.pump(); summary(app, "wf-normal"); }
                case "boundaries" -> boundaries(app);
                case "crash" -> {
                    if (app.workflow("wf-crash") != null) throw new IllegalStateException("wf-crash already exists. Reset the demo fixtures offstage before rehearsing again.");
                    app.start("wf-crash"); app.pump();
                    throw new IllegalStateException("The expected crash checkpoint was not reached");
                }
                case "recover" -> {
                    var saved = app.workflow("wf-crash");
                    if (saved == null) throw new IllegalStateException("Run demo crash before demo recover");
                    int before = app.gateway.count();
                    System.out.println("[RESTART] savedState=" + saved.text("status") + " savedStateVersion=" + saved.number("state_version")
                            + " plannerRuns=" + saved.number("planner_runs") + " localReceiptSaved=" + (app.intent("wf-crash").text("receipt_json") != null));
                    System.out.println("  originalActionKey=" + app.intent("wf-crash").text("action_key"));
                    app.pump(); summary(app, "wf-crash");
                    System.out.println("  additionalInstructionsDuringRecovery=" + (app.gateway.count() - before));
                    System.out.println("  gatewaySubmissionCallsThisRun=" + app.gateway.submissionCalls.get());
                }
                default -> throw new IllegalStateException("Unexpected scenario");
            }
        }
    }
    private static void boundaries(WorkflowEngine app) {
        var normal = app.workflow("wf-normal");
        if (normal == null || !"COMPLETED".equals(normal.text("status"))) throw new IllegalStateException("Run demo normal before demo boundaries");
        var event = app.originalPlanEvent("wf-normal");
        app.enqueue(event); app.pump();
        var fresh = event.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) fresh).put("eventId", "evt-wf-normal-fresh-envelope");
        app.enqueue(fresh); app.pump();
        app.plan("wf-invalid", Json.normalInstruction(), Json.normalInstruction(), "2.0");
        app.plan("wf-policy", Json.normalInstruction(), Json.instruction("demo-beneficiary-999", 25000), "1.0");
        app.pump();
        System.out.println("[BOUNDARIES] validationFailures=" + app.failures("VALIDATION") + " policyFailures=" + app.failures("POLICY")
                + " totalInstructions=" + app.gateway.count());
    }
    private static void summary(WorkflowEngine app, String workflow) {
        var row = app.workflow(workflow);
        System.out.println("[RESULT] workflow=" + workflow + " state=" + row.text("status") + " stateVersion=" + row.number("state_version"));
        System.out.println("  savedPlan=" + (row.text("plan_json") != null) + " plannerRuns=" + row.number("planner_runs"));
        System.out.println("  instructionsForWorkflow=" + app.gateway.count(Planner.actionKey(workflow)) + " totalInstructions=" + app.gateway.count());
    }
    private static void prepare(Path root) throws java.io.IOException {
        Files.createDirectories(root);
        if (!Files.exists(root.resolve(MARKER))) {
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) throw new IllegalStateException("Use an empty demo directory; this directory has no fixture marker");
            }
            Files.writeString(root.resolve(MARKER), "DevFest Troy synthetic payment-instruction fixtures only\n");
        }
    }
    private static void reset(Path root) throws java.io.IOException {
        try (var ignored = new RunLock(root)) {
            // No recursive deletion, wildcards, or removal of arbitrary directory contents.
            for (String name : new String[]{"workflow.mv.db", "workflow.trace.db", "payment-gateway.mv.db", "payment-gateway.trace.db"})
                Files.deleteIfExists(root.resolve(name));
            System.out.println("RESET synthetic demo databases cleared; unrelated files preserved");
        }
    }
    private static void help() {
        System.out.println("When AI Agents Retry — offline Java demonstration");
        System.out.println("Offstage: java -jar agent-retry-demo.jar demo reset");
        System.out.println("Offstage: java -jar agent-retry-demo.jar demo preflight");
        for (String scenario : new String[]{"normal", "boundaries", "crash", "recover"})
            System.out.println("java -jar agent-retry-demo.jar demo " + scenario);
        System.out.println("crash deliberately exits 23; the other prepared commands exit 0.");
    }
}
