package org.icij.datashare.utils;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Replaces a directory with a freshly written one, so a reader never sees a half-written directory and
 * a failed write leaves the previous contents in place. The new contents are written into a staging
 * directory alongside the target and renamed over it, which POSIX cannot do in one step for a non-empty
 * target, hence the holding pen below.
 * <p>
 * Staging and holding-pen directories are siblings of the target, so every rename is same-filesystem
 * and therefore atomic, and they are named after it with a leading dot so a reader listing the parent
 * skips them.
 */
public class AtomicDirectorySwap {
    private static final Logger LOGGER = LoggerFactory.getLogger(AtomicDirectorySwap.class);
    private static final String REPLACED_SUFFIX = ".replaced";

    /** Writes the new contents into the staging directory it is handed. */
    public interface Writer {
        void writeInto(Path staging) throws IOException;
    }

    private AtomicDirectorySwap() {}

    /**
     * Writes new contents through {@code writer} and puts them at {@code target}, replacing whatever is
     * there (a directory, or a plain file another producer wrote at that path). The target's parent has
     * to exist. On any failure the staging directory goes and the previous contents stay.
     */
    public static void replace(Path target, Writer writer) throws IOException {
        String prefix = "." + target.getFileName() + "-";
        reclaimHoldingPens(target.getParent(), prefix);
        Path staging = createStagingDir(target.getParent(), prefix);
        Path aside = staging.resolveSibling(staging.getFileName() + REPLACED_SUFFIX);
        Throwable failure = null;
        try {
            writer.writeInto(staging);
            swapIntoPlace(staging, aside, target);
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            // Throwable, not Exception: an OutOfMemoryError mid-write is a real failure mode for big
            // payloads, and the staging name is unique per invocation, so what it leaves behind would
            // never be reclaimed by anything.
            discard(staging, failure);
        }
    }

    /**
     * Best-effort recursive delete, for a caller dropping contents it can regenerate. Never throws: a
     * leftover only wastes disk, which must not fail the work that asked for the delete.
     */
    public static void discard(Path directory) {
        discard(directory, null);
    }

    // A leftover holding pen means a delete failed, and its name is unique per invocation, so nothing
    // else would ever reclaim it: the target would cost a full extra copy on every rewrite. Only
    // ".replaced" pens are swept, never a staging directory, which a concurrent writer of the same
    // target may be writing into right now.
    private static void reclaimHoldingPens(Path parent, String prefix) throws IOException {
        try (Stream<Path> entries = Files.list(parent)) {
            entries.filter(entry -> isHoldingPen(entry.getFileName().toString(), prefix))
                    .forEach(leftover -> discard(leftover, null));
        }
    }

    private static boolean isHoldingPen(String name, String prefix) {
        return name.startsWith(prefix) && name.endsWith(REPLACED_SUFFIX);
    }

    // Files.createDirectory, not createTempDirectory: the JDK stamps a temp directory owner-only, and
    // this one is renamed into place as the target, which every uid sharing the parent has to read. The
    // random name still matters: two writers of the same target must not share a staging directory.
    private static Path createStagingDir(Path parent, String prefix) throws IOException {
        return Files.createDirectory(parent.resolve(prefix + UUID.randomUUID()));
    }

    // Two renames rather than a delete then a move: what is being replaced is only destroyed once the new
    // contents hold its place, so a failure anywhere here leaves the old ones readable. Residual window: a
    // JVM death between the two renames leaves the target missing. A concurrent writer of the same target
    // can make either rename fail, and nothing here can tell whose contents the path holds, so the caller
    // fails loudly rather than reporting success over someone else's bytes.
    private static void swapIntoPlace(Path staging, Path aside, Path target) throws IOException {
        if (!Files.exists(target)) {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        }
        Files.move(target, aside, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException moveFailure) {
            restore(aside, target, moveFailure);
            throw moveFailure;
        }
        // Here rather than in the caller's finally: a failed restore above deliberately keeps the aside as
        // the last copy of those contents.
        discard(aside, null);
    }

    // A restore that fails too leaves the only copy in the holding pen while the target is missing: name
    // the path at ERROR so an operator can rename it back by hand.
    private static void restore(Path aside, Path target, IOException moveFailure) {
        try {
            Files.move(aside, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException restoreFailure) {
            moveFailure.addSuppressed(restoreFailure);
            LOGGER.error("cannot restore {}: it is left in {}, rename it back by hand",
                    target, aside, restoreFailure);
        }
    }

    // forceDelete throws on the very conditions that break a write (a full disk, a revoked permission),
    // so it is attached to the real failure instead of replacing the cause the operator needs to see.
    private static void discard(Path directory, Throwable failure) {
        if (Files.notExists(directory)) {
            return;
        }
        try {
            FileUtils.forceDelete(directory.toFile());
        } catch (IOException cleanupFailure) {
            if (failure == null) {
                LOGGER.warn("cannot remove {}, leaving it behind", directory, cleanupFailure);
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }
}
