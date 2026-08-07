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
     *  repair. {@code !exists} rather than {@code notExists}: a path the filesystem will not answer for
     *  (a stale NFS handle, a revoked permission on a shared artifactDir) makes both false, and re-producing
     *  costs work a later run can redo while stamping COMPLETE over an unconfirmed payload is what leaves a
     *  document unrepairable. */
    public static boolean isMissing(Path docArtifactDir, ArtifactType type, ManifestEntry entry) {
        if (entry.status() == ManifestEntryStatus.EMPTY) {
            return false;
        }
        return switch (type) {
            // extract-lib writes raw as a single file next to manifest.json, not as a directory, and its
            // sidecar as a second atomic move after it. Both or neither: SourceExtractor only serves the
            // cache when both are readable, so a pair half written by a JVM death is unservable and must
            // be re-produced rather than recorded as present.
            // Exhaustive over the enum on purpose: a new type must decide its own shape here rather than
            // default to "present".
            case RAW -> !Files.exists(docArtifactDir.resolve(ArtifactPath.RAW_FILE))
                    || !Files.exists(docArtifactDir.resolve(ArtifactPath.RAW_SIDECAR_FILE));
            case STRUCTURE -> !Files.exists(lastPageOrDir(docArtifactDir, entry));
        };
    }

    /** The one path that answers for the whole payload: the highest-numbered page the entry advertises.
     *  Pages are written in order into a staging directory the swap renames in one move, so a partial write
     *  is impossible and a set truncated by discard()'s page-by-page delete always loses its last page, and
     *  a missing page implies a missing directory. Falls back to the directory when the entry advertises no
     *  usable page count: manifest.json is read from disk (a Python producer writes it too), so that is a
     *  state to fall back from rather than re-produce on every run forever. */
    private static Path lastPageOrDir(Path docArtifactDir, ManifestEntry entry) {
        if (entry.pages() == null || entry.pages().total() < 1) {
            return ArtifactPath.structureDir(docArtifactDir);
        }
        return ArtifactPath.structurePage(docArtifactDir, entry.pages().total(), "md");
    }
}
