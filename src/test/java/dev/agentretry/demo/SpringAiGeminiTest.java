package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpringAiGeminiTest {
    @TempDir Path root;
    com.fasterxml.jackson.databind.node.ArrayNode task() {
        return Json.MAPPER.createArrayNode().add(Json.read("{\"role\":\"user\",\"parts\":[{\"text\":\"Check and submit invoice INV-1042\"}]}"));
    }
    @Test void actualSpringAiClientSendsRegisteredToolsSystemPromptAndAutoChoice() throws Exception {
        try (var api = new SpringAiTestSupport.WireServer(request -> SpringAiTestSupport.response(SpringAiTestSupport.tool("submit_payment", "provider-1")))) {
            var model = GeminiPlannerModel.protocolEndpoint(api.endpoint());
            var reply = model.generate(task());
            assertEquals("PROTOCOL_TEST", model.source()); assertEquals(GeminiPlannerModel.FRAMEWORK, model.integration());
            assertEquals("provider-1", reply.calls().get(0).providerId());
            assertEquals("submit_payment", reply.calls().get(0).name());
            var sent = api.requests.get(0);
            assertEquals(task(), sent.path("contents"));
            assertTrue(sent.path("systemInstruction").toString().contains("UNKNOWN"));
            assertEquals(3, sent.path("tools").get(0).path("functionDeclarations").size());
            assertEquals("AUTO", sent.path("toolConfig").path("functionCallingConfig").path("mode").asText());
            assertTrue(api.paths.get(0).endsWith("/models/protocol-test:generateContent"));
            assertEquals(1, api.requests.size(), "ChatModel must return the request without executing a tool callback");
        }
    }
    @Test void nativeSpringAiWireRetainsParallelCallIdsAndSignedPartsAfterAgentRestart() throws Exception {
        var signed = SpringAiTestSupport.content("[{\"thought\":true,\"text\":\"PRIVATE_THOUGHT\"},{\"thoughtSignature\":\"b3BhcXVlLXNpZ25hdHVyZQ==\",\"functionCall\":{\"id\":\"first\",\"name\":\"get_payment_status\",\"args\":{\"invoiceId\":\"INV-1042\"}}},{\"functionCall\":{\"id\":\"second\",\"name\":\"get_invoice_context\",\"args\":{\"invoiceId\":\"INV-1042\"}}}]");
        var requests = new AtomicInteger();
        try (var api = new SpringAiTestSupport.WireServer(request -> SpringAiTestSupport.response(requests.getAndIncrement() == 0 ? signed : SpringAiTestSupport.done()))) {
            var model = GeminiPlannerModel.protocolEndpoint(api.endpoint());
            try (var agent = new AgenticPlanner(root, model, 0, "task", 3000, false, () -> {})) { agent.ask(); }
            try (var agent = new AgenticPlanner(root, model, 0, "task", 3000, false, () -> {})) {
                agent.db.execute("UPDATE pending SET response=?", "{\"state\":\"VERIFIED\"}");
                agent.awaitTools(); agent.ask();
            }
            var replayed = api.requests.get(1).path("contents");
            assertEquals(signed, replayed.get(1));
            var responses = replayed.get(2).path("parts");
            assertEquals("first", responses.get(0).path("functionResponse").path("id").asText());
            assertEquals("second", responses.get(1).path("functionResponse").path("id").asText());
            String trace = Files.readString(root.resolve("agent-trace.jsonl"));
            assertFalse(trace.contains("PRIVATE_THOUGHT")); assertFalse(trace.contains("b3BhcXVlLXNpZ25hdHVyZQ=="));
        }
    }
    @Test void springAndSdkRetriesDoNotMultiplyTheSavedApplicationApiBudget() throws Exception {
        try (var api = new SpringAiTestSupport.WireServer(request -> new SpringAiTestSupport.Response(429, Json.read("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"protocol test\"}}")))) {
            var model = GeminiPlannerModel.protocolEndpoint(api.endpoint());
            try (var agent = new AgenticPlanner(root, model, 0, "task", 3000, false, () -> {})) {
                assertEquals("MODEL_API_BUDGET_EXHAUSTED", assertThrows(IllegalStateException.class, agent::ask).getMessage());
                assertEquals(3, api.requests.size()); assertEquals(3, agent.number("apiAttempts"));
                assertEquals(0, agent.summary().path("submitProposals").asInt());
            }
        }
    }
    @Test void permanentApiErrorStopsWithoutRetryOrScriptedFallback() throws Exception {
        try (var api = new SpringAiTestSupport.WireServer(request -> new SpringAiTestSupport.Response(400, Json.read("{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"protocol test\"}}")))) {
            var model = GeminiPlannerModel.protocolEndpoint(api.endpoint());
            assertTrue(assertThrows(Problems.Permanent.class, () -> model.generate(task())).reason.startsWith("GEMINI_HTTP_400"));
            assertEquals(1, api.requests.size()); assertEquals("PROTOCOL_TEST", model.source());
        }
    }
    @Test void incompleteNativeModelResponseIsPermanent() throws Exception {
        try (var api = new SpringAiTestSupport.WireServer(request -> SpringAiTestSupport.response(SpringAiTestSupport.done(), "MAX_TOKENS"))) {
            assertEquals("GEMINI_INCOMPLETE_OUTPUT", assertThrows(Problems.Permanent.class, () -> GeminiPlannerModel.protocolEndpoint(api.endpoint()).generate(task())).reason);
        }
    }
    @Test void protocolEndpointCannotTargetAnExternalServer() {
        assertThrows(IllegalArgumentException.class, () -> GeminiPlannerModel.protocolEndpoint(java.net.URI.create("https://example.com")));
    }
    @Test void tooManyNativeToolRequestsStopBeforeCreatingDomainCommands() throws Exception {
        var parts = Json.MAPPER.createArrayNode();
        for (int i = 0; i < 5; i++) parts.add(SpringAiTestSupport.tool("submit_payment", "call-" + i).path("parts").get(0));
        var content = Json.MAPPER.createObjectNode().put("role", "model").set("parts", parts);
        try (var api = new SpringAiTestSupport.WireServer(request -> SpringAiTestSupport.response(content));
             var agent = new AgenticPlanner(root, GeminiPlannerModel.protocolEndpoint(api.endpoint()), 0, "task", 3000, false, () -> {})) {
            assertThrows(IllegalStateException.class, agent::ask);
            assertEquals(0, agent.db.one("SELECT COUNT(*) n FROM outbox").number("n"));
            assertEquals(1, api.requests.size());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"normal", "completion-status", "completion-repeat", "hold"})
    void springAiToolLoopTraversesDurableEventWorkflow(String scenario) throws Exception {
        boolean hold = "hold".equals(scenario), completion = scenario.startsWith("completion");
        try (var api = new SpringAiTestSupport.WireServer(request -> {
            String contents = request.path("contents").toString();
            JsonNode response;
            if (contents.contains("\"onHold\":true") || contents.contains("\"state\":\"VERIFIED\"")) response = SpringAiTestSupport.done();
            else if (!contents.contains("get_invoice_context")) response = SpringAiTestSupport.tool("get_invoice_context", "context");
            else if (!contents.contains("get_payment_status")) response = SpringAiTestSupport.tool("get_payment_status", "initial-status");
            else if (contents.contains("\"status\":\"UNKNOWN\"")) response = SpringAiTestSupport.tool(scenario.endsWith("repeat") ? "submit_payment" : "get_payment_status", "recovery");
            else response = SpringAiTestSupport.tool("submit_payment", "payment");
            return SpringAiTestSupport.response(response);
        }); var broker = new AgenticBroker(root.resolve("broker")); var provider = new AgenticGateway(root.resolve("provider"))) {
            var brokerServer = AgenticHttp.serve(broker::handle); var providerServer = AgenticHttp.serve(provider::handle);
            try (var worker = new AgenticWorkflow(root.resolve("workflow"), brokerServer.getAddress().getPort(), AgenticGateway.client(providerServer.getAddress().getPort()), hold, false);
                 var agent = new AgenticPlanner(root.resolve("agent"), GeminiPlannerModel.protocolEndpoint(api.endpoint()), brokerServer.getAddress().getPort(), "Check and submit INV-1042", 2000, completion, worker::tick)) {
                agent.run();
                var summary = agent.summary();
                assertEquals("PROTOCOL_TEST", summary.path("decisionSource").asText());
                assertEquals(GeminiPlannerModel.FRAMEWORK, summary.path("modelIntegration").asText());
                assertEquals(hold ? 0 : 1, provider.gateway.count());
                if (!hold) { assertEquals("VERIFIED", worker.operation().text("state")); assertTrue(summary.path("verifiedResultReturnedToModel").asBoolean()); }
                else assertEquals(0, summary.path("submitProposals").asInt());
                if (completion) {
                    assertTrue(summary.path("completionFaultTriggered").asBoolean());
                    assertTrue(summary.path("maxCompletionDeliveryCount").asInt() >= 2);
                    assertEquals(scenario.endsWith("repeat") ? 1 : 0, summary.path("freshPaymentProposalsAfterUnknown").asInt());
                    assertEquals(1, provider.gateway.submissionCalls.get());
                }
            } finally { brokerServer.stop(0); providerServer.stop(0); }
        }
    }
}
