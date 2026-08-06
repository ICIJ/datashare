package org.icij.datashare.text.artifact;

import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.nio.file.Files;
import java.nio.file.Path;

/** Whether the payload a manifest entry advertises is still on disk. The single definition of that
 *  question: skip-if-current, INDEX-time recording and raw's post-extraction verification all ask it
 *  here, so none of the three can drift from the others. */
public class ArtifactPayload {
    private ArtifactPayload() {}

    /** True when the entry advertises a payload that is not there. Takes the entry as it is recorded,
     *  stamped or not, because two of the three callers ask before {@link ManifestEntry#withTerminalStatus()}
     *  runs. An EMPTY entry advertises no payload of its own (a root document's raw source is the on-disk
     *  original, and unreadable content has nothing to write), so there is nothing to check and nothing to
     *  repair. */
    public static boolean isMissing(Path docArtifactDir, ArtifactType type, ManifestEntry entry) {
        if (entry.status() == ManifestEntryStatus.EMPTY) {
            return false;
        }
        return switch (type) {
            // extract-lib writes raw as a single file next to manifest.json, not as a directory, and its
            // sidecar as a second atomic move after it. Both or neither: SourceExtractor only serves the
            // cache when both are readable, so a pair half written by a JVM death is unservable and must
            // be re-produced rather than recorded as present.
            case RAW -> Files.notExists(docArtifactDir.resolve(ArtifactPath.RAW_FILE))
                    || Files.notExists(docArtifactDir.resolve(ArtifactPath.RAW_SIDECAR_FILE));
            // The directory and the last page it advertises. The swap renames a fully written directory
            // into place, so a partial write is impossible, but a partial delete is not: discard() removes
            // pages one by one and only warns when it stops half way. Exhaustive over the enum on purpose:
            // a new type must decide its own shape here rather than default to "present".
            case STRUCTURE -> Files.notExists(ArtifactPath.structureDir(docArtifactDir))
                    || lastPageMissing(docArtifactDir, entry);
        };
    }

    /** Whether the highest-numbered page the entry advertises is gone. Only the last one: pages are
     *  written in order into a staging directory the swap renames in one move, so a set truncated by a
     *  failed delete always loses its last page. An entry that advertises no usable page count says
     *  nothing to check, and manifest.json is read from disk (a Python producer writes it too), so that
     *  is a state to fall back from rather than re-produce on every run forever. */
    private static boolean lastPageMissing(Path docArtifactDir, ManifestEntry entry) {
        if (entry.pages() == null || entry.pages().total() < 1) {
            return false;
        }
        return Files.notExists(ArtifactPath.structurePage(docArtifactDir, entry.pages().total(), "md"));
    }
}
