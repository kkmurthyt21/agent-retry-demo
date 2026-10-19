package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/** JSON encoding and explicit canonicalization for event and action identity. */
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Json() { }
    static JsonNode read(String text) {
        try { return MAPPER.readTree(text); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid JSON", e); }
    }
    static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot encode JSON", e); }
    }
    static String canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, String>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), canonical(e.getValue())));
            return "{" + sorted.entrySet().stream()
                    .map(e -> write(e.getKey()) + ":" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        if (node.isArray()) {
            var values = new java.util.ArrayList<String>();
            node.forEach(v -> values.add(canonical(v)));
            return "[" + String.join(",", values) + "]";
        }
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
        return node.toString();
    }
    static String fingerprint(JsonNode node) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical(node).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static ObjectNode instruction(String beneficiary, long amount) {
        var n = MAPPER.createObjectNode();
        n.put("action", "SUBMIT_PAYMENT_INSTRUCTION");
        n.put("debitAccountRef", "demo-account-001");
        n.put("beneficiaryRef", beneficiary);
        n.put("amountMinor", amount);
        n.put("currency", "USD");
        n.put("route", "DEMO_NETWORK");
        return n;
    }
    static JsonNode normalInstruction() { return instruction("demo-beneficiary-001", 25000); }
    static String actionFingerprint(JsonNode data) {
        var n = MAPPER.createObjectNode();
        for (String k : new String[]{"action", "debitAccountRef", "beneficiaryRef", "amountMinor", "currency", "route"}) {
            if (!data.has(k)) throw new IllegalArgumentException("Missing action parameter " + k);
            n.set(k, data.get(k));
        }
        // Absent clientReference has the explicit default "" in both supported contracts.
        n.put("clientReference", data.path("clientReference").asText(""));
        return fingerprint(n);
    }
}
