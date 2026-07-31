package org.icij.datashare.text.artifact;

import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read side of the artifact store, mirroring {@link ArtifactProducer}. Owns every rule that
 *  involves the manifest or the on-disk layout, so serving code stays HTTP-only: what is
 *  servable, where a page lives, and what a missing payload means. */
public class ArtifactReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactReader.class);
    private final ManifestRepository manifests;

    public ArtifactReader(ManifestRepository manifests) {
        this.manifests = manifests;
    }

    /** The type's entry when it exists and is servable, else null. Callers map null to 404 and
     *  never inspect status themselves, so "servable" has one definition. */
    public ManifestEntry servableEntry(Path docArtifactDir, ArtifactType type) throws IOException {
        ManifestEntry entry = manifests.get(docArtifactDir, type.token());
        return entry != null && entry.isComplete() ? entry : null;
    }

    /** One page's bytes, or null when the page is out of range or its payload is missing. */
    public byte[] page(Path docArtifactDir, ArtifactType type, ManifestEntry entry, int page, String extension) throws IOException {
        Integer total = entry.total();
        if (total == null || page < 1 || page > total) {
            return null;
        }
        Path file = ArtifactPath.payloadPage(docArtifactDir, type, page, extension);
        if (!Files.isRegularFile(file)) {
            // In range per the manifest but absent on disk: the two disagree, which is worth
            // seeing in the logs rather than reshaping the advertised page count silently.
            LOGGER.warn("manifest advertises {} page(s) for '{}' but {} is missing", total, type.token(), file);
            return null;
        }
        return Files.readAllBytes(file);
    }
}
