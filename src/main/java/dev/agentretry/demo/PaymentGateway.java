package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;

/** The external-tool contract, independent of any cloud, broker, or model SDK. */
interface PaymentGateway {
    record Receipt(String tenant, String actionKey, String fingerprint, String instructionId, long acceptedAt) { }
    record Submission(Receipt receipt, boolean created) { }
    Submission submit(String tenant, String actionKey, JsonNode instruction);
    Receipt find(String tenant, String actionKey);
    JsonNode readInstruction(String instructionId);
}
