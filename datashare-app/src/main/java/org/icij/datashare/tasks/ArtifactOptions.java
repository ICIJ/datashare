package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import static java.util.Optional.ofNullable;
import org.icij.datashare.text.artifact.ArtifactType;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import java.nio.file.Path;
import java.util.Optional;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;

/** Shared artifact-stage configuration for the INDEX and ARTIFACT stages, so the two stages resolve
 *  the project, the force flag, and the artifact directory the same way and cannot drift. */
public record ArtifactOptions(String projectName, String defaultProject, boolean force, List<ArtifactType> artifacts, Path artifactDir, int parallelism) {
    public ArtifactOptions(String projectName, String defaultProject, boolean force, List<ArtifactType> artifacts,
                           Path artifactDir, int parallelism) {
        this.projectName = ofNullable(projectName).orElse(ofNullable(defaultProject).orElse(DEFAULT_DEFAULT_PROJECT));
        this.defaultProject = defaultProject;
        this.force = force;
        this.artifacts = artifacts;
        this.artifactDir = artifactDir;
        this.parallelism = parallelism == 0 ? 1 : parallelism;
    }

    @JsonCreator
    public ArtifactOptions(@JsonProperty("projectName") String projectName,
                           @JsonProperty("defaultProject") String defaultProject, @JsonProperty("force") boolean force,
                           @JsonProperty("artifacts") Object artifacts, @JsonProperty("artifactDir") Path artifactDir,
                           @JsonProperty("parallelism") int parallelism) {
        this(projectName, defaultProject, force, parseArtifacts(artifacts), artifactDir, parallelism);
    }

    /** The artifact project root for a stage that opted in via --artifacts, or empty when the stage did
     *  not opt in. Throws IllegalArgumentException when --artifacts is set without --artifactDir. Call
     *  from a stage's RUN path (not its constructor): a throw here is reported as a clean task error,
     *  whereas a throw from a reflectively-constructed task becomes a requeue-forever NackException. */
    public Optional<Path> artifactProjectRoot() {
        if (!artifacts.isEmpty()) {
            Path dir = ofNullable(artifactDir()).orElseThrow(
                    () -> new IllegalArgumentException("--artifacts requires --artifactDir"));
            return Optional.of(ArtifactPath.projectRoot(dir, projectName()));
        } else {
            return Optional.empty();
        }
    }

    /** Accepts both forms the record meets: the --artifacts string from the properties, and the list of
     *  types the record itself serializes to (what Temporal hands back to the activity). */
    private static List<ArtifactType> parseArtifacts(Object artifacts) {
        if (artifacts instanceof Collection<?> types)
            return types.stream().map(type -> ArtifactType.fromToken(type.toString())).toList();
        if (artifacts == null || artifacts.toString().isBlank())
            return List.of();
        if ("true".equals(artifacts.toString()))
            return List.of(ArtifactType.values());
        return Arrays.stream(artifacts.toString().split(",")).filter(token -> !token.isBlank())
                     .map(ArtifactType::fromToken).toList();
    }
}
