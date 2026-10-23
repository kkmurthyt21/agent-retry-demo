package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Offline replacement for a model response. Every decision is explicitly scripted. */
final class ScriptedAgentDecisionProvider implements AgentDecisionProvider {
    enum Scenario { APPROVED, UNAUTHORIZED_BENEFICIARY }
    private final JsonNode response;

    ScriptedAgentDecisionProvider(Scenario scenario) {
        String resource = switch (scenario) {
            case APPROVED -> "/agent-decisions/approved.json";
            case UNAUTHORIZED_BENEFICIARY -> "/agent-decisions/unauthorized-beneficiary.json";
        };
        try (var in = getClass().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing scripted decision " + resource);
            response = Json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException("Cannot read scripted decision " + resource, e); }
    }

    /** Explicit responses also support contract and policy failure fixtures. */
    ScriptedAgentDecisionProvider(JsonNode response) {
        this.response = Objects.requireNonNull(response).deepCopy();
    }

    @Override public String source() { return "SCRIPTED"; }
    @Override public JsonNode propose(JsonNode approvedRequest) { return response.deepCopy(); }
}
