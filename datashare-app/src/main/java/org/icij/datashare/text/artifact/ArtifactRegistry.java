package org.icij.datashare.text.artifact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The app's catalog of known artifact types and the parser for the --artifacts selector.
 *  Resolves a selector to the concrete artifacts to run, so unknown-type errors surface in the
 *  app rather than inside the produce loop. */
public class ArtifactRegistry {
    private final Map<String, Artifact> byType = new LinkedHashMap<>();

    public ArtifactRegistry(List<Artifact> catalog) {
        for (Artifact artifact : catalog) {
            byType.put(artifact.type(), artifact);
        }
    }

    public List<Artifact> select(String flagValue) {
        if (flagValue == null || flagValue.isBlank() || "true".equals(flagValue)) {
            return new ArrayList<>(byType.values());
        }
        List<Artifact> selected = new ArrayList<>();
        for (String token : flagValue.split(",")) {
            String type = token.trim().toLowerCase();
            if (type.isEmpty()) {
                continue;
            }
            Artifact artifact = byType.get(type);
            if (artifact == null) {
                throw new IllegalArgumentException("unknown artifact type '" + type + "'; valid types: " + byType.keySet());
            }
            selected.add(artifact);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("no artifact types in '" + flagValue + "'; valid types: " + byType.keySet());
        }
        return selected;
    }
}
