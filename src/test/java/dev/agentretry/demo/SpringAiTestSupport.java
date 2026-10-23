package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** Explicit test doubles; never evidence of real Gemini model behavior. */
final class SpringAiTestSupport {
    static ChatResponse reply(JsonNode content, String finish) {
        var message = AssistantMessage.builder().content("").properties(Map.of(
            GeminiPlannerModel.RAW_CONTENT, Json.write(content), GeminiPlannerModel.FINISH, finish)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }
    static JsonNode content(String parts) { return Json.read("{\"role\":\"model\",\"parts\":" + parts + "}"); }
    static JsonNode tool(String name, String id) {
        var f = Json.MAPPER.createObjectNode().put("name", name).put("id", id);
        f.set("args", Json.MAPPER.createObjectNode().put("invoiceId", AgenticEvents.INVOICE));
        return Json.MAPPER.createObjectNode().put("role", "model").set("parts", Json.MAPPER.createArrayNode().add(Json.MAPPER.createObjectNode().set("functionCall", f)));
    }
    static JsonNode done() { return content("[{\"text\":\"PROTOCOL_TEST complete\"}]"); }
    record Response(int status, JsonNode body) { }
    static Response response(JsonNode content, String finish) {
        var candidate = Json.MAPPER.createObjectNode().put("finishReason", finish).put("index", 0).set("content", content);
        return new Response(200, Json.MAPPER.createObjectNode().put("modelVersion", "protocol-test")
            .set("candidates", Json.MAPPER.createArrayNode().add(candidate)));
    }
    static Response response(JsonNode content) { return response(content, "STOP"); }
    static final class WireServer implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        final List<String> paths = Collections.synchronizedList(new ArrayList<>());
        WireServer(Function<JsonNode, Response> answer) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.add(request); paths.add(exchange.getRequestURI().getPath());
                Response response;
                try { response = answer.apply(request); }
                catch (RuntimeException error) { response = new Response(500, Json.read("{\"error\":{\"code\":500,\"status\":\"INTERNAL\",\"message\":\"protocol test failed\"}}")); }
                byte[] body = Json.write(response.body()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
        }
        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        public void close() { server.stop(0); }
    }
}
