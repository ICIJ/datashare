package org.icij.datashare.text.artifact;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;

import java.util.Map;

import static org.icij.datashare.cli.DatashareCliOptions.OCR_OPT;

/** A derived representation of a document, produced alongside it and stored under its artifact dir.
 *  Implementations write payload files only and MUST NOT touch manifest.json. */
public interface Artifact {
    /** The declared type of this artifact, from datashare's known-types vocabulary. Its
     *  {@link ArtifactType#token()} is both the --artifacts selector token AND the manifest key. */
    ArtifactType type();

    /** The config-only fingerprint of this run (e.g. {"type":..,"version":..}) compared by value
     *  for skip-if-current. MUST NOT include data (document ids, batch, queries): the same doc
     *  processed with the same config in two different batches must compare equal. Keep it compact
     *  (mirrors datashare-python TaskArgs.as_manifest_task_input). */
    Map<String, Object> taskInput();

    /** The fingerprint to compare and to record for one document: {@link #taskInput()} unless the
     *  payload also depends on something the document carries (the OCR that was applied to it, say).
     *  Still config, not data: an override MUST return the same value for a given document and run
     *  config, so the same doc in two different batches compares equal. */
    default Map<String, Object> taskInput(Document document) {
        return taskInput();
    }

    /** Whether this run enables OCR, defaulting to on as extract-lib's --ocr does. Shared because
     *  more than one artifact renders a document with it and both have to read the option the same
     *  way: two copies would let a change to how --ocr is parsed reach one artifact and not the other,
     *  which shows up as two artifacts of the same document disagreeing about a scanned page. */
    static boolean ocrEnabled(PropertiesProvider propertiesProvider) {
        return propertiesProvider.get(OCR_OPT).map(Boolean::parseBoolean).orElse(true);
    }

    /** Produce payload files under context.docArtifactDir() and return the entry to record (without
     *  status). Return ManifestEntry.empty(taskInput()) when this node has no payload of this type
     *  (never null — a null would be re-produced on every run). */
    ManifestEntry produce(ArtifactContext context) throws ArtifactException;
}
