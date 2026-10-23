package dev.agentretry.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One broker process owns durable queues, fenced leases and a delivery failure channel. */
final class AgenticBroker implements AutoCloseable {
    final Database db;
    AgenticBroker(Path root) { db = new Database(root, "agentic-broker"); }
    Object handle(String path, JsonNode n) {
        if ("/health".equals(path)) return Map.of("role", "broker", "pid", ProcessHandle.current().pid());
        if ("/stats".equals(path)) return Map.of("messages", db.one("SELECT COUNT(*) n FROM messages").number("n"),
            "maxDeliveries", db.one("SELECT COALESCE(MAX(deliveries),0) n FROM messages").number("n"),
            "deliveryFailures", db.one("SELECT COUNT(*) n FROM broker_failures").number("n"));
        String topic = n.path("topic").asText();
        if (!Set.of("workflow", "agent").contains(topic)) throw new IllegalArgumentException("Unknown topic");
        if ("/publish".equals(path)) {
            var e = n.path("event"); String id = e.path("eventId").asText(), fp = Json.fingerprint(e);
            if (id.isBlank() || id.length() > 255) throw new IllegalArgumentException("Invalid event ID");
            var old = db.one("SELECT fingerprint FROM messages WHERE topic=? AND event_id=?", topic, id);
            if (old != null) {
                if (!fp.equals(old.text("fingerprint"))) throw new Problems.Contract("EVENT_ID_CONTENT_CONFLICT");
                return Map.of("accepted", true, "duplicate", true);
            }
            db.execute("INSERT INTO messages(topic,event_id,body,fingerprint) VALUES(?,?,?,?)", topic, id, Json.write(e), fp);
            return Map.of("accepted", true, "duplicate", false);
        }
        long now = System.currentTimeMillis();
        if ("/claim".equals(path)) return db.transaction(c -> {
            var r = Database.one(c, "SELECT * FROM messages WHERE topic=? AND status='READY' AND lease_until<=? AND next_at<=? ORDER BY next_at,event_id LIMIT 1", topic, now, now);
            if (r == null) return Map.of("available", false);
            if (r.number("deliveries") >= 10) {
                Database.execute(c, "UPDATE messages SET status='FAILED' WHERE topic=? AND event_id=?", topic, r.text("event_id"));
                Database.execute(c, "INSERT INTO broker_failures VALUES(?,'DELIVERY','LEASE_BUDGET_EXHAUSTED')", r.text("event_id"));
                return Map.of("available", false);
            }
            String token = UUID.randomUUID().toString();
            Database.execute(c, "UPDATE messages SET deliveries=deliveries+1,lease_token=?,lease_until=? WHERE topic=? AND event_id=?", token, now + 1500, topic, r.text("event_id"));
            return Map.of("available", true, "token", token, "deliveryCount", r.number("deliveries") + 1, "event", Json.read(r.text("body")));
        });
        if ("/ack".equals(path) || "/nack".equals(path)) {
            boolean ack = "/ack".equals(path);
            int updated = db.execute("UPDATE messages SET status=?,lease_until=0,next_at=? WHERE topic=? AND event_id=? AND lease_token=? AND lease_until>? AND status='READY'",
                ack ? "DONE" : "READY", now + (ack ? 0 : 200), topic, n.path("eventId").asText(), n.path("token").asText(), now);
            return Map.of("accepted", updated == 1);
        }
        throw new IllegalArgumentException("Unknown broker route");
    }
    static JsonNode call(int port, String path, String topic, JsonNode eventOrClaim) {
        var n = Json.MAPPER.createObjectNode().put("topic", topic);
        if ("/publish".equals(path)) n.set("event", eventOrClaim);
        else if ("/ack".equals(path) || "/nack".equals(path)) n.put("eventId", eventOrClaim.path("event").path("eventId").asText()).put("token", eventOrClaim.path("token").asText());
        return AgenticHttp.post(port, path, n);
    }
    public void close() { db.close(); }
}
