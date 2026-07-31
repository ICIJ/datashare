package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Read side of the artifact store, mirroring {@link ArtifactProducer}. Owns every rule that
 *  involves the manifest or the on-disk layout, so serving code stays HTTP-only: what is
 *  servable, where a page lives, and what a missing payload means. */
public class ArtifactReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactReader.class);
    private static final String BYTE_RANGES = "byteRanges";
    private final ManifestRepository manifests;

    public ArtifactReader(ManifestRepository manifests) {
        this.manifests = manifests;
    }

    /** The type's entry when it exists and is servable, else null. Callers map null to 404 and
     *  never inspect status themselves, so "servable" has one definition. A corrupt manifest.json
     *  (bad syntax, or an unrecognised status) is not servable either: manifest.json is written by
     *  other producers, including datashare-python, so this is the boundary where a malformed one
     *  reads as "not found" rather than propagating as a 500 to whoever asks for a serving read. */
    public ManifestEntry servableEntry(Path docArtifactDir, ArtifactType type) throws IOException {
        ManifestEntry entry;
        try {
            entry = manifests.get(docArtifactDir, type.token());
        } catch (JsonProcessingException malformed) {
            LOGGER.warn("ignoring unreadable manifest for '{}' in {}", type.token(), docArtifactDir, malformed);
            return null;
        }
        return entry != null && entry.isComplete() ? entry : null;
    }

    /** One page's bytes, or null when the page is out of range or its payload is missing. */
    public byte[] page(Path docArtifactDir, ArtifactType type, ManifestEntry entry, int page, String extension) throws IOException {
        Integer total = entry.total();
        if (total == null || page < 1 || page > total) {
            return null;
        }
        if (isByteRanges(entry)) {
            return slice(ArtifactPath.payloadContent(docArtifactDir, type, extension),
                    entry.pagination().byteRanges(), page, type, total);
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

    /** Which extensions are actually on disk, in the candidate order given. Probes per scheme,
     *  because the two schemes keep an extension in different files. */
    public List<String> formats(Path docArtifactDir, ArtifactType type, ManifestEntry entry, List<String> candidates) {
        boolean byteRanges = isByteRanges(entry);
        return candidates.stream()
                .filter(extension -> Files.isRegularFile(byteRanges
                        ? ArtifactPath.payloadContent(docArtifactDir, type, extension)
                        : ArtifactPath.payloadPage(docArtifactDir, type, 1, extension)))
                .toList();
    }

    private boolean isByteRanges(ManifestEntry entry) {
        return entry.pagination() != null && BYTE_RANGES.equals(entry.pagination().type());
    }

    // Half-open [start, end). A range outside the file means manifest and payload disagree, which
    // is a 404 for that page rather than a truncated body.
    private byte[] slice(Path content, List<long[]> ranges, int page, ArtifactType type, int total) throws IOException {
        if (!Files.isRegularFile(content)) {
            LOGGER.warn("manifest advertises {} byte-range page(s) for '{}' but {} is missing", total, type.token(), content);
            return null;
        }
        if (ranges == null || ranges.size() < page || ranges.get(page - 1).length != 2) {
            LOGGER.warn("manifest advertises {} byte-range page(s) for '{}' but range {} is malformed", total, type.token(), page);
            return null;
        }
        long[] range = ranges.get(page - 1);
        long start = range[0];
        long end = range[1];
        if (start < 0 || end < start || end > Files.size(content) || end - start > Integer.MAX_VALUE) {
            LOGGER.warn("byte range [{}, {}) for '{}' is outside {}", start, end, type.token(), content);
            return null;
        }
        byte[] slice = new byte[(int) (end - start)];
        try (RandomAccessFile file = new RandomAccessFile(content.toFile(), "r")) {
            file.seek(start);
            file.readFully(slice);   // handles the partial-read loop
        }
        return slice;
    }
}
