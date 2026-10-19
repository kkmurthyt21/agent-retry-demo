package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GatewayTest {
    @TempDir Path root;
    MockPaymentGateway open(Faults faults) { return new MockPaymentGateway(root, Clock.systemUTC(), faults); }
    @Test void repeatedSameKeyReturnsOriginalReceiptAndSingleMutation() {
        try (var gateway = open(Faults.NONE)) {
            var first = gateway.submit("tenant", "action", Json.normalInstruction());
            var retry = gateway.submit("tenant", "action", Json.normalInstruction());
            assertTrue(first.created()); assertFalse(retry.created());
            assertEquals(first.receipt(), retry.receipt()); assertEquals(1, gateway.count());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"amountMinor", "currency", "beneficiaryRef", "debitAccountRef", "route", "clientReference"})
    void sameKeyWithChangedMeaningfulParameterIsAConflict(String field) {
        try (var gateway = open(Faults.NONE)) {
            gateway.submit("tenant", "action", Json.normalInstruction());
            var changed = (ObjectNode) Json.normalInstruction();
            if ("amountMinor".equals(field)) changed.put(field, 50000); else changed.put(field, "CHANGED");
            assertThrows(Problems.Permanent.class, () -> gateway.submit("tenant", "action", changed));
            assertEquals(1, gateway.count());
        }
    }
    @Test void propertyOrderAndEquivalentNumericRepresentationAreNotNewActions() {
        try (var gateway = open(Faults.NONE)) {
            var first = gateway.submit("tenant", "action", Json.normalInstruction());
            var reordered = Json.read("{\"route\":\"DEMO_NETWORK\",\"currency\":\"USD\",\"amountMinor\":25000.0,\"beneficiaryRef\":\"demo-beneficiary-001\",\"debitAccountRef\":\"demo-account-001\",\"action\":\"SUBMIT_PAYMENT_INSTRUCTION\"}");
            assertEquals(first.receipt(), gateway.submit("tenant", "action", reordered).receipt()); assertEquals(1, gateway.count());
        }
    }
    @Test void actionKeysAreScopedToTenant() {
        try (var gateway = open(Faults.NONE)) {
            gateway.submit("tenant-a", "same-key", Json.normalInstruction());
            gateway.submit("tenant-b", "same-key", Json.normalInstruction());
            assertEquals(2, gateway.count());
        }
    }
    @Test void operationAndInstructionRollbackTogether() {
        Faults fail = (p, id) -> { if (p == Faults.Point.GATEWAY_BETWEEN_WRITES) throw new Problems.Interrupted("POWER_LOSS_BEFORE_COMMIT"); };
        try (var gateway = open(fail)) {
            assertThrows(Problems.Interrupted.class, () -> gateway.submit("tenant", "action", Json.normalInstruction()));
            assertEquals(0, gateway.count()); assertNull(gateway.find("tenant", "action"));
        }
        try (var gateway = open(Faults.NONE)) { assertTrue(gateway.submit("tenant", "action", Json.normalInstruction()).created()); assertEquals(1, gateway.count()); }
    }
    @Test void concurrentSameKeyRequestsCommitExactlyOneInstruction() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try (var gateway = open(Faults.NONE)) {
            var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
            java.util.concurrent.Callable<PaymentGateway.Submission> call = () -> {
                ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS)); return gateway.submit("tenant", "action", Json.normalInstruction());
            };
            var a = workers.submit(call); var b = workers.submit(call);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            var first = a.get(15, TimeUnit.SECONDS); var second = b.get(15, TimeUnit.SECONDS);
            assertEquals(first.receipt(), second.receipt()); assertNotEquals(first.created(), second.created());
            assertEquals(1, gateway.count());
        } finally { workers.shutdownNow(); }
    }
}
