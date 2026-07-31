package org.icij.datashare.text.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    public ArtifactProducer(ManifestRepository repository, BooleanSupplier cancelRequested) {
        this.repository = repository;
        this.cancelRequested = cancelRequested;
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
        if (Thread.currentThread().isInterrupted()) {
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
            // put() holds the per-doc write lock while it merges the entry, so the recorded manifest
            // stays consistent (and cross-process/host safe) with the payload just written.
            repository.put(context.docArtifactDir(), type.token(), produced.withTerminalStatus());
            return true;
        } catch (ArtifactException | IOException failure) {
            // Restoring the flag is what ends ArtifactTask's while(!isInterrupted()) loop instead of
            // polling the next document; skipped, not failed, so a clean cancellation is not counted.
            if (isCancellation(failure)) {
                Thread.currentThread().interrupt();
                LOGGER.debug("cancelled while producing '{}' for document {}", type.token(), context.document().getId(), failure);
                return true;
            }
            if (failure instanceof UnparseableContentException) {
                return recordNoPayload(artifact, context, failure);
            }
            LOGGER.error("failed to produce artifact '{}' for document {}", type.token(), context.document().getId(), failure);
            return false;
        }
    }

    // Content no parser can read will not parse on the next run either, so skip-if-current leaves it
    // alone instead of re-parsing every corrupt file in the corpus on every run, and an operator chasing
    // real failures is not shown an ERROR for a document a re-run cannot fix.
    private boolean recordNoPayload(Artifact artifact, ArtifactContext context, Exception failure) {
        ArtifactType type = artifact.type();
        // No stack trace: these are benign, and a corpus holds enough of them to bury the real failures.
        LOGGER.warn("{}: recording an empty '{}' entry so it is not parsed again", failure.getMessage(), type.token());
        try {
            repository.put(context.docArtifactDir(), type.token(), ManifestEntry.empty(artifact.taskInput()));
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
        return Thread.currentThread().isInterrupted()
                || (cancelRequested.getAsBoolean() && causedByInterrupt(failure));
    }

    private static boolean causedByInterrupt(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    private boolean isCurrent(ArtifactType type, Artifact artifact, ArtifactContext context) throws IOException {
        ManifestEntry existing = repository.get(context.docArtifactDir(), type.token());
        return existing != null && existing.isCurrentFor(artifact.taskInput());
    }
}
