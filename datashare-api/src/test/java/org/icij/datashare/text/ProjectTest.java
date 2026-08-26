package org.icij.datashare.text;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Date;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;


public class ProjectTest {

    @Test()
    public void test_constructor_with_only_name_and_default_values() {
        Project project = new Project("local-datashare");
        assertThat(project.name).isEqualTo("local-datashare");
        assertThat(project.label).isEqualTo("local-datashare");
        assertThat(project.allowFromMask).isEqualTo("*.*.*.*");
    }
    @Test()
    public void test_constructor_with_all_values() {
        Project project = new Project(
                "local-datashare",
                "Local Datashare",
                "A sample project",
                Path.of("/vault/local-datashare"),
                "https://icij.org",
                "Jane Doe",
                "ICIJ",
                null,
                "*.*.*.*",
                new Date(),
                new Date());
        assertThat(project.name).isEqualTo("local-datashare");
        assertThat(project.label).isEqualTo("Local Datashare");
        assertThat(project.description).isEqualTo("A sample project");
        assertThat(project.sourceUrl).isEqualTo("https://icij.org");
        assertThat(project.maintainerName).isEqualTo("Jane Doe");
        assertThat(project.publisherName).isEqualTo("ICIJ");
        assertThat(project.allowFromMask).isEqualTo("*.*.*.*");
    }
    @Test
    public void test_entities_index_of_a_project() {
        assertThat(Project.entitiesIndex("foo")).isEqualTo("foo.entities");
    }

    @Test
    public void test_entities_index_rejects_a_null_project() {
        try {
            Project.entitiesIndex(null);
            fail("should have rejected a null project id");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("projectId");
        }
    }
}
