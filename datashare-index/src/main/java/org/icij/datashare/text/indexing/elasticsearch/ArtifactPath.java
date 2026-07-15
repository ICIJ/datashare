package org.icij.datashare.text.indexing.elasticsearch;

import java.nio.file.Path;

/** Content-addressed on-disk layout for per-document artifacts under artifactDir. */
public class ArtifactPath {
    public static final String MANIFEST_FILE = "manifest.json";
    /** On-disk filename of the raw payload, owned by extract-lib's embedded-artifact layout. Not to
     *  be confused with ArtifactType.RAW.token() (the manifest key, coincidentally also "raw"). */
    public static final String RAW_FILE = "raw";

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
}
