package org.icij.datashare.text.indexing.elasticsearch;

import java.nio.file.Path;

public class ArtifactPath {

    private ArtifactPath() {}

    /** Content-addressed directory for a digest, mirroring extract-lib's raw layout. */
    public static Path dir(Path projectRoot, String digest) {
        return projectRoot.resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest);
    }

    public static Path structureDir(Path projectRoot, String digest) {
        return dir(projectRoot, digest).resolve("structure");
    }

    public static Path structurePage(Path projectRoot, String digest, int pageNumber) {
        return structureDir(projectRoot, digest).resolve(String.format("page-%04d.md", pageNumber));
    }
}
