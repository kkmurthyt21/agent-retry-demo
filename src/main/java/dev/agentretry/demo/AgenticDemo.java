package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Launcher for the agent plus independent broker, domain worker and mock-provider JVMs. */
final class AgenticDemo {
    static final String BUILD = "1.1.0-candidate-build4-checkpoints";
    static Map<String,String> options(String[] args) {
        var options = new HashMap<String,String>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (Set.of("--resume", "--check-model").contains(key)) options.put(key, "true");
            else if (Set.of("--mode", "--fault", "--case", "--data-dir", "--wait-ms", "--request").contains(key) && i + 1 < args.length) options.put(key, args[++i]);
            else throw new IllegalArgumentException("Unknown/missing option: " + key);
        }
        return options;
    }
    static void run(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            System.out.println("agentic [preflight] --mode gemini|scripted [--check-model] [--fault none|completion|worker] [--case approved|hold] [--data-dir fresh-directory] [--resume] [--wait-ms 3000]"); return;
        }
        boolean preflight = "preflight".equals(args[0]);
        var opt = options(preflight ? Arrays.copyOfRange(args, 1, args.length) : args);
        String mode = opt.getOrDefault("--mode", "gemini"), fault = opt.getOrDefault("--fault", "none"), fixture = opt.getOrDefault("--case", "approved");
        if (!Set.of("gemini", "scripted").contains(mode) || !Set.of("none", "completion", "worker").contains(fault) || !Set.of("approved", "hold").contains(fixture)) throw new IllegalArgumentException("Invalid mode, fault or case");
        PlannerModel model = "gemini".equals(mode) ? GeminiPlannerModel.fromEnvironment() : new ScriptedPlannerModel();
        System.out.println("[PREFLIGHT] build=" + BUILD + " java=" + System.getProperty("java.version") + " source=" + model.source() + " integration=" + model.integration() + " model=" + model.model());
        if (preflight) {
            if (opt.containsKey("--check-model")) {
                var history = Json.MAPPER.createArrayNode().add(Json.MAPPER.createObjectNode().put("role", "user").set("parts", Json.MAPPER.createArrayNode().add(Json.MAPPER.createObjectNode().put("text", "Connectivity check only. Do not call tools. Reply READY."))));
                var r = model.generate(history); System.out.println("[MODEL_CONNECTED] text=" + r.text() + " toolCalls=" + r.calls().size());
            }
            return;
        }
        long waitMs = Long.parseLong(opt.getOrDefault("--wait-ms", "3000"));
        if (waitMs < 2000 || waitMs > 60000) throw new IllegalArgumentException("wait-ms must be 2000..60000");
        Path root = Path.of(opt.getOrDefault("--data-dir", "runs/agentic-" + java.util.UUID.randomUUID())).toAbsolutePath().normalize();
        Files.createDirectories(root); Path marker = root.resolve("run-config.json");
        var config = Map.of("mode", mode, "model", model.model(), "integration", model.integration(), "fault", fault, "case", fixture);
        if (opt.containsKey("--resume")) {
            if (!Files.isRegularFile(marker) || !Json.read(Files.readString(marker)).equals(Json.MAPPER.valueToTree(config))) throw new IllegalArgumentException("Resume requires the same mode/model/fault/case and an existing run directory");
        } else {
            try (var entries = Files.list(root)) { if (entries.findAny().isPresent()) throw new IllegalArgumentException("Use a fresh data directory or --resume"); }
            AgenticHttp.save(marker, config);
        }
        var services = new Services(root, fixture, fault);
        try (var lock = new RunLock(root)) {
            services.startAll();
            try (var agent = new AgenticPlanner(root, model, services.brokerPort, opt.getOrDefault("--request", "Submit the approved payment request for invoice INV-1042, after checking invoice policy and current payment status."), waitMs, "completion".equals(fault), services::supervise)) {
                agent.run();
                var provider = AgenticHttp.post(services.providerPort, "/stats", AgenticHttp.empty());
                var broker = AgenticHttp.post(services.brokerPort, "/stats", AgenticHttp.empty());
                var workflow = Json.read(Files.readString(root.resolve("workflow-status.json")));
                var summary = agent.summary(); summary.set("provider", provider); summary.set("broker", broker); summary.set("workflow", workflow);
                summary.put("workerRestarts", services.restarts).put("javaVersion", System.getProperty("java.version")).put("build", BUILD);
                boolean hold = "hold".equals(fixture);
                boolean pass = hold ? provider.path("instructions").asInt() == 0 && summary.path("submitProposals").asInt() == 0
                    : provider.path("instructions").asInt() == 1 && "VERIFIED".equals(workflow.path("state").asText()) && summary.path("verifiedResultReturnedToModel").asBoolean();
                if ("completion".equals(fault) && !hold) pass &= summary.path("completionFaultTriggered").asBoolean() && summary.path("maxCompletionDeliveryCount").asInt() >= 2;
                if ("worker".equals(fault) && !hold) pass &= services.restarts == 1;
                summary.put("invariantsPassed", pass).put("liveAgentRetryProven", pass && "GEMINI".equals(model.source()) && summary.path("freshPaymentProposalsAfterUnknown").asInt() > 0);
                summary.put("modelEvidence", "GEMINI".equals(model.source()) ? "LIVE_API_RUN" : "SCRIPTED_REHEARSAL_ONLY");
                AgenticHttp.save(root.resolve("run-summary.json"), summary);
                System.out.println("[RUN_SUMMARY] " + Json.write(summary)); System.out.println("[EVIDENCE] " + root);
                if (!pass) throw new IllegalStateException("RUN_INVARIANTS_FAILED; inspect run-summary.json and role logs");
            }
        } finally { services.close(); }
    }
    static final class Services implements AutoCloseable {
        final Path root;
        final String fixture, fault;
        final List<Process> children = new ArrayList<>();
        Process broker, provider, worker;
        int brokerPort, providerPort, restarts;
        Services(Path root, String fixture, String fault) { this.root = root; this.fixture = fixture; this.fault = fault; }
        Process start(String role) throws Exception {
            var args = new ArrayList<String>(List.of(javaCommand(), "-Xmx128m", "-cp", System.getProperty("java.class.path"), Main.class.getName(), "agentic-service", role, root.toString(), Integer.toString(brokerPort), Integer.toString(providerPort), fixture, fault));
            if (Boolean.getBoolean("demo.debug")) args.add(2, "-Ddemo.debug=true");
            var builder = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve(role + ".log").toFile()));
            for (String key : List.of("GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_APPLICATION_CREDENTIALS")) builder.environment().remove(key);
            Process p = builder.start(); children.add(p);
            long until = System.currentTimeMillis() + 20000; Path ready = root.resolve(role + "-ready.json");
            boolean started = false;
            while (System.currentTimeMillis() < until) {
                if (!p.isAlive()) throw serviceExited(role, p);
                if (Files.exists(ready)) {
                    var n = Json.read(Files.readString(ready));
                    if (n.path("pid").asLong() == p.pid()) {
                        if ("broker".equals(role)) brokerPort = n.path("port").asInt();
                        else if ("provider".equals(role)) providerPort = n.path("port").asInt();
                        started = true;
                        break;
                    }
                }
                Thread.sleep(40);
            }
            if (!started) throw new IllegalStateException(role + " startup timed out; inspect " + root.resolve(role + ".log"));
            System.out.println("[PROCESS] role=" + role + " pid=" + p.pid()); return p;
        }
        void startAll() throws Exception { broker = start("broker"); provider = start("provider"); worker = start("workflow"); }
        IllegalStateException serviceExited(String role, Process process) {
            Path log = root.resolve(role + ".log");
            String detail = "";
            try {
                var lines = Files.readAllLines(log);
                detail = String.join(System.lineSeparator(), lines.subList(Math.max(0, lines.size() - 12), lines.size()));
                String primary = lines.stream().filter(line -> line.startsWith("DEMO_ERROR:")).reduce((first, last) -> last).orElse("");
                if (!primary.isBlank() && !detail.contains(primary)) detail = primary + System.lineSeparator() + detail;
            } catch (java.io.IOException ignored) { /* The log path is still supplied below. */ }
            return new IllegalStateException(("workflow".equals(role) ? "Workflow worker" : role)
                + " exited: " + process.exitValue() + "; inspect " + log
                + (detail.isBlank() ? "" : System.lineSeparator() + detail));
        }
        void supervise() {
            if (!broker.isAlive()) throw serviceExited("broker", broker);
            if (!provider.isAlive()) throw serviceExited("provider", provider);
            if (!worker.isAlive()) {
                if (worker.exitValue() == 23 && "worker".equals(fault) && restarts == 0) {
                    restarts++; System.out.println("[RESTART] workflow worker only; broker/provider remain running");
                    try { worker = start("workflow"); } catch (Exception e) { throw new IllegalStateException(e); }
                } else throw serviceExited("workflow", worker);
            }
        }
        public void close() {
            for (var p : children) if (p.isAlive()) p.destroy();
            for (var p : children) try { if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    static String javaCommand() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString(); }
    static void service(String[] args) throws Exception {
        String role = args[1]; Path root = Path.of(args[2]);
        if ("broker".equals(role)) {
            var broker = new AgenticBroker(root); var server = AgenticHttp.serve(broker::handle);
            AgenticHttp.save(root.resolve("broker-ready.json"), Map.of("port", server.getAddress().getPort(), "pid", ProcessHandle.current().pid()));
            new java.util.concurrent.CountDownLatch(1).await();
        } else if ("provider".equals(role)) {
            var provider = new AgenticGateway(root); var server = AgenticHttp.serve(provider::handle);
            AgenticHttp.save(root.resolve("provider-ready.json"), Map.of("port", server.getAddress().getPort(), "pid", ProcessHandle.current().pid()));
            new java.util.concurrent.CountDownLatch(1).await();
        } else if ("workflow".equals(role)) {
            try (var lock = new RunLock(root.resolve("worker-lock")); var workflow = new AgenticWorkflow(root, Integer.parseInt(args[3]), AgenticGateway.client(Integer.parseInt(args[4])), "hold".equals(args[5]), "worker".equals(args[6]))) {
                workflow.snapshot();
                AgenticHttp.save(root.resolve("workflow-ready.json"), Map.of("pid", ProcessHandle.current().pid()));
                while (true) { workflow.tick(); Thread.sleep(40); }
            }
        } else throw new IllegalArgumentException("Unknown service role");
    }
}
