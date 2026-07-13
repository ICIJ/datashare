package org.icij.datashare.text.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the uniform skip-if-current -> produce -> record loop for a set of artifacts on one
 *  document. Which artifacts to run (selection) is decided by the caller (the app). */
public class ArtifactProducer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactProducer.class);
    private final ManifestRepository repository;

    public ArtifactProducer(ManifestRepository repository) {
        this.repository = repository;
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

    // true when produced, skipped, or empty-recorded; false when a failure was caught and isolated.
    private boolean produce(Artifact artifact, ArtifactContext context, boolean force) {
        ArtifactType type = artifact.type();
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
            repository.put(context.docArtifactDir(), type.token(), stampTerminal(produced));
            return true;
        } catch (ArtifactException | IOException failure) {
            LOGGER.error("failed to produce artifact '{}' for document {}", type.token(), context.document().getId(), failure);
            return false;
        }
    }

    // Producers return EMPTY as-is; any other (status-less) entry is stamped COMPLETE only after
    // its payload has been written, so a crash mid-produce never leaves a "ready" lie.
    static ManifestEntry stampTerminal(ManifestEntry produced) {
        return produced.isTerminal() ? produced : produced.withStatus(ManifestEntryStatus.COMPLETE);
    }

    // An artifact is current when a terminal entry (COMPLETE or EMPTY) already exists and was
    // produced with the exact same task input (config + version) as this run; only then is
    // regeneration skipped.
    private boolean isCurrent(ArtifactType type, Artifact artifact, ArtifactContext context) throws IOException {
        ManifestEntry existing = repository.get(context.docArtifactDir(), type.token());
        return existing != null && existing.isTerminal() && existing.taskInput().equals(artifact.taskInput());
    }
}
