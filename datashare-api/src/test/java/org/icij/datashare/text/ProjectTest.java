package org.icij.datashare.text;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Date;

import static org.fest.assertions.Assertions.assertThat;


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
}
