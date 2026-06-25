package org.icij.datashare.text.artifact;

import java.util.Map;

/** A derived representation of a document, produced alongside it and stored under artifactDir.
 *  Implementations write payload files only and MUST NOT touch manifest.json. */
public interface Artifact {
    /** Stable type name: the --artifacts selector token AND the manifest key. */
    String type();

    /** This run's config + an explicit version; compared by value for skip-if-current. */
    Map<String, Object> taskInput();

    /** Produce payload files under context.nodeDir() and return the entry to record (without status),
     *  or null if there is nothing to record for this node (e.g. a root document with no cached payload). */
    ManifestEntry produce(ArtifactContext context) throws ArtifactException;
}
