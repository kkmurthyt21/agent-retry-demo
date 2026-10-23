package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgenticProtocolTest {
    @TempDir Path root;
    @Test void requestsNeedSupportedContractAndIntegralApprovedVersion() {
        var e = AgenticEvents.event("ActionProposed", "call"); AgenticEvents.validate(e);
        e.put("schemaVersion", "1.1").put("traceHint", "additive"); AgenticEvents.validate(e);
        e.put("schemaVersion", "2.0"); assertThrows(Problems.Contract.class, () -> AgenticEvents.validate(e));
        e.put("schemaVersion", "1.0").put("intentVersion", 1.5); assertThrows(Problems.Contract.class, () -> AgenticEvents.validate(e));
    }
    @Test void modelCannotSupplyMoneyFieldsOrAnotherInvoice() {
        assertTrue(AgenticEvents.validTool("submit_payment", Json.read("{\"invoiceId\":\"INV-1042\"}")));
        assertFalse(AgenticEvents.validTool("submit_payment", Json.read("{\"invoiceId\":\"INV-1042\",\"amountMinor\":1}")));
        assertFalse(AgenticEvents.validTool("submit_payment", Json.read("{\"invoiceId\":\"OTHER\"}")));
        assertFalse(AgenticEvents.validTool("unregistered", Json.read("{\"invoiceId\":\"INV-1042\"}")));
    }
    @Test void geminiAdapterSuppliesBusinessContextToolsAndPreservesSignedContent() {
        var captured = new AtomicReference<org.springframework.ai.chat.prompt.Prompt>();
        var content = Json.read("{\"role\":\"model\",\"parts\":[{\"thought\":true,\"text\":\"PRIVATE_THOUGHT\"},{\"thoughtSignature\":\"opaque-signature\",\"functionCall\":{\"name\":\"submit_payment\",\"id\":\"provider-call\",\"args\":{\"invoiceId\":\"INV-1042\"}}}]}");
        var model = GeminiPlannerModel.protocolTest(request -> { captured.set(request); return SpringAiTestSupport.reply(content, "STOP"); });
        var reply = model.generate(Json.MAPPER.createArrayNode());
        assertEquals("PROTOCOL_TEST", model.source()); assertEquals(content, reply.content()); assertEquals("", reply.text());
        assertEquals("provider-call", reply.calls().get(0).providerId());
        assertTrue(captured.get().getInstructions().get(0).getText().contains("UNKNOWN"));
        assertEquals(3, ((org.springframework.ai.google.genai.GoogleGenAiChatOptions) captured.get().getOptions()).getToolCallbacks().size());
    }
    @Test void incompleteGeminiResponseStopsWithoutScriptedFallback() {
        var model = GeminiPlannerModel.protocolTest(request -> SpringAiTestSupport.reply(SpringAiTestSupport.content("[]"), "MAX_TOKENS"));
        assertThrows(Problems.Permanent.class, () -> model.generate(Json.MAPPER.createArrayNode()));
    }
    @Test void brokerRedeliversUnacknowledgedMessageAndRejectsOldLease() throws Exception {
        try (var broker = new AgenticBroker(root)) {
            var event = AgenticEvents.event("WorkflowVerified", "call");
            var publish = Json.MAPPER.createObjectNode().put("topic", "agent").set("event", event);
            broker.handle("/publish", publish); broker.handle("/publish", publish);
            var query = Json.MAPPER.createObjectNode().put("topic", "agent");
            var first = Json.MAPPER.valueToTree(broker.handle("/claim", query));
            Thread.sleep(1550);
            var second = Json.MAPPER.valueToTree(broker.handle("/claim", query));
            assertEquals(2, second.path("deliveryCount").asInt());
            var ack = query.deepCopy().put("eventId", event.path("eventId").asText()).put("token", first.path("token").asText());
            assertFalse(Json.MAPPER.valueToTree(broker.handle("/ack", ack)).path("accepted").asBoolean());
            ack.put("token", second.path("token").asText());
            assertTrue(Json.MAPPER.valueToTree(broker.handle("/ack", ack)).path("accepted").asBoolean());
            event.put("changed", true); assertThrows(Problems.Contract.class, () -> broker.handle("/publish", publish));
        }
    }
    @Test void brokerRetainsAcceptedMessagesAcrossOwnerRestart() {
        var e = AgenticEvents.event("ActionProposed", "call");
        try (var b = new AgenticBroker(root)) { b.handle("/publish", Json.MAPPER.createObjectNode().put("topic", "workflow").set("event", e)); }
        try (var b = new AgenticBroker(root)) {
            var claim = Json.MAPPER.valueToTree(b.handle("/claim", Json.MAPPER.createObjectNode().put("topic", "workflow")));
            assertEquals(e, claim.path("event"));
        }
    }
}
