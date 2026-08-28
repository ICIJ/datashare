package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Records the raw manifest entry for a document indexed during the INDEX stage, without
 *  re-extracting: extract-lib already wrote the raw bytes during the parse, so this only writes
 *  or updates manifest.json. It produces the same entry the ARTIFACT stage would, because both go
 *  through {@link RawArtifact#entryFor} and {@link ManifestEntry#withTerminalStatus}.
 *
 *  <p>INDEX-time recording only records artifact types whose payload extract-lib materializes
 *  during the streaming parse, which today is {@link ArtifactType#RAW}. Any other selected type is
 *  produced by the ARTIFACT stage, not here. */
public class ManifestRecorder {
    private final ManifestRepository repository;
    private final Path projectRoot;
    private final boolean force;
    private final boolean rawSelected;
    private final String taskId;
    private final RawArtifact raw = new RawArtifact();

    public ManifestRecorder(ManifestRepository repository, Path projectRoot, List<Artifact> selected, boolean force, String taskId) {
        this.repository = repository;
        this.projectRoot = projectRoot;
        this.force = force;
        this.taskId = taskId;
        this.rawSelected = selected.stream().anyMatch(artifact -> artifact.type() == ArtifactType.RAW);
    }

    /** Record the raw entry for a document written during indexing. No-op when raw was not among the
     *  selected types, or when a matching terminal entry already exists (unless force). */
    public void record(Document document) throws IOException {
        if (!rawSelected) {
            return;
        }
        Path docArtifactDir = ArtifactPath.dir(projectRoot, document.getId());
        ManifestEntry entry = raw.entryFor(document);
        // Before skip-if-current, as in ArtifactProducer.isCurrent: nothing is current until its payload is
        // confirmed. Skipping here leaves the document to a later ARTIFACT run rather than recording a
        // permanent false-COMPLETE.
        if (ArtifactPayload.isMissing(docArtifactDir, ArtifactType.RAW, entry)) {
            return;
        }
        if (!force) {
            ManifestEntry existing = repository.get(docArtifactDir, ArtifactType.RAW.token());
            if (existing != null && existing.isCurrentFor(raw.taskInput())) {
                return;
            }
        }
        repository.put(docArtifactDir, ArtifactType.RAW.token(), entry.withTerminalStatus().withTaskId(taskId));
    }
}
