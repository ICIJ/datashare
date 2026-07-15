package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.nio.file.Path;
import java.util.Optional;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_FORCE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;

/** Shared artifact-stage configuration for the INDEX and ARTIFACT stages, so the two stages resolve
 *  the project, the force flag, and the artifact directory the same way and cannot drift. */
public final class ArtifactStages {
    private ArtifactStages() {}

    /** Resolve the project name the same way ElasticsearchSpewer.configure resolves the ES index
     *  name: prefer the task-level projectName, then defaultProject, then the built-in default. Keeps
     *  the manifest dir, the embedded raw bytes, and the ES index under one project name. */
    public static String resolveProjectName(PropertiesProvider properties) {
        return properties.get("projectName")
                .orElse(properties.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
    }

    /** Whether artifacts should be reprocessed even when an up-to-date manifest entry exists. */
    public static boolean force(PropertiesProvider properties) {
        return Boolean.parseBoolean(properties.get(ARTIFACTS_FORCE_OPT).orElse("false"));
    }

    /** The artifact project root for a stage that opted in via --artifacts, or empty when the stage did
     *  not opt in. Throws IllegalArgumentException when --artifacts is set without --artifactDir. Call
     *  from a stage's RUN path (not its constructor): a throw here is reported as a clean task error,
     *  whereas a throw from a reflectively-constructed task becomes a requeue-forever NackException. */
    public static Optional<Path> artifactProjectRoot(PropertiesProvider properties) {
        if (properties.get(ARTIFACTS_OPT).isEmpty()) {
            return Optional.empty();
        }
        String dir = properties.get(ARTIFACT_DIR_OPT)
                .orElseThrow(() -> new IllegalArgumentException("--artifacts requires --artifactDir"));
        return Optional.of(ArtifactPath.projectRoot(Path.of(dir), resolveProjectName(properties)));
    }
}
