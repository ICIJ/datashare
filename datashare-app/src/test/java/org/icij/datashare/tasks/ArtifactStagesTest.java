package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.fest.assertions.Assertions.assertThat;

public class ArtifactStagesTest {
    @Test
    public void test_resolve_project_name_prefers_project_name() {
        PropertiesProvider properties = new PropertiesProvider(Map.of("projectName", "bar", "defaultProject", "foo"));
        assertThat(ArtifactStages.resolveProjectName(properties)).isEqualTo("bar");
    }

    @Test
    public void test_resolve_project_name_falls_back_to_default_project() {
        PropertiesProvider properties = new PropertiesProvider(Map.of("defaultProject", "foo"));
        assertThat(ArtifactStages.resolveProjectName(properties)).isEqualTo("foo");
    }

    @Test
    public void test_resolve_project_name_falls_back_to_default_default_project() {
        PropertiesProvider properties = new PropertiesProvider(Map.of());
        assertThat(ArtifactStages.resolveProjectName(properties)).isEqualTo("local-datashare");
    }

    @Test
    public void test_force_absent_defaults_to_false() {
        PropertiesProvider properties = new PropertiesProvider(Map.of());
        assertThat(ArtifactStages.force(properties)).isFalse();
    }

    @Test
    public void test_artifact_project_root_absent_when_artifacts_not_set() {
        PropertiesProvider properties = new PropertiesProvider(Map.of());
        assertThat(ArtifactStages.artifactProjectRoot(properties)).isEqualTo(Optional.empty());
    }

    @Test
    public void test_artifact_project_root_resolved_when_artifacts_and_artifact_dir_set() {
        PropertiesProvider properties = new PropertiesProvider(Map.of("artifacts", "true", "artifactDir", "/tmp/art", "projectName", "bar"));
        assertThat(ArtifactStages.artifactProjectRoot(properties)).isEqualTo(Optional.of(Path.of("/tmp/art").resolve("bar")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_artifact_project_root_throws_when_artifacts_set_without_artifact_dir() {
        PropertiesProvider properties = new PropertiesProvider(Map.of("artifacts", "true"));
        ArtifactStages.artifactProjectRoot(properties);
    }
}
