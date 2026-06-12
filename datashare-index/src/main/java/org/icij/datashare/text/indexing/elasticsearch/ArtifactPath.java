package org.icij.datashare.text.indexing.elasticsearch;

import java.nio.file.Path;

public class ArtifactPath {
    public static final String STRUCTURE_MARKDOWN = "structure.md";

    private ArtifactPath() {}

    /** Content-addressed directory for a digest, mirroring extract-lib's raw layout. */
    public static Path dir(Path projectRoot, String digest) {
        return projectRoot.resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest);
    }

    public static Path structureMarkdown(Path projectRoot, String digest) {
        return dir(projectRoot, digest).resolve(STRUCTURE_MARKDOWN);
    }
}
