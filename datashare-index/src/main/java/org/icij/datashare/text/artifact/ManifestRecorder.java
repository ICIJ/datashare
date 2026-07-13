package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Records the raw manifest entry for a document indexed during the INDEX stage, without
 *  re-extracting: extract-lib already wrote the raw bytes during the parse, so this only writes
 *  or updates manifest.json. It produces the same entry the ARTIFACT stage would, because both go
 *  through {@link RawArtifact#entryFor} and {@link ArtifactProducer#stampTerminal}. */
public class ManifestRecorder {
    private final ManifestRepository repository;
    private final Path projectRoot;
    private final boolean force;
    private final boolean rawSelected;
    private final RawArtifact raw = new RawArtifact();

    public ManifestRecorder(ManifestRepository repository, Path projectRoot, List<Artifact> selected, boolean force) {
        this.repository = repository;
        this.projectRoot = projectRoot;
        this.force = force;
        this.rawSelected = selected.stream().anyMatch(artifact -> artifact.type() == ArtifactType.RAW);
    }

    /** Record the raw entry for a document written during indexing. No-op when raw was not among the
     *  selected types, or when a matching terminal entry already exists (unless force). */
    public void record(Document document) throws IOException {
        if (!rawSelected) {
            return;
        }
        Path docArtifactDir = ArtifactPath.dir(projectRoot, document.getId());
        if (!force) {
            ManifestEntry existing = repository.get(docArtifactDir, ArtifactType.RAW.token());
            if (existing != null && existing.isTerminal() && existing.taskInput().equals(raw.taskInput())) {
                return;
            }
        }
        repository.put(docArtifactDir, ArtifactType.RAW.token(), ArtifactProducer.stampTerminal(raw.entryFor(document)));
    }
}
