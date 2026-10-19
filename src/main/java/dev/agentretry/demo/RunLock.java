package dev.agentretry.demo;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One workflow dispatcher owns the fixture directory. OS ownership ends on hard process exit. */
final class RunLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    RunLock(Path root) {
        try {
            Files.createDirectories(root);
            channel = FileChannel.open(root.resolve(".workflow-process.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired;
            try { acquired = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException e) { channel.close(); throw new IllegalStateException("Another workflow dispatcher owns this directory", e); }
            if (acquired == null) { channel.close(); throw new IllegalStateException("Another workflow dispatcher owns this directory"); }
            lock = acquired;
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot lock demo directory", e); }
    }
    @Override public void close() {
        try { lock.release(); channel.close(); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
