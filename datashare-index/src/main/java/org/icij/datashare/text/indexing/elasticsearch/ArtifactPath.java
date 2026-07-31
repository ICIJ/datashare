package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.text.artifact.ArtifactType;
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
    public static final String PAGES_CONTENT_FILE = "content.txt";

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

    /** Payload subdirectory for a paginated type: one file per page and per format. */
    public static Path payloadDir(Path docArtifactDir, ArtifactType type) {
        return docArtifactDir.resolve(payloadDirName(type));
    }

    /** One page file of a filesystem-paginated payload, 1-based. */
    public static Path payloadPage(Path docArtifactDir, ArtifactType type, int page, String extension) {
        return payloadDir(docArtifactDir, type).resolve(pageFilename(page, extension));
    }

    /** The single file a byte-ranges-paginated payload slices pages out of. */
    public static Path payloadContent(Path docArtifactDir, ArtifactType type, String extension) {
        return payloadDir(docArtifactDir, type).resolve("content." + extension);
    }

    /** Just the filename, for a caller writing pages under a directory other than the final payload
     *  one (the producer's atomic-swap temp directory). Unpadded, so a reader formats the name from a
     *  page number without knowing a width. {@link Locale#ROOT} so the default locale cannot decide the
     *  digits: with LANG=ar_EG.UTF-8 it would write page-١٢.md. */
    public static String pageFilename(int page, String extension) {
        return String.format(Locale.ROOT, "page-%d.%s", page, extension);
    }

    private static String payloadDirName(ArtifactType type) {
        return switch (type) {
            case PAGE -> PAGES_DIR;
            case STRUCTURE -> STRUCTURE_DIR;
            // raw is a single file next to manifest.json (extract-lib owns that name), not a directory.
            case RAW -> throw new IllegalArgumentException("artifact type 'raw' has no payload directory");
        };
    }
}
