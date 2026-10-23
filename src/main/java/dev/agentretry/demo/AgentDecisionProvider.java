package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;

/** Decision boundary: a provider proposes data; application code supplies authority. */
interface AgentDecisionProvider {
    String source();
    JsonNode propose(JsonNode approvedRequest);
}
