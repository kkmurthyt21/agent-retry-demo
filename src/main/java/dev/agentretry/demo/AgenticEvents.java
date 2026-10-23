package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.UUID;

/** Application-owned identity and versioned domain event boundary. */
final class AgenticEvents {
    static final String TENANT = "demo-tenant", INVOICE = "INV-1042";
    static final String OPERATION = TENANT + ":" + INVOICE + ":approval-v1:submit-payment";
    static final Set<String> REQUESTS = Set.of("ContextRequested", "StatusRequested", "ActionProposed");
    static ObjectNode event(String type, String call) {
        var n = Json.MAPPER.createObjectNode();
        n.put("contract", "dev.agentretry.agentic").put("schemaVersion", "1.0")
            .put("eventId", UUID.randomUUID().toString()).put("type", type)
            .put("tenant", TENANT).put("invoiceId", INVOICE).put("operationId", OPERATION)
            .put("intentVersion", 1).put("expectedStateVersion", 0).put("toolCallId", call);
        return n;
    }
    static void validate(JsonNode n) {
        if (!n.isObject() || !"dev.agentretry.agentic".equals(n.path("contract").asText())
                || !Set.of("1.0", "1.1").contains(n.path("schemaVersion").asText()))
            throw new Problems.Contract("UNSUPPORTED_CONTRACT");
        for (String k : new String[]{"eventId", "type", "tenant", "invoiceId", "operationId", "toolCallId"})
            if (!n.path(k).isTextual() || n.path(k).asText().isBlank() || n.path(k).asText().length() > 255)
                throw new Problems.Contract("INVALID_HEADER_" + k);
        if (!TENANT.equals(n.path("tenant").asText()) || !INVOICE.equals(n.path("invoiceId").asText())
                || !OPERATION.equals(n.path("operationId").asText())) throw new Problems.Policy("SCOPE_MISMATCH");
        if (!n.path("intentVersion").isIntegralNumber() || n.path("intentVersion").asInt() != 1)
            throw new Problems.Contract("INTENT_VERSION_MISMATCH");
        if (!n.path("expectedStateVersion").isIntegralNumber()) throw new Problems.Contract("INVALID_STATE_VERSION");
        if (!Set.of("ContextRequested", "StatusRequested", "ActionProposed", "TaskPlanned", "ActionCompleted", "ToolResultReady", "WorkflowVerified").contains(n.path("type").asText()))
            throw new Problems.Contract("UNKNOWN_EVENT_TYPE");
        if (REQUESTS.contains(n.path("type").asText()) && n.path("expectedStateVersion").asInt() != 0)
            throw new Problems.Contract("INVALID_REQUEST_PRECONDITION");
    }
    static boolean validTool(String name, JsonNode args) {
        return Set.of("get_invoice_context", "get_payment_status", "submit_payment").contains(name)
            && args.isObject() && args.size() == 1 && args.path("invoiceId").isTextual()
            && INVOICE.equals(args.path("invoiceId").asText());
    }
    static ObjectNode facts(boolean hold) {
        return Json.MAPPER.createObjectNode().put("invoiceId", INVOICE).put("amountMinor", 25000)
            .put("currency", "USD").put("beneficiaryRef", "demo-beneficiary-001")
            .put("approvalVersion", 1).put("approved", !hold).put("onHold", hold);
    }
}
