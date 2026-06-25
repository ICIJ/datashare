package org.icij.datashare.text.artifact;

import java.util.Map;

/** The raw/source-bytes artifact. extract-lib still writes the raw/raw.json bytes via
 *  SourceExtractor.extractEmbeddedSources; this class orchestrates that and records the entry. */
public class RawArtifact implements Artifact {
    private static final String TYPE = "raw";

    // Bump this when the raw extraction logic changes, so already-cached entries are
    // recognised as stale (skip-if-current compares the whole task input by value).
    private static final int VERSION = 1;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> taskInput() {
        return Map.of("type", TYPE, "version", VERSION);
    }

    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        // extract-lib writes the raw/raw.json bytes for the embedded subtree as a side effect;
        // we only drive it and then describe the entry. Any failure becomes an ArtifactException
        // so the registry can isolate it without aborting the other types or the ingest loop.
        try {
            context.sources().extractEmbeddedSources(context.project(), context.document());
        } catch (Exception extractionFailure) {
            throw new ArtifactException("raw extraction failed for " + context.document().getId(), extractionFailure);
        }
        return ManifestEntry.singleFile(taskInput(), context.document().getContentType(), context.document().getName());
    }
}
