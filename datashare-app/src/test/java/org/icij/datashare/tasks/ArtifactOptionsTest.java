package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.artifact.ArtifactType;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.fest.assertions.Assertions.assertThat;

public class ArtifactOptionsTest {
    @Test
    public void test_resolve_project_name_prefers_project_name() {
        ArtifactOptions options = new PropertiesProvider(Map.of("projectName", "bar", "defaultProject", "foo")).toRecord(ArtifactOptions.class);
        assertThat(options.projectName()).isEqualTo("bar");
    }

    @Test
    public void test_resolve_parallelism_default() {
        ArtifactOptions options = new PropertiesProvider(Map.of()).toRecord(ArtifactOptions.class);
        assertThat(options.parallelism()).isEqualTo(1);
    }

    @Test
    public void test_resolve_parallelism() {
        ArtifactOptions options = new PropertiesProvider(Map.of("parallelism", "3")).toRecord(ArtifactOptions.class);
        assertThat(options.parallelism()).isEqualTo(3);
    }

    @Test
    public void test_resolve_project_name_falls_back_to_default_project() {
        ArtifactOptions options = new PropertiesProvider(Map.of("defaultProject", "foo")).toRecord(ArtifactOptions.class);
        assertThat(options.projectName()).isEqualTo("foo");
    }

    @Test
    public void test_resolve_project_name_falls_back_to_default_default_project() {
        ArtifactOptions options = new PropertiesProvider(Map.of()).toRecord(ArtifactOptions.class);
        assertThat(options.projectName()).isEqualTo("local-datashare");
    }

    @Test
    public void test_force_absent_defaults_to_false() {
        ArtifactOptions options = new PropertiesProvider(Map.of()).toRecord(ArtifactOptions.class);
        assertThat(options.force()).isFalse();
    }

    @Test
    public void test_artifact_project_root_absent_when_artifacts_not_set() {
        ArtifactOptions options = new PropertiesProvider(Map.of()).toRecord(ArtifactOptions.class);
        assertThat(options.artifactProjectRoot()).isEqualTo(Optional.empty());
    }

    @Test
    public void test_artifact_project_root_resolved_when_artifacts_and_artifact_dir_set() {
        ArtifactOptions options = new PropertiesProvider(Map.of("artifacts", "true", "artifactDir", "/tmp/art", "projectName", "bar")).toRecord(ArtifactOptions.class);
        assertThat(options.artifactProjectRoot()).isEqualTo(Optional.of(Path.of("/tmp/art").resolve("bar")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_artifact_project_root_throws_when_artifacts_set_without_artifact_dir() {
        ArtifactOptions options = new PropertiesProvider(Map.of("artifacts", "true")).toRecord(ArtifactOptions.class);
        options.artifactProjectRoot();
    }

    @Test
    public void test_artifacts_empty_when_option_absent() {
        ArtifactOptions options = new PropertiesProvider(Map.of()).toRecord(ArtifactOptions.class);
        assertThat(options.artifacts()).isEmpty();
    }

    @Test
    public void test_artifacts_all_types_when_bare_flag() {
        ArtifactOptions options = new PropertiesProvider(Map.of("artifacts", "true")).toRecord(ArtifactOptions.class);
        assertThat(options.artifacts()).isEqualTo(List.of(ArtifactType.values()));
    }

    @Test
    public void test_artifacts_parsed_from_comma_separated_tokens() {
        ArtifactOptions options = new PropertiesProvider(Map.of("artifacts", "raw, PAGE")).toRecord(ArtifactOptions.class);
        assertThat(options.artifacts()).isEqualTo(List.of(ArtifactType.RAW, ArtifactType.PAGE));
    }
}
