package org.icij.datashare.text.artifact;

import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.nio.file.Files;
import java.nio.file.Path;

/** Whether the payload a manifest entry advertises is still on disk. Single definition of the question,
 *  so skip-if-current, INDEX-time recording and raw's post-extraction check cannot drift apart. */
public class ArtifactPayload {
    private ArtifactPayload() {}

    /** Takes the entry stamped or not, since two of the three callers ask before
     *  {@link ManifestEntry#withTerminalStatus()} runs. {@code !exists} rather than {@code notExists}: a
     *  path the filesystem will not answer for counts as absent, because re-producing is work a later run
     *  can redo and stamping COMPLETE over an unconfirmed payload is not. */
    public static boolean isMissing(Path docArtifactDir, ArtifactType type, ManifestEntry entry) {
        // EMPTY advertises no payload of its own: a root's raw source is the on-disk original.
        if (entry.status() == ManifestEntryStatus.EMPTY) {
            return false;
        }
        // Exhaustive on purpose: a new type must decide its own shape rather than default to "present".
        return switch (type) {
            // Both or neither: SourceExtractor serves the cache only when both are readable, so a pair
            // half written by a JVM death is unservable.
            case RAW -> !Files.exists(docArtifactDir.resolve(ArtifactPath.RAW_FILE))
                    || !Files.exists(docArtifactDir.resolve(ArtifactPath.RAW_SIDECAR_FILE));
            case STRUCTURE -> !Files.exists(lastPageOrDir(docArtifactDir, entry));
        };
    }

    /** The one path that answers for the whole payload: pages land in a staging directory renamed in one
     *  move, so a partial write is impossible and a set truncated by discard()'s page-by-page delete
     *  always loses its last page. The directory instead when the entry advertises no usable count, since
     *  manifest.json is read from disk and a Python producer writes it too. */
    private static Path lastPageOrDir(Path docArtifactDir, ManifestEntry entry) {
        if (entry.pages() == null || entry.pages().total() < 1) {
            return ArtifactPath.structureDir(docArtifactDir);
        }
        return ArtifactPath.structurePage(docArtifactDir, entry.pages().total(), "md");
    }
}
