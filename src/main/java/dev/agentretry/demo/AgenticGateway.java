package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/** Separate mock-provider process; its database never joins a workflow transaction. */
final class AgenticGateway implements AutoCloseable {
    final MockPaymentGateway gateway;
    AgenticGateway(Path root) { gateway = new MockPaymentGateway(root, Clock.systemUTC(), Faults.NONE); }
    Object handle(String path, JsonNode n) {
        return switch (path) {
            case "/health" -> Map.of("role", "provider", "pid", ProcessHandle.current().pid());
            case "/stats" -> Map.of("instructions", gateway.count(), "submissionCalls", gateway.submissionCalls.get(), "lookupCalls", gateway.lookupCalls.get());
            case "/submit" -> gateway.submit(n.path("tenant").asText(), n.path("key").asText(), n.path("intent"));
            case "/lookup" -> { var r = gateway.find(n.path("tenant").asText(), n.path("key").asText()); yield r == null ? AgenticHttp.empty() : r; }
            case "/instruction" -> { var r = gateway.readInstruction(n.path("id").asText()); yield r == null ? AgenticHttp.empty() : r; }
            default -> throw new IllegalArgumentException("Unknown provider route");
        };
    }
    static PaymentGateway client(int port) { return new PaymentGateway() {
        private com.fasterxml.jackson.databind.node.ObjectNode identity(String tenant, String key) { return Json.MAPPER.createObjectNode().put("tenant", tenant).put("key", key); }
        public Submission submit(String tenant, String key, JsonNode intent) {
            var request = identity(tenant, key); request.set("intent", intent);
            var n = AgenticHttp.post(port, "/submit", request);
            return new Submission(receipt(n.path("receipt")), n.path("created").asBoolean());
        }
        public Receipt find(String tenant, String key) { var n = AgenticHttp.post(port, "/lookup", identity(tenant, key)); return n.isEmpty() ? null : receipt(n); }
        public JsonNode readInstruction(String id) { var n = AgenticHttp.post(port, "/instruction", Json.MAPPER.createObjectNode().put("id", id)); return n.isEmpty() ? null : n; }
    }; }
    static PaymentGateway.Receipt receipt(JsonNode n) { return new PaymentGateway.Receipt(n.path("tenant").asText(), n.path("actionKey").asText(), n.path("fingerprint").asText(), n.path("instructionId").asText(), n.path("acceptedAt").asLong()); }
    public void close() { gateway.close(); }
}
