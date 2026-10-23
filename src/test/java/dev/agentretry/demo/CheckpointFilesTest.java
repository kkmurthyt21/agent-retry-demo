package dev.agentretry.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointFilesTest {
    @TempDir Path root;

    @Test void replacesAnExistingCheckpointAndLeavesNoTemporaryFiles() throws Exception {
        Path target = root.resolve("status.json");
        CheckpointFiles.write(target, "{\"version\":1}");
        CheckpointFiles.write(target, "{\"version\":2}");
        assertEquals(2, Json.read(Files.readString(target)).path("version").asInt());
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void recoversFromTwoFileSharingFailuresWithoutRemovingThePreviousCheckpoint() throws Exception {
        Path target = root.resolve("status.json"); Files.writeString(target, "previous");
        var attempts = new AtomicInteger(); var delays = new ArrayList<Long>(); var names = new HashSet<Path>();
        CheckpointFiles.write(target, "replacement", (source, destination, options) -> {
            assertTrue(names.add(source), "Each attempt must own a different temporary file");
            assertEquals("previous", Files.readString(target));
            if (attempts.incrementAndGet() < 3) throw new AccessDeniedException(source.toString(), destination.toString(), "simulated file sharing conflict");
            Files.move(source, destination, options);
        }, delays::add);
        assertEquals(3, attempts.get()); assertEquals(java.util.List.of(25L, 50L), delays);
        assertEquals("replacement", Files.readString(target));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void persistentFileFailureStopsAtTheBudgetAndPreservesOldContentAndCause() throws Exception {
        Path target = root.resolve("status.json"); Files.writeString(target, "previous");
        var attempts = new AtomicInteger(); var delays = new ArrayList<Long>();
        var error = assertThrows(IllegalStateException.class, () -> CheckpointFiles.write(target, "replacement", (source, destination, options) -> {
            attempts.incrementAndGet(); throw new AccessDeniedException(source.toString(), destination.toString(), "persistent denial");
        }, delays::add));
        assertEquals(5, attempts.get()); assertEquals(java.util.List.of(25L, 50L, 100L, 200L), delays);
        assertEquals("previous", Files.readString(target));
        assertInstanceOf(AccessDeniedException.class, error.getCause());
        assertTrue(error.getMessage().contains(target.toString()));
        assertTrue(error.getMessage().contains("stage=replace-checkpoint, attempts=5"));
        assertTrue(error.getMessage().contains("persistent denial"));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void unsupportedAtomicMoveUsesExplicitReplacementFallback() throws Exception {
        Path target = root.resolve("status.json"); Files.writeString(target, "previous");
        var attempts = new AtomicInteger();
        CheckpointFiles.write(target, "replacement", (source, destination, options) -> {
            attempts.incrementAndGet();
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
                throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "simulated provider limitation");
            Files.move(source, destination, options);
        }, millis -> fail("A supported fallback should not sleep"));
        assertEquals(2, attempts.get()); assertEquals("replacement", Files.readString(target));
    }

    @Test void providerRejectingAtomicReplacementCanStillReplaceARegularFile() throws Exception {
        Path target = root.resolve("status.json"); Files.writeString(target, "previous");
        CheckpointFiles.write(target, "replacement", (source, destination, options) -> {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) throw new FileAlreadyExistsException(destination.toString());
            Files.move(source, destination, options);
        }, millis -> fail("A supported fallback should not sleep"));
        assertEquals("replacement", Files.readString(target));
    }

    @Test void otherIoFailuresAreReportedWithoutBlindRetries() throws Exception {
        Path target = root.resolve("status.json"); Files.writeString(target, "previous");
        var attempts = new AtomicInteger();
        var error = assertThrows(IllegalStateException.class, () -> CheckpointFiles.write(target, "replacement", (source, destination, options) -> {
            attempts.incrementAndGet(); throw new IOException("simulated device failure");
        }, millis -> fail("A generic IO error should not be retried")));
        assertEquals(1, attempts.get()); assertEquals("previous", Files.readString(target));
        assertEquals("simulated device failure", error.getCause().getMessage());
    }

    @Test void interruptionStopsRetriesAndRetainsTheInterruptSignal() throws Exception {
        Path target = root.resolve("status.json"); Files.writeString(target, "previous");
        try {
            var error = assertThrows(IllegalStateException.class, () -> CheckpointFiles.write(target, "replacement", (source, destination, options) -> {
                throw new AccessDeniedException(destination.toString());
            }, millis -> { throw new InterruptedException("stop"); }));
            assertTrue(Thread.currentThread().isInterrupted());
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertEquals("previous", Files.readString(target));
        } finally { Thread.interrupted(); }
    }
}
