package org.icij.datashare.text.artifact;

import java.io.IOException;
import java.nio.file.Path;

/** Reads/writes a document's manifest.json. Persistence-layer contract: implementations own
 *  concurrency and atomicity. Kept in datashare-api so alternative backends (e.g. a DB) can
 *  implement it without depending on datashare-index. */
public interface ManifestStore {
    ManifestEntry get(Path docArtifactDir, String type) throws IOException;
    void put(Path docArtifactDir, String type, ManifestEntry entry) throws IOException;
    // Runs `action` while holding the per-doc write lock, so payload production and the
    // manifest update are one atomic critical section (prevents manifest/FS divergence).
    <T> T inLock(Path docArtifactDir, ManifestAction<T> action) throws IOException;
    @FunctionalInterface interface ManifestAction<T> { T run() throws IOException; }
}
