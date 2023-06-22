package org.icij.datashare.text;

import org.icij.datashare.text.Project;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;


public class ProjectTest {

    @Test()
    public void test_constructor_with_only_name_and_default_values() {
        Project project = new Project("local-datashare");
        assertThat(project.name).isEqualTo("local-datashare");
        assertThat(project.label).isEqualTo("local-datashare");
        assertThat(project.allowFromMask).isEqualTo("*");
    }
}
