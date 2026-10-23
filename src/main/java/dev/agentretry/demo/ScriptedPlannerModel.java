package dev.agentretry.demo;

import com.fasterxml.jackson.databind.node.ArrayNode;

/** Explicit deterministic rehearsal fixture; never evidence of a real model decision. */
final class ScriptedPlannerModel implements PlannerModel {
    public Reply generate(ArrayNode history) {
        var text = history.toString(); String tool;
        if (!text.contains("functionResponse")) tool = "get_invoice_context";
        else if (text.contains("\"onHold\":true") || text.contains("\"state\":\"VERIFIED\""))
            return PlannerModel.decode(Json.read("{\"role\":\"model\",\"parts\":[{\"text\":\"Rehearsal complete. Inspect the authoritative tool result.\"}]}"));
        else if (!text.contains("get_payment_status")) tool = "get_payment_status";
        else tool = "submit_payment";
        var content = Json.MAPPER.createObjectNode().put("role", "model");
        var call = Json.MAPPER.createObjectNode().put("name", tool);
        call.set("args", Json.MAPPER.createObjectNode().put("invoiceId", AgenticEvents.INVOICE));
        content.set("parts", Json.MAPPER.createArrayNode().add(Json.MAPPER.createObjectNode().set("functionCall", call)));
        return PlannerModel.decode(content);
    }
    public String source() { return "SCRIPTED"; }
    public String model() { return "scripted-rehearsal-v1"; }
}
