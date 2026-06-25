package org.icij.datashare.text.artifact;

import java.util.Map;

/** The raw/source-bytes artifact. extract-lib still writes the raw/raw.json bytes via
 *  SourceExtractor.extractEmbeddedSources; this class orchestrates that and records the entry. */
public class RawArtifact implements Artifact {
    @Override
    public String type() {
        return "raw";
    }

    @Override
    public Map<String, Object> taskInput() {
        return Map.of("type", "raw", "version", 1);
    }

    @Override
    public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
        try {
            ctx.sources().extractEmbeddedSources(ctx.project(), ctx.document());
        } catch (Exception e) {
            throw new ArtifactException("raw extraction failed for " + ctx.document().getId(), e);
        }
        return ManifestEntry.singleFile(taskInput(), ctx.document().getContentType(), ctx.document().getName());
    }
}
