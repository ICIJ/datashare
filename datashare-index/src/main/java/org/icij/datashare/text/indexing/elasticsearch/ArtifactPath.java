package org.icij.datashare.text.indexing.elasticsearch;

import java.nio.file.Path;

public class ArtifactPath {

    public static final String STRUCTURE_COMPLETE_MARKER = ".complete";
    public static final String STRUCTURE_XHTML_FILE = "structure.xhtml";

    private ArtifactPath() {}

    /** Content-addressed directory for a digest, mirroring extract-lib's raw layout. */
    public static Path dir(Path projectRoot, String digest) {
        return projectRoot.resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest);
    }

    public static Path structureDir(Path projectRoot, String digest) {
        return dir(projectRoot, digest).resolve("structure");
    }

    public static Path structureXhtml(Path projectRoot, String digest) {
        return structureDir(projectRoot, digest).resolve(STRUCTURE_XHTML_FILE);
    }

    public static String pageFileName(int pageNumber) {
        return String.format("page-%04d.md", pageNumber);
    }

    public static Path structurePage(Path projectRoot, String digest, int pageNumber) {
        return structureDir(projectRoot, digest).resolve(pageFileName(pageNumber));
    }

    public static Path structureComplete(Path projectRoot, String digest) {
        return structureDir(projectRoot, digest).resolve(STRUCTURE_COMPLETE_MARKER);
    }
}
