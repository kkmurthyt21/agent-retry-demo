package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Consumer;

/** Narrow adapter. Resume the saved action identity and recover its receipt when present. */
final class Executor {
    private final PaymentGateway gateway;
    Executor(PaymentGateway gateway) { this.gateway = gateway; }
    PaymentGateway.Receipt execute(String tenant, String key, JsonNode intent, Consumer<String> output) {
        var known = gateway.find(tenant, key);
        if (known != null) {
            if (!known.fingerprint().equals(Json.actionFingerprint(intent))) throw new Problems.Permanent("ACTION_KEY_PARAMETER_CONFLICT");
            output.accept("[RECOVERED_RECEIPT] instructionId=" + known.instructionId() + " externalActionRepeated=false");
            return known;
        }
        var submitted = gateway.submit(tenant, key, intent);
        output.accept("[GATEWAY] status=ACCEPTED created=" + submitted.created() + " instructionId=" + submitted.receipt().instructionId());
        return submitted.receipt();
    }
}
