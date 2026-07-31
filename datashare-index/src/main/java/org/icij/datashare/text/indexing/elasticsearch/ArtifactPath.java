package org.icij.datashare.text.indexing.elasticsearch;

import java.nio.file.Path;
import java.util.Locale;

/** Content-addressed on-disk layout for per-document artifacts under artifactDir. */
public class ArtifactPath {
    public static final String MANIFEST_FILE = "manifest.json";
    // extract-lib's EmbeddedArtifactWriter owns these names: the raw payload and its sidecar.
    public static final String RAW_FILE = "raw";
    public static final String RAW_SIDECAR_FILE = "raw.json";
    public static final String STRUCTURE_DIR = "structure";
    public static final String PAGES_DIR = "pages";

    private ArtifactPath() {}

    /** The per-project artifact root under artifactDir. Single home for the dir+project join so the
     *  INDEX stage, the ARTIFACT stage, and the source-extraction read path cannot drift. */
    public static Path projectRoot(Path artifactDir, String projectName) {
        return artifactDir.resolve(projectName);
    }

    /** Content-addressed directory for a digest, mirroring extract-lib's raw layout. */
    public static Path dir(Path projectRoot, String digest) {
        return projectRoot.resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest);
    }

    /** The per-document manifest.json path. */
    public static Path manifest(Path projectRoot, String digest) {
        return dir(projectRoot, digest).resolve(MANIFEST_FILE);
    }

    /** The structure artifact's own directory: one file per page and per format. */
    public static Path structureDir(Path docArtifactDir) {
        return docArtifactDir.resolve(STRUCTURE_DIR);
    }

    /** {@code page-%d.<extension>}, 1-based, as the convention's filesystem pagination requires. */
    public static Path structurePage(Path docArtifactDir, int page, String extension) {
        return structureDir(docArtifactDir).resolve(pageFilename(page, extension));
    }

    /** Just the filename, for a caller writing pages under a directory other than the final structure/
     *  one (the producer's atomic-swap temp directory). Unpadded, so a reader formats the name from a
     *  page number without knowing a width. {@link Locale#ROOT} so the default locale cannot decide the
     *  digits: with LANG=ar_EG.UTF-8 it would write page-١٢.md. */
    public static String pageFilename(int page, String extension) {
        return String.format(Locale.ROOT, "page-%d.%s", page, extension);
    }

    /** The page artifact's own directory. */
    public static Path pagesDir(Path docArtifactDir) {
        return docArtifactDir.resolve(PAGES_DIR);
    }

    /** The single payload file of the byte-ranges scheme, whose per-page offsets live in the
     *  manifest entry instead of in file names. The extension is fixed by the type: page is text. */
    public static Path pagesContent(Path docArtifactDir) {
        return pagesDir(docArtifactDir).resolve("content.txt");
    }
}
