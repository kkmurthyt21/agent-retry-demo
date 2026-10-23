package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringAiJarIT {
    @TempDir Path root;
    @Test void shadedJarContainsWorkingSpringAiClientAndSignedToolRoundTrip() throws Exception {
        var calls = new AtomicInteger();
        var content = SpringAiTestSupport.content("[{\"thoughtSignature\":\"b3BhcXVl\",\"functionCall\":{\"id\":\"jar-call\",\"name\":\"get_invoice_context\",\"args\":{\"invoiceId\":\"INV-1042\"}}}]");
        try (var api = new SpringAiTestSupport.WireServer(request -> SpringAiTestSupport.response(calls.getAndIncrement() == 0 ? content : SpringAiTestSupport.done()))) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = Path.of(System.getProperty("demo.jar")).toAbsolutePath() + File.pathSeparator + Path.of("target/test-classes").toAbsolutePath();
            var output = root.resolve("jar-protocol.log");
            var builder = new ProcessBuilder(java, "-Xmx192m", "-cp", classpath, SpringAiJarProbe.class.getName(), api.endpoint().toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
            for (String key : new String[]{"GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_APPLICATION_CREDENTIALS"}) builder.environment().remove(key);
            var process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Packaged Spring AI protocol timed out"); }
            String log = Files.readString(output);
            assertEquals(0, process.exitValue(), log);
            assertTrue(log.contains("PACKAGED_SPRING_AI_PROTOCOL_OK"), log);
            assertEquals(content, api.requests.get(1).path("contents").get(1));
            assertEquals("jar-call", api.requests.get(1).path("contents").get(2).path("parts").get(0).path("functionResponse").path("id").asText());
            assertEquals(2, api.requests.size());
        }
    }
}
