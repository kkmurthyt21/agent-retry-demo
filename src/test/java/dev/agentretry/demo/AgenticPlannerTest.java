package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgenticPlannerTest {
    @TempDir Path root;
    GeminiPlannerModel model(String parts) {
        return GeminiPlannerModel.protocolTest(request -> SpringAiTestSupport.reply(SpringAiTestSupport.content(parts), "STOP"));
    }
    @Test void parallelCallsKeepProviderOrderAndSignaturesWhilePublicTraceExcludesThoughts() throws Exception {
        String parts = "[{\"thought\":true,\"text\":\"PRIVATE_THOUGHT\"},{\"thoughtSignature\":\"SECRET_SIGNATURE\",\"functionCall\":{\"id\":\"first\",\"name\":\"get_payment_status\",\"args\":{\"invoiceId\":\"INV-1042\"}}},{\"functionCall\":{\"id\":\"second\",\"name\":\"get_invoice_context\",\"args\":{\"invoiceId\":\"INV-1042\"}}}]";
        try (var agent = new AgenticPlanner(root, model(parts), 0, "task", 3000, false, () -> {})) {
            agent.ask();
            agent.db.execute("UPDATE pending SET response=?", "{\"state\":\"VERIFIED\"}");
            agent.awaitTools();
            var history = agent.conversation();
            assertTrue(history.get(1).toString().contains("SECRET_SIGNATURE"));
            var responses = history.get(2).path("parts");
            assertEquals("first", responses.get(0).path("functionResponse").path("id").asText());
            assertEquals("second", responses.get(1).path("functionResponse").path("id").asText());
            var trace = Files.readString(root.resolve("agent-trace.jsonl"));
            assertFalse(trace.contains("PRIVATE_THOUGHT")); assertFalse(trace.contains("SECRET_SIGNATURE"));
            assertEquals("PROTOCOL_TEST", agent.summary().path("decisionSource").asText());
        }
    }
    @Test void twoInitialPaymentCallsDoNotCountAsLaterRetry() {
        String call = "{\"functionCall\":{\"name\":\"submit_payment\",\"args\":{\"invoiceId\":\"INV-1042\"}}}";
        try (var agent = new AgenticPlanner(root, model("[" + call + "," + call + "]"), 0, "task", 3000, false, () -> {})) {
            agent.ask(); assertEquals(2, agent.summary().path("submitProposals").asInt());
            assertEquals(0, agent.summary().path("freshPaymentProposalsAfterUnknown").asInt());
        }
    }
    @Test void statusLookupAfterUnknownIsReconciliation() {
        try (var agent = new AgenticPlanner(root, model("[{\"functionCall\":{\"name\":\"get_payment_status\",\"args\":{\"invoiceId\":\"INV-1042\"}}}]"), 0, "task", 3000, false, () -> {})) {
            agent.put("turn", "3"); agent.put("unknownTurn", "3"); agent.ask();
            assertEquals("PROTOCOL_TEST_STATUS_RECONCILIATION", agent.summary().path("observedBehavior").asText());
            assertEquals(0, agent.summary().path("freshPaymentProposalsAfterUnknown").asInt());
        }
    }
}
