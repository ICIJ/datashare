package org.icij.datashare.text.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Runs the uniform skip-if-current -> produce -> record loop for a set of artifacts on one
 *  document. Which artifacts to run (selection) is decided by the caller (the app). */
public class ArtifactProducer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactProducer.class);
    private final ManifestRepository repository;
    private final BooleanSupplier cancelRequested;
    private final String taskId;

    public ArtifactProducer(ManifestRepository repository, BooleanSupplier cancelRequested, String taskId) {
        this.repository = repository;
        this.cancelRequested = cancelRequested;
        this.taskId = taskId;
    }

    public boolean run(List<Artifact> artifacts, ArtifactContext context, boolean force) {
        // reduce (not allMatch): allMatch short-circuits on the first false, which would skip
        // producing sibling types once one fails; every type must get its turn.
        return dedupeByType(artifacts).values().stream()
                .map(artifact -> produce(artifact, context, force))
                .reduce(true, Boolean::logicalAnd);
    }

    private Map<ArtifactType, Artifact> dedupeByType(List<Artifact> artifacts) {
        Map<ArtifactType, Artifact> byType = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            byType.putIfAbsent(artifact.type(), artifact);
        }
        return byType;
    }

    // true when produced, skipped (skip-if-current, or a cancellation caught mid-type), or
    // empty-recorded; false only when a genuine failure was caught and isolated.
    private boolean produce(Artifact artifact, ArtifactContext context, boolean force) {
        ArtifactType type = artifact.type();
        // A cancel caught on an earlier type must not let a fresh Tika parse start for this one on the
        // same document. Skipped, not failed, so true.
        if (cancelRequested.getAsBoolean() && Thread.currentThread().isInterrupted()) {
            return true;
        }
        try {
            // Read-only skip-if-current pre-check: a document already produced with the same config is
            // not reprocessed. This is the common-case optimization and needs no lock.
            if (!force && isCurrent(type, artifact, context)) {
                return true;
            }
            // Production runs unlocked, mirroring the Python side (compute out of lock, then write under
            // lock): real concurrency on the same document is rare and, when it happens, at worst
            // duplicates work (accepted for now). Only the manifest write below is serialised.
            ManifestEntry produced = artifact.produce(context);
            record(context, type, produced.withTerminalStatus());
            return true;
        } catch (UnreadableContentException unreadable) {
            // The cancel question comes first: a cancelled Tika parse arrives as a parse failure too, and
            // recording "no parser can read this" because the operator pressed cancel is a lie.
            if (handledAsCancellation(type, context, unreadable)) {
                return true;
            }
            return storeEmptyEntry(artifact, context, unreadable);
        } catch (ArtifactException | IOException failure) {
            if (handledAsCancellation(type, context, failure)) {
                return true;
            }
            LOGGER.error("failed to produce artifact '{}' for document {}", type.token(), context.document().getId(), failure);
            return false;
        }
    }

    // The one manifest-write point for every path, so no artifact is recorded without the id of the run
    // that produced it. put() holds the per-doc write lock while it merges the entry, so the recorded
    // manifest stays consistent (and cross-process/host safe) with the payload just written.
    private void record(ArtifactContext context, ArtifactType type, ManifestEntry entry) throws IOException {
        repository.put(context.docArtifactDir(), type.token(), entry.withTaskId(taskId));
    }

    // Restoring the flag is what ends ArtifactTask's while(!isInterrupted()) loop instead of polling the
    // next document. The caller then reports skipped, not failed, so a clean cancellation is not counted.
    private boolean handledAsCancellation(ArtifactType type, ArtifactContext context, Exception failure) {
        if (!isCancellation(failure)) {
            return false;
        }
        Thread.currentThread().interrupt();
        LOGGER.debug("cancelled while producing '{}' for document {}", type.token(), context.document().getId(), failure);
        return true;
    }

    // Content no parser can read will not parse on the next run either, so skip-if-current leaves it
    // alone instead of re-parsing every corrupt file in the corpus on every run, and an operator chasing
    // real failures is not shown an ERROR for a document a re-run cannot fix.
    private boolean storeEmptyEntry(Artifact artifact, ArtifactContext context, Exception failure) {
        ArtifactType type = artifact.type();
        // No stack trace: these are benign, and a corpus holds enough of them to bury the real failures.
        LOGGER.warn("{}: recording an empty '{}' entry so it is not parsed again", failure.getMessage(), type.token());
        try {
            record(context, type, ManifestEntry.empty(artifact.taskInput(context.document())));
            return true;
        } catch (IOException recordFailure) {
            LOGGER.error("failed to record artifact '{}' for document {}", type.token(), context.document().getId(), recordFailure);
            return false;
        }
    }

    /**
     * Whether {@code failure} is this run being cancelled rather than a document going wrong. Nothing
     * counts unless the task itself reports a cancel, because neither signal is proof on its own: the
     * interrupt flag can be set by a library that interrupts and re-sets it or by a client's timeout
     * handling, and Tika's fork and external parsers wrap unrelated failures in an InterruptedException.
     * Believing either one alone ends the whole remaining queue green, with nbFailed at 0.
     */
    public boolean isCancellation(Throwable failure) {
        return cancelRequested.getAsBoolean()
                && (Thread.currentThread().isInterrupted() || causedByInterrupt(failure));
    }

    // InterruptedIOException as well as the two obvious ones: extract-lib's cancellation path surfaces it,
    // and being an IOException it would otherwise be counted as a failed document.
    private static boolean causedByInterrupt(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof ClosedByInterruptException
                    || cause instanceof InterruptedIOException) {
                return true;
            }
            // A custom or deserialised exception can return itself from getCause(), which would make this
            // walk spin forever inside the worker's catch block.
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    private boolean isCurrent(ArtifactType type, Artifact artifact, ArtifactContext context) throws IOException {
        ManifestEntry existing = repository.get(context.docArtifactDir(), type.token());
        // The document's own fingerprint, the one produce() records: comparing the run-level one here
        // would skip a document whose payload was made under a different per-document input.
        if (existing == null || !existing.isCurrentFor(artifact.taskInput(context.document()))) {
            return false;
        }
        // A terminal entry is not proof the payload survived a JVM death mid-swap or a failed restore.
        // Asking the disk is what lets a plain re-run repair it, instead of --artifactsForce on the corpus.
        if (ArtifactPayload.isMissing(context.docArtifactDir(), type, existing)) {
            LOGGER.warn("'{}' entry for document {} is current but its payload is gone: re-producing",
                    type.token(), context.document().getId());
            return false;
        }
        return true;
    }
}
