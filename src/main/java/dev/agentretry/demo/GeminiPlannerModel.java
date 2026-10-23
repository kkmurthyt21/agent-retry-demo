package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Candidate;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongConsumer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/** Spring AI GoogleGenAiChatModel adapter; the durable application owns the tool loop. */
final class GeminiPlannerModel implements PlannerModel {
    static final String FRAMEWORK = "SPRING_AI_GOOGLE_GENAI_2.0.1";
    static final String RAW_CONTENT = "agentRetry.geminiContent", FINISH = "agentRetry.finishReason";
    private final String key, model;
    private final URI testEndpoint;
    private final Function<Prompt, ChatResponse> testModel;

    private GeminiPlannerModel(String key, String model, URI endpoint, Function<Prompt, ChatResponse> testModel) {
        this.key = key; this.model = model; testEndpoint = endpoint; this.testModel = testModel;
    }
    static GeminiPlannerModel fromEnvironment() {
        String key = System.getenv("GEMINI_API_KEY"), model = System.getenv("GEMINI_MODEL");
        if (key == null || key.isBlank()) key = System.getenv("GOOGLE_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("GEMINI_API_KEY is missing; no scripted fallback");
        if (model == null || !model.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Set GEMINI_MODEL to your tested model ID");
        return new GeminiPlannerModel(key, model, null, null);
    }
    /** Test-only Spring ChatModel seam, explicitly labelled PROTOCOL_TEST. */
    static GeminiPlannerModel protocolTest(Function<Prompt, ChatResponse> model) {
        return new GeminiPlannerModel("", "protocol-test", null, model);
    }
    /** Exercise the actual Spring AI + Google SDK against a loopback test server. */
    static GeminiPlannerModel protocolEndpoint(URI endpoint) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()))
            throw new IllegalArgumentException("Protocol tests require a loopback endpoint");
        return new GeminiPlannerModel("protocol-test-key", "protocol-test", endpoint, null);
    }
    Prompt request(ArrayNode conversation) {
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(resource("planner-instructions.txt")));
        for (var content : conversation) {
            if ("model".equals(content.path("role").asText())) {
                var decoded = PlannerModel.decode(content);
                var calls = decoded.calls().stream().map(c -> new AssistantMessage.ToolCall(c.providerId(), "function", c.name(), Json.write(c.args()))).toList();
                messages.add(AssistantMessage.builder().content(decoded.text()).toolCalls(calls).build());
            } else {
                var responses = new ArrayList<ToolResponseMessage.ToolResponse>(); var text = new StringBuilder();
                for (var part : content.path("parts")) {
                    if (part.has("functionResponse")) {
                        var f = part.path("functionResponse");
                        responses.add(new ToolResponseMessage.ToolResponse(f.path("id").asText(""), f.path("name").asText(), Json.write(f.path("response"))));
                    } else if (part.has("text")) text.append(part.path("text").asText());
                }
                if (!responses.isEmpty()) messages.add(ToolResponseMessage.builder().responses(responses).build());
                else messages.add(new UserMessage(text.toString()));
            }
        }
        var callbacks = new ArrayList<ToolCallback>();
        for (var declaration : Json.read(resource("tools.json")).path("functionDeclarations")) {
            var definition = ToolDefinition.builder().name(declaration.path("name").asText())
                .description(declaration.path("description").asText()).inputSchema(Json.write(declaration.path("parameters"))).build();
            callbacks.add(new ToolCallback() {
                public ToolDefinition getToolDefinition() { return definition; }
                public String call(String input) { throw new IllegalStateException("DURABLE_EVENT_DISPATCH_REQUIRED"); }
            });
        }
        var options = GoogleGenAiChatOptions.builder().model(model).toolCallbacks(callbacks)
            .toolChoice(new GoogleGenAiChatOptions.ToolChoice(GoogleGenAiChatOptions.ToolChoice.Mode.AUTO, null)).build();
        return new Prompt(messages, options);
    }
    @Override public Reply generate(ArrayNode conversation) {
        var prompt = request(conversation);
        var executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "spring-ai-gemini"); t.setDaemon(true); return t; });
        try {
            var pending = executor.submit(() -> testModel != null ? testModel.apply(prompt) : callSpringAi(prompt, conversation));
            var response = await(pending, Duration.ofSeconds(45), Duration.ofSeconds(5),
                elapsed -> System.out.println("[MODEL_WAIT] integration=" + FRAMEWORK + " elapsedSeconds=" + elapsed + " requestLimitSeconds=45"));
            if (response == null || response.getResults().isEmpty()) throw new Problems.Permanent("GEMINI_INCOMPLETE_OUTPUT");
            var metadata = response.getResult().getOutput().getMetadata();
            if (!"STOP".equals(metadata.get(FINISH)) || !(metadata.get(RAW_CONTENT) instanceof String content))
                throw new Problems.Permanent("GEMINI_INCOMPLETE_OUTPUT");
            return PlannerModel.decode(Json.read(content));
        } catch (Problems.Permanent | Problems.Transient e) { throw e; }
        catch (TimeoutException e) { throw new Problems.Transient("GEMINI_REQUEST_TIMEOUT_45S"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Problems.Permanent("GEMINI_REQUEST_INTERRUPTED"); }
        catch (ExecutionException e) { throw classify(e.getCause()); }
        finally { executor.shutdownNow(); }
    }
    private ChatResponse callSpringAi(Prompt prompt, ArrayNode conversation) {
        // Preserve signed parts and provider call IDs from the private durable journal.
        // Spring AI 2.0.1 reconstructs parts and drops those IDs during message conversion.
        // The SDK's extraBody hook preserves contents; Spring AI still supplies the model
        // call, system prompt, registered schemas and AUTO tool choice.
        var http = HttpOptions.builder().apiVersion("v1beta").timeout(45000)
            .retryOptions(HttpRetryOptions.builder().attempts(1).build())
            .extraBody(Map.of("contents", Json.MAPPER.convertValue(conversation, List.class)))
            .baseUrl(testEndpoint == null ? "https://generativelanguage.googleapis.com" : testEndpoint.toString()).build();
        try (var client = Client.builder().apiKey(key).vertexAI(false).httpOptions(http).build()) {
            var model = new JournaledGoogleGenAiChatModel(client, (GoogleGenAiChatOptions) prompt.getOptions());
            return model.call(prompt);
        }
    }
    private static RuntimeException classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof Problems.Permanent p) return p;
            if (cause instanceof Problems.Transient p) return p;
            if (cause instanceof ApiException api) return api.code() == 429 || api.code() >= 500
                ? new Problems.Transient("GEMINI_HTTP_" + api.code()) : new Problems.Permanent("GEMINI_HTTP_" + api.code() + "; check model access and API key locally");
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException)
                return new Problems.Transient("GEMINI_REQUEST_TIMEOUT_45S");
            if (cause instanceof javax.net.ssl.SSLException) return new Problems.Transient("GEMINI_TLS_FAILED");
            if (cause instanceof java.net.ConnectException) return new Problems.Transient("GEMINI_CONNECT_FAILED");
            if (cause instanceof LinkageError || cause instanceof IllegalArgumentException) return new Problems.Permanent("GEMINI_CLIENT_CONFIGURATION_ERROR");
        }
        return new Problems.Transient("GEMINI_CONNECTION_FAILED");
    }
    /** Retain provider parts for recovery; public traces still filter out thoughts/signatures. */
    private static final class JournaledGoogleGenAiChatModel extends GoogleGenAiChatModel {
        JournaledGoogleGenAiChatModel(Client client, GoogleGenAiChatOptions options) {
            super(client, options, ToolCallingManager.builder().build(), new RetryTemplate(RetryPolicy.withMaxRetries(0)), ObservationRegistry.NOOP);
        }
        @Override protected List<Generation> responseCandidateToGeneration(Candidate candidate) {
            if (candidate.content().isEmpty()) throw new Problems.Permanent("GEMINI_INCOMPLETE_OUTPUT");
            String raw = candidate.content().get().toJson();
            var output = new ArrayList<Generation>();
            for (var generation : super.responseCandidateToGeneration(candidate)) {
                var metadata = new HashMap<>(generation.getOutput().getMetadata());
                metadata.put(RAW_CONTENT, raw); metadata.put(FINISH, candidate.finishReason().map(Object::toString).orElse(""));
                output.add(new Generation(generation.getOutput().mutate().properties(metadata).build(), generation.getMetadata()));
            }
            return output;
        }
    }
    static <T> T await(Future<T> future, Duration timeout, Duration interval, LongConsumer waiting)
            throws InterruptedException, ExecutionException, TimeoutException {
        long started = System.nanoTime(), deadline = started + timeout.toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Model response deadline exceeded");
                try { return future.get(Math.min(remaining, interval.toNanos()), TimeUnit.NANOSECONDS); }
                catch (TimeoutException e) { if (System.nanoTime() >= deadline) throw e; waiting.accept(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)); }
            }
        } catch (TimeoutException | InterruptedException e) { future.cancel(true); throw e; }
    }
    static String resource(String name) {
        try (var in = GeminiPlannerModel.class.getResourceAsStream("/agentic/" + name)) {
            if (in == null) throw new IllegalStateException("Missing planner resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public String source() { return testModel == null && testEndpoint == null ? "GEMINI" : "PROTOCOL_TEST"; }
    public String model() { return model; }
    public String integration() { return testModel == null ? FRAMEWORK : "SPRING_AI_MODEL_TEST_DOUBLE"; }
}
