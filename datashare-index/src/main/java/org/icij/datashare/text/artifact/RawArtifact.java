package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;

import java.util.Map;

/** The raw/source-bytes artifact. extract-lib still writes the raw/raw.json bytes via
 *  SourceExtractor.extractEmbeddedSources; this class orchestrates that and records the entry. */
public class RawArtifact implements Artifact {
    private static final ArtifactType TYPE = ArtifactType.RAW;

    // Bump this when the raw extraction logic changes, so already-cached entries are
    // recognised as stale (skip-if-current compares the whole task input by value).
    private static final int VERSION = 1;

    @Override
    public ArtifactType type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> taskInput() {
        return TYPE.taskInput(VERSION);
    }

    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        Document document = context.document();
        try {
            // extract-lib writes the raw/raw.json bytes for the embedded subtree as a side effect.
            context.sources().extractEmbeddedSources(context.project(), document);
            return entryFor(document);
        } catch (Exception extractionFailure) {
            throw new ArtifactException("raw extraction failed for " + document.getId(), extractionFailure);
        }
    }

    /** Build the raw manifest entry for an already-extracted document, without touching the
     *  filesystem. Shared by produce() and the INDEX-time ManifestRecorder so both stages emit
     *  the same entry. A root's source is the on-disk original (no payload here), so it records
     *  an empty entry; an embedded node records its single-file payload. */
    public ManifestEntry entryFor(Document document) {
        if (document.getExtractionLevel() <= 0) {
            return ManifestEntry.empty(taskInput());
        }
        return ManifestEntry.singleFile(taskInput(), document.getContentType(), document.getName());
    }
}
