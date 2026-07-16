package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;

import java.nio.file.Files;
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
            // A root's source is the on-disk original, served directly and never copied here, so there is
            // no payload in this dir. Record an empty entry so the root is not reprocessed on every run.
            if (document.getExtractionLevel() <= 0) {
                return ManifestEntry.empty(taskInput());
            }
            ManifestEntry entry = ManifestEntry.singleFile(taskInput(), document.getContentType(), document.getName());
            // extractAll can return normally without ever writing THIS polled document's bytes: a
            // per-message parse failure the resilient parser swallows, a mid-walk abort, a corrupt
            // entry, an OCR-off image... Verify the raw payload actually landed before handing back
            // a terminal-able entry, so a silent miss fails loudly (nbFailed, re-runnable) instead of
            // being recorded as produced.
            if (Files.notExists(context.docArtifactDir().resolve("raw"))) {
                throw new ArtifactException("raw extraction produced no bytes for " + document.getId(), null);
            }
            return entry;
        } catch (ArtifactException noRawBytes) {
            throw noRawBytes;
        } catch (Exception extractionFailure) {
            throw new ArtifactException("raw extraction failed for " + document.getId(), extractionFailure);
        }
    }
}
