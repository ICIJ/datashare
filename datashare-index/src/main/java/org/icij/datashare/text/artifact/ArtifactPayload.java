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
            // extract-lib writes raw as a single file next to manifest.json, not as a directory.
            case RAW -> Files.notExists(docArtifactDir.resolve(ArtifactPath.RAW_FILE));
            // The directory, not its contents: the atomic swap renames a fully written one into place, so
            // it is either there with its pages or not there at all. Exhaustive over the enum on purpose:
            // a new type must decide its own shape here rather than default to "present".
            case STRUCTURE -> Files.notExists(ArtifactPath.structureDir(docArtifactDir));
        };
    }
}
