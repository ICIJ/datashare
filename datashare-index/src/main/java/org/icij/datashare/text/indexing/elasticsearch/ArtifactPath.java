package org.icij.datashare.text.indexing.elasticsearch;

import java.nio.file.Path;

/** Content-addressed on-disk layout for per-document artifacts under artifactDir. */
public class ArtifactPath {
    public static final String MANIFEST_FILE = "manifest.json";

    private ArtifactPath() {}

    /** Content-addressed directory for a digest, mirroring extract-lib's raw layout. */
    public static Path dir(Path projectRoot, String digest) {
        return projectRoot.resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest);
    }

    /** The per-document manifest.json path. */
    public static Path manifest(Path projectRoot, String digest) {
        return dir(projectRoot, digest).resolve(MANIFEST_FILE);
    }
}
