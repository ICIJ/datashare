package org.icij.datashare.text.artifact;

import java.util.Map;

/** A derived representation of a document, produced alongside it and stored under its artifact dir.
 *  Implementations write payload files only and MUST NOT touch manifest.json. */
public interface Artifact {
    /** Stable type name: the --artifacts selector token AND the manifest key. */
    String type();

    /** The config-only fingerprint of this run (e.g. {"type":..,"version":..}) compared by value
     *  for skip-if-current. MUST NOT include data (document ids, batch, queries): the same doc
     *  processed with the same config in two different batches must compare equal. Keep it compact
     *  (mirrors datashare-python TaskArgs.as_manifest_task_input). */
    Map<String, Object> taskInput();

    /** Produce payload files under context.docArtifactDir() and return the entry to record (without
     *  status). Return ManifestEntry.empty(taskInput()) when this node has no payload of this type
     *  (never null — a null would be re-produced on every run). */
    ManifestEntry produce(ArtifactContext context) throws ArtifactException;
}
