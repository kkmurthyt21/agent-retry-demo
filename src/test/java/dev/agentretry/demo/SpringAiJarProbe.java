package dev.agentretry.demo;

/** Runs outside Maven's dependency classpath to test the self-contained shaded JAR. */
public class SpringAiJarProbe {
    public static void main(String[] args) {
        var model = GeminiPlannerModel.protocolEndpoint(java.net.URI.create(args[0]));
        var history = Json.MAPPER.createArrayNode().add(Json.read("{\"role\":\"user\",\"parts\":[{\"text\":\"Read invoice INV-1042\"}]}"));
        var reply = model.generate(history);
        if (reply.calls().size() != 1 || !"jar-call".equals(reply.calls().get(0).providerId())) throw new IllegalStateException("Packaged Spring AI tool request mismatch");
        history.add(reply.content());
        history.add(Json.read("{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"id\":\"jar-call\",\"name\":\"get_invoice_context\",\"response\":{\"approved\":true}}}]}"));
        var complete = model.generate(history);
        if (!complete.calls().isEmpty() || !"PROTOCOL_TEST complete".equals(complete.text())) throw new IllegalStateException("Packaged Spring AI continuation mismatch");
        System.out.println("PACKAGED_SPRING_AI_PROTOCOL_OK integration=" + model.integration() + " source=" + model.source());
    }
}
