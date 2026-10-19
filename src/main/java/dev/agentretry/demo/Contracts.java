package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.Map;

/** Two explicitly supported, bundled JSON Schema 2020-12 contracts. No URL is constructed from input. */
final class Contracts {
    private final Map<String, JsonSchema> schemas = Map.of("1.0", load("1.0"), "1.1", load("1.1"));
    private static JsonSchema load(String version) {
        try (var in = Contracts.class.getResourceAsStream("/schemas/event-" + version + ".json")) {
            if (in == null) throw new IllegalStateException("Missing bundled schema " + version);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    JsonNode parse(String json) {
        if (json.length() > 65536) throw new Problems.Contract("EVENT_TOO_LARGE");
        try { var event = Json.read(json); validate(event); return event; }
        catch (Problems.Contract e) { throw e; }
        catch (IllegalArgumentException e) { throw new Problems.Contract("MALFORMED_JSON"); }
    }
    void validate(JsonNode event) {
        if (event == null || !event.isObject()) throw new Problems.Contract("ENVELOPE_MUST_BE_OBJECT");
        var schema = schemas.get(event.path("schemaVersion").asText());
        if (schema == null) throw new Problems.Contract("UNSUPPORTED_SCHEMA_VERSION");
        var errors = schema.validate(event);
        if (!errors.isEmpty()) throw new Problems.Contract("CONTRACT_INVALID: " + errors.stream()
                .map(Object::toString).sorted().collect(java.util.stream.Collectors.joining("; ")));
    }
}
