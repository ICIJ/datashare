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
        if (flagValue == null || flagValue.isBlank()) {
            return new LinkedHashSet<>(byType.keySet());
        }
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
        return selected;
    }

    public void run(Set<String> selected, ArtifactContext ctx) {
        for (String type : selected) {
            Artifact artifact = byType.get(type);
            if (artifact == null) {
                continue;
            }
            try {
                ManifestEntry existing = store.get(ctx.nodeDir(), type);
                if (existing != null && existing.isComplete() && existing.taskInput().equals(artifact.taskInput())) {
                    continue; // skip-if-current
                }
                ManifestEntry produced = artifact.produce(ctx);
                store.put(ctx.nodeDir(), type, produced.withStatus("complete"));
            } catch (ArtifactException | IOException e) {
                LOGGER.error("failed to produce artifact '{}' for document {}", type, ctx.document().getId(), e);
            }
        }
    }
}
