package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;

/** Permission comes from the trusted workflow row. Agent text supplies no authority. */
final class Policy {
    static final String VERSION = "demo-policy-1";
    void authorize(Database.Row workflow, JsonNode proposal, long now) {
        if (!Planner.TENANT.equals(workflow.text("tenant"))
                || !"demo-operator".equals(workflow.text("subject_name"))
                || !"PAYMENT_SUBMITTER".equals(workflow.text("role_name"))) deny("TRUSTED_SUBJECT_DENIED");
        if (now >= workflow.millis("deadline_ms")) deny("DEADLINE_EXPIRED");
        if (!"demo-account-001".equals(proposal.path("debitAccountRef").asText())) deny("DEBIT_ACCOUNT_DENIED");
        if (!"demo-beneficiary-001".equals(proposal.path("beneficiaryRef").asText())) deny("BENEFICIARY_DENIED");
        if (!"USD".equals(proposal.path("currency").asText())) deny("CURRENCY_DENIED");
        if (!"DEMO_NETWORK".equals(proposal.path("route").asText())) deny("ROUTE_DENIED");
        long amount = proposal.path("amountMinor").asLong();
        if (amount <= 0 || amount > 100000) deny("AMOUNT_DENIED");
        if (!Json.actionFingerprint(Json.read(workflow.text("approved_json"))).equals(Json.actionFingerprint(proposal))) deny("APPROVED_INTENT_MISMATCH");
    }
    private static void deny(String reason) { throw new Problems.Policy(reason); }
}
