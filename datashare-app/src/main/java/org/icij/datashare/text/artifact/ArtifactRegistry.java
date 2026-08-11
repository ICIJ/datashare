package org.icij.datashare.text.artifact;

import org.icij.datashare.PropertiesProvider;

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

    /** The app's catalog of Java-produced artifact types, shared by the INDEX and ARTIFACT stages so a
     *  newly registered producer cannot be honored by one and missed by the other. Order matters: raw
     *  first, so structure and page on an embedded document read bytes raw's production has already
     *  cached. Takes the properties because page builds an extractor from the run's options (OCR in
     *  particular); raw and structure need none.
     *  <p>
     *  Every catalog type runs when no selector is given, structure included. A deployment that also
     *  runs datashare-python's structure producer shares the manifest key and the structure/ directory
     *  with it, and the two task inputs can never match, so each run destroys the other's payload:
     *  such a deployment has to pin ownership with an explicit {@code --artifacts raw}. page has no
     *  such rival producer: the pages/ payload is Java's alone. */
    public static ArtifactRegistry withDefaults(PropertiesProvider propertiesProvider) {
        return new ArtifactRegistry(
                List.of(new RawArtifact(), new StructureArtifact(propertiesProvider), new PageArtifact(propertiesProvider)));
    }

    public List<Artifact> select(String flagValue) {
        if (flagValue == null || flagValue.isBlank() || "true".equals(flagValue)) {
            return List.copyOf(byType.values());
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
