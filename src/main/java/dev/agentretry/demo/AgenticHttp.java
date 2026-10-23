package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BiFunction;

/** Loopback HTTP only transports durable broker requests and mock-provider calls. */
final class AgenticHttp {
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static JsonNode post(int port, String path, JsonNode body) {
        try {
            var r = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 400 || r.statusCode() == 409) throw new Problems.Permanent("LOCAL_REQUEST_REJECTED_" + r.statusCode());
            if (r.statusCode() != 200) throw new Problems.Transient("LOCAL_SERVICE_HTTP_" + r.statusCode());
            return Json.read(r.body());
        } catch (Problems.Permanent | Problems.Transient e) { throw e; }
        catch (Exception e) { throw new Problems.Transient("LOCAL_SERVICE_UNAVAILABLE"); }
    }
    static HttpServer serve(BiFunction<String, JsonNode, Object> handler) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
        server.createContext("/", exchange -> {
            int status = 200; Object result;
            try {
                if (!"POST".equals(exchange.getRequestMethod())) throw new IllegalArgumentException("POST_REQUIRED");
                var bytes = exchange.getRequestBody().readNBytes(131073);
                if (bytes.length > 131072) throw new IllegalArgumentException("BODY_TOO_LARGE");
                result = handler.apply(exchange.getRequestURI().getPath(), Json.read(new String(bytes, StandardCharsets.UTF_8)));
            } catch (Problems.Permanent | IllegalArgumentException e) { status = 400; result = java.util.Map.of("error", e.getMessage()); }
            catch (Exception e) { status = 503; result = java.util.Map.of("error", "SERVICE_UNAVAILABLE"); }
            byte[] bytes = Json.write(result).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start(); return server;
    }
    static void save(Path path, Object value) {
        CheckpointFiles.write(path, Json.write(value));
    }
    static JsonNode empty() { return Json.MAPPER.createObjectNode(); }
}
