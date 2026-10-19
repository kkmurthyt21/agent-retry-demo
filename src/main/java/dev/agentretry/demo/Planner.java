package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Deterministic mocked decisions. The application must authorize every proposal. */
final class Planner {
    static final String TENANT = "demo-tenant";
    static String actionKey(String workflow) { return TENANT + ":" + workflow + ":submit-payment-instruction"; }
    ObjectNode propose(String workflow, JsonNode instruction, String version) {
        return event(workflow, "planner", "TaskPlanned", 1,
                "evt-" + workflow + "-task-planned", "evt-" + workflow + "-requested", instruction, version);
    }
    static ObjectNode event(String workflow, String source, String type, int stateVersion,
                            String eventId, String cause, JsonNode data, String version) {
        var n = Json.MAPPER.createObjectNode();
        n.put("eventId", eventId); n.put("source", source); n.put("eventType", type);
        n.put("schemaVersion", version); n.put("workflowId", workflow);
        n.put("expectedStateVersion", stateVersion); n.put("correlationId", workflow);
        n.put("causationId", cause); n.put("idempotencyKey", actionKey(workflow));
        n.set("data", data.deepCopy());
        return n;
    }
}
