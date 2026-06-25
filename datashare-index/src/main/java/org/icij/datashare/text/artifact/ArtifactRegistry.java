package org.icij.datashare.text.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Holds the known artifact types, parses the --artifacts selector, and runs the
 *  uniform skip-if-current -> produce -> record loop. */
public class ArtifactRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactRegistry.class);
    private final Map<String, Artifact> byType = new LinkedHashMap<>();
    private final ManifestStore store;

    public ArtifactRegistry(List<Artifact> artifacts, ManifestStore store) {
        this.store = store;
        for (Artifact artifact : artifacts) {
            byType.put(artifact.type(), artifact);
        }
    }

    /** bare/empty -> all types; CSV -> subset (case-insensitive, trimmed); unknown token -> error. */
    public Set<String> select(String flagValue) {
        // A missing, blank, or bare (--artifacts with no value, which the CLI represents as "true")
        // flag means "every registered type".
        if (flagValue == null || flagValue.isBlank() || "true".equals(flagValue)) {
            return new LinkedHashSet<>(byType.keySet());
        }
        // Otherwise the value is a comma-separated subset; validate each token so a typo
        // fails loudly rather than silently producing nothing.
        Set<String> selected = new LinkedHashSet<>();
        for (String token : flagValue.split(",")) {
            String type = token.trim().toLowerCase();
            if (type.isEmpty()) {
                continue;
            }
            if (!byType.containsKey(type)) {
                throw new IllegalArgumentException("unknown artifact type '" + type + "'; valid types: " + byType.keySet());
            }
            selected.add(type);
        }
        // A value made only of separators (e.g. ",") names no type; fail loudly rather than
        // silently selecting nothing.
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("no artifact types in '" + flagValue + "'; valid types: " + byType.keySet());
        }
        return selected;
    }

    public boolean run(Set<String> selected, ArtifactContext context) {
        boolean allSucceeded = true;
        for (String type : selected) {
            if (!produceIfNeeded(type, context)) {
                allSucceeded = false;
            }
        }
        return allSucceeded;
    }

    // Returns true when the type was produced, skipped, or had nothing to record; false when a
    // failure was caught and isolated, so the caller can avoid counting a partially-failed document.
    private boolean produceIfNeeded(String type, ArtifactContext context) {
        Artifact artifact = byType.get(type);
        if (artifact == null) {
            return true;
        }
        try {
            if (isCurrent(type, artifact, context)) {
                return true;
            }
            // Write the payload first, then stamp the manifest entry complete last, so a crash
            // mid-produce leaves no "ready" entry and the next run regenerates it.
            ManifestEntry produced = artifact.produce(context);
            // A producer may return null to signal there is nothing to record for this node.
            if (produced != null) {
                store.put(context.nodeDir(), type, produced.withStatus(ManifestEntry.STATUS_COMPLETE));
            }
            return true;
        } catch (ArtifactException | IOException failure) {
            LOGGER.error("failed to produce artifact '{}' for document {}", type, context.document().getId(), failure);
            return false;
        }
    }

    // An artifact is current when a completed entry already exists and was produced with the
    // exact same task input (config + version) as this run; only then is regeneration skipped.
    private boolean isCurrent(String type, Artifact artifact, ArtifactContext context) throws IOException {
        ManifestEntry existing = store.get(context.nodeDir(), type);
        return existing != null
                && existing.isComplete()
                && existing.taskInput().equals(artifact.taskInput());
    }
}
