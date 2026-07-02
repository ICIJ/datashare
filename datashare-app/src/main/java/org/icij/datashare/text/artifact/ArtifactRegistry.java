package org.icij.datashare.text.artifact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The app's catalog of known artifact types and the parser for the --artifacts selector.
 *  Resolves a selector to the concrete artifacts to run, so unknown-type errors surface in the
 *  app rather than inside the produce loop. */
public class ArtifactRegistry {
    private final Map<ArtifactType, Artifact> byType = new LinkedHashMap<>();

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
            if (token.trim().isEmpty()) {
                continue;
            }
            // fromToken rejects tokens that are not a known type at all; a token that IS a known
            // type but has no producer wired in this process (e.g. produced by a Python worker)
            // is rejected here, since you cannot ask this process to produce it.
            ArtifactType type = ArtifactType.fromToken(token);
            Artifact artifact = byType.get(type);
            if (artifact == null) {
                throw new IllegalArgumentException("no producer registered for artifact type '" + type.token() + "'; available: " + tokens());
            }
            selected.add(artifact);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("no artifact types in '" + flagValue + "'; available: " + tokens());
        }
        return selected;
    }

    private String tokens() {
        return byType.keySet().stream().map(ArtifactType::token).collect(Collectors.joining(", "));
    }
}
