package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Read side of the artifact store, mirroring {@link ArtifactProducer}. Owns every rule that
 *  involves the manifest or the on-disk layout, so serving code stays HTTP-only: what is
 *  servable, where a page lives, and what a missing payload means. */
public class ArtifactReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactReader.class);

    /** Beyond this a total is a corrupt manifest, not a long document. It matters because {@link
     *  StructureSearch} turns a total written by another producer into a loop bound, and unbounded,
     *  {@code Integer.MAX_VALUE} never terminates at all: the counter wraps to MIN_VALUE, still in range. */
    public static final int MAX_SERVABLE_PAGES = 100_000;

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

    /**
     * The page count a servable entry advertises, or null when its pages block cannot be trusted:
     * absent, renamed by a producer whose manifest shape differs (unknown properties are ignored on
     * read), non-positive because Pages.total is a primitive that an absent field fills with 0, or
     * past {@link #MAX_SERVABLE_PAGES}. Warned rather than silent: such a manifest is otherwise
     * indistinguishable from a document that has no artifact of this type at all, and the
     * strict-store contract says it is not found.
     */
    public Integer servableTotal(Path docArtifactDir, ArtifactType type, ManifestEntry entry) {
        Pages pages = entry.pages();
        if (pages != null && pages.total() > 0 && pages.total() <= MAX_SERVABLE_PAGES) {
            return pages.total();
        }
        LOGGER.warn("complete '{}' entry in {} advertises no usable page count: its pages block is "
                + "absent, renamed, or carries a total outside 1..{}", type.token(), docArtifactDir,
                MAX_SERVABLE_PAGES);
        return null;
    }

    /** One page's bytes, or null when the page is out of range or its payload is missing. */
    public byte[] page(Path docArtifactDir, ArtifactType type, ManifestEntry entry, int page, String extension) throws IOException {
        Integer total = servableTotal(docArtifactDir, type, entry);
        if (total == null || page < 1 || page > total) {
            return null;
        }
        ByteRangePagination byteRanges = byteRanges(entry);
        if (byteRanges != null) {
            return slice(ArtifactPath.payloadContent(docArtifactDir, type, extension),
                    byteRanges.ranges(), page, type, total);
        }
        Path file = ArtifactPath.payloadPage(docArtifactDir, type, page, extension);
        // Readable, not merely present: payloads are written 0600 by whichever process produced
        // them (same hazard as SourceExtractor#hasCachedEmbeddedSource), and reading one this
        // process cannot open would throw an IOException the serving side turns into a 500.
        if (!Files.isReadable(file)) {
            // In range per the manifest but absent on disk. DEBUG, not WARN: a caller walking every
            // page would turn one disagreement into one line per page, replayable by any project
            // member, so StructureSearch reports it once per scan and the page route answers 404.
            LOGGER.debug("manifest advertises {} page(s) for '{}' but {} is missing or unreadable", total, type.token(), file);
            return null;
        }
        return Files.readAllBytes(file);
    }

    /** Which extensions are actually on disk, in the candidate order given. Probes per scheme,
     *  because the two schemes keep an extension in different files. */
    public List<String> formats(Path docArtifactDir, ArtifactType type, ManifestEntry entry, Collection<String> candidates) {
        boolean byteRanges = byteRanges(entry) != null;
        return candidates.stream()
                .filter(extension -> Files.isReadable(byteRanges
                        ? ArtifactPath.payloadContent(docArtifactDir, type, extension)
                        : ArtifactPath.payloadPage(docArtifactDir, type, 1, extension)))
                .toList();
    }

    // The scheme when it is the byte-range one, else null: every other case (filesystem, or a pages
    // block with no pagination at all) is served as one file per page.
    private ByteRangePagination byteRanges(ManifestEntry entry) {
        return entry.pages() != null && entry.pages().pagination() instanceof ByteRangePagination ranges
                ? ranges : null;
    }

    // Half-open [start, end). A range outside the file means manifest and payload disagree, which
    // is a 404 for that page rather than a truncated body.
    private byte[] slice(Path content, List<long[]> ranges, int page, ArtifactType type, int total) throws IOException {
        if (!Files.isReadable(content)) {
            LOGGER.warn("manifest advertises {} byte-range page(s) for '{}' but {} is missing or unreadable", total, type.token(), content);
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
