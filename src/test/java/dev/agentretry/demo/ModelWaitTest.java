package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelWaitTest {
    @Test void slowResponseReportsWaitThenCancelsAtTheWholeResponseDeadline() {
        var future = new CompletableFuture<String>(); var notices = new AtomicInteger();
        assertThrows(TimeoutException.class, () -> GeminiPlannerModel.await(future, Duration.ofMillis(180), Duration.ofMillis(30), seconds -> notices.incrementAndGet()));
        assertTrue(future.isCancelled()); assertTrue(notices.get() > 0);
    }
    @Test void completeResponseIsReturnedWithoutWaitingNotices() throws Exception {
        var future = CompletableFuture.completedFuture("body"); var notices = new AtomicInteger();
        assertEquals("body", GeminiPlannerModel.await(future, Duration.ofSeconds(1), Duration.ofMillis(50), seconds -> notices.incrementAndGet()));
        assertEquals(0, notices.get());
    }
    @Test void interruptionCancelsThePendingResponse() {
        var future = new CompletableFuture<String>(); Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> GeminiPlannerModel.await(future, Duration.ofSeconds(1), Duration.ofMillis(50), seconds -> {})); assertTrue(future.isCancelled()); }
        finally { Thread.interrupted(); }
    }
}
