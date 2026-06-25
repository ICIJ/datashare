package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;

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
        Document document = context.document();
        try {
            // extract-lib writes the raw/raw.json bytes for the embedded subtree as a side effect.
            context.sources().extractEmbeddedSources(context.project(), document);
            // Only embedded nodes have their source bytes cached in their own node dir; a root
            // document's source is the original on-disk file, served directly and never copied here.
            // So a root records no raw entry — a raw entry must always mean a payload in this dir.
            if (document.getExtractionLevel() <= 0) {
                return null;
            }
            return ManifestEntry.singleFile(taskInput(), document.getContentType(), document.getName());
        } catch (Exception extractionFailure) {
            throw new ArtifactException("raw extraction failed for " + document.getId(), extractionFailure);
        }
    }
}
