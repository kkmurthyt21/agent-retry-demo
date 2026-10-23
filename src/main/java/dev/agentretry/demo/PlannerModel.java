package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;

interface PlannerModel {
    record Call(String name, JsonNode args, String providerId) { }
    record Reply(JsonNode content, List<Call> calls, String text) { }
    Reply generate(ArrayNode conversation);
    String source();
    String model();
    default String integration() { return "SCRIPTED_FIXTURE"; }
    static Reply decode(JsonNode content) {
        var calls = new ArrayList<Call>(); var text = new StringBuilder();
        if (!content.path("parts").isArray()) throw new IllegalStateException("Missing model content");
        for (var p : content.path("parts")) {
            if (p.has("functionCall")) {
                var f = p.get("functionCall"); calls.add(new Call(f.path("name").asText(), f.path("args"), f.path("id").asText("")));
            } else if (p.has("text") && !p.path("thought").asBoolean()) text.append(p.path("text").asText()).append('\n');
        }
        if (calls.size() > 4) throw new IllegalStateException("More than four tool calls in one turn");
        return new Reply(content, calls, text.toString().trim());
    }
}
