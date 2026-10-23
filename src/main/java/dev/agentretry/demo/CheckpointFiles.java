package dev.agentretry.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** JSON exports and launcher markers. Business state remains in the owning H2 database. */
final class CheckpointFiles {
    static final int MAX_ATTEMPTS = 5;
    @FunctionalInterface interface Mover { void move(Path source, Path target, CopyOption... options) throws IOException; }
    @FunctionalInterface interface Pause { void sleep(long millis) throws InterruptedException; }

    private CheckpointFiles() { }

    static void write(Path path, String text) {
        write(path, text, (source, target, options) -> Files.move(source, target, options), Thread::sleep);
    }

    static void write(Path path, String text, Mover mover, Pause pause) {
        Path target = path.toAbsolutePath().normalize();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Path temporary = null;
            IOException failed = null;
            String stage = "create-directory";
            try {
                Files.createDirectories(target.getParent());
                stage = "create-temporary-file";
                temporary = Files.createTempFile(target.getParent(), "checkpoint-" + target.getFileName() + ".", ".tmp");
                stage = "write-temporary-file";
                Files.writeString(temporary, text, StandardCharsets.UTF_8);
                stage = "replace-checkpoint";
                replace(temporary, target, mover);
                temporary = null;
                return;
            } catch (IOException e) {
                failed = e;
                if (!(e instanceof FileSystemException) || e instanceof NoSuchFileException
                        || e instanceof NotDirectoryException || attempt == MAX_ATTEMPTS) {
                    throw failure(target, stage, attempt, e);
                }
                long delay = 25L << (attempt - 1);
                System.err.println("[CHECKPOINT_RETRY] file=" + target + " attempt=" + attempt
                    + " maxAttempts=" + MAX_ATTEMPTS + " delayMs=" + delay + " error=" + e.getClass().getSimpleName());
            } finally {
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary); }
                    catch (IOException cleanup) { if (failed != null) failed.addSuppressed(cleanup); }
                }
            }
            try { pause.sleep(25L << (attempt - 1)); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(target, "retry-interrupted", attempt, e);
            }
        }
        throw new IllegalStateException("Unreachable checkpoint retry state");
    }

    private static void replace(Path temporary, Path target, Mover mover) throws IOException {
        try { mover.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { mover.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        catch (FileAlreadyExistsException e) {
            // Some providers cannot atomically replace an existing file. Never replace a directory or symlink.
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw e;
            mover.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static IllegalStateException failure(Path path, String stage, int attempts, Exception cause) {
        return new IllegalStateException("Cannot save run checkpoint " + path + " (stage=" + stage
            + ", attempts=" + attempts + "): " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
    }
}
