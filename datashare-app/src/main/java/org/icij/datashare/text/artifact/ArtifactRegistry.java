package org.icij.datashare.text.artifact;

import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.icij.datashare.cli.OcrStrategy;
import org.icij.datashare.tasks.ArtifactOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
     *  cached. Takes the index options because page builds an extractor from them (OCR in
     *  particular) and structure records the OCR settings; raw needs none.
     *  <p>
     *  Every catalog type runs when no selector is given, structure included. A deployment that also
     *  runs datashare-python's structure producer shares the manifest key and the structure/ directory
     *  with it, and the two task inputs can never match, so each run destroys the other's payload:
     *  such a deployment has to pin ownership with an explicit {@code --artifacts raw}. page has no
     *  such rival producer: the pages/ payload is Java's alone. */
    public static ArtifactRegistry withDefaults(IndexOptions indexOptions) {
        boolean ocr = indexOptions.ocr() == null || indexOptions.ocr();
        return new ArtifactRegistry(List.of(new RawArtifact(),
                new StructureArtifact(ocr, ocrStrategy(indexOptions)),
                new PageArtifact(ocr, indexOptions.extractorOptions())));
    }

    // Read as the CLI's own OcrStrategy, so this accepts exactly what --ocrStrategy accepts. Unset or
    // unparseable means NO_OCR, as extract-lib resolves it: a scanned PDF renders empty here exactly
    // when the INDEX stage extracted nothing from it either.
    private static OcrStrategy ocrStrategy(IndexOptions indexOptions) {
        try {
            return OcrStrategy.valueOf(
                    Optional.ofNullable(indexOptions.ocrStrategy()).orElse("NO_OCR").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return OcrStrategy.NO_OCR;
        }
    }

    /** The artifacts for already-parsed types, as {@link ArtifactOptions#artifacts()} holds them. */
    public List<Artifact> select(List<ArtifactType> types) {
        return types.stream().map(type -> {
            Artifact artifact = byType.get(type);
            if (artifact == null) {
                throw new IllegalArgumentException(
                        "no producer registered for artifact type '" + type.token() + "'; available: " + tokens());
            }
            return artifact;
        }).toList();
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
                throw new IllegalArgumentException(
                        "no producer registered for artifact type '" + type.token() + "'; available: " + tokens());
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
