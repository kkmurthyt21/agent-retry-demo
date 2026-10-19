package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;

/** Checks provider-owned evidence independently of the Executor's success message. */
final class Verifier {
    private final PaymentGateway gateway;
    Verifier(PaymentGateway gateway) { this.gateway = gateway; }
    void verify(String tenant, String key, JsonNode approved, JsonNode localReceipt) {
        String id = localReceipt.path("instructionId").asText();
        var actual = gateway.readInstruction(id);
        var receipt = gateway.find(tenant, key);
        if (actual == null || receipt == null || !id.equals(receipt.instructionId())
                || !tenant.equals(actual.path("tenant").asText()) || !key.equals(actual.path("actionKey").asText())
                || !"ACCEPTED".equals(actual.path("status").asText())
                || !Json.actionFingerprint(approved).equals(Json.actionFingerprint(actual.path("request")))
                || !Json.actionFingerprint(approved).equals(receipt.fingerprint())) {
            throw new Problems.Permanent("VERIFICATION_MISMATCH");
        }
    }
}
