package org.icij.datashare.text.artifact;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class PageArtifactTest {
    @Test
    public void test_type_is_page() {
        assertThat(new PageArtifact(new PropertiesProvider()).type()).isEqualTo(ArtifactType.PAGE);
    }

    @Test
    public void test_task_input_is_the_tika_pipeline_its_version_and_the_run_ocr_setting() {
        Map<String, Object> taskInput = new PageArtifact(new PropertiesProvider()).taskInput();

        assertThat(taskInput.get("pipeline")).isEqualTo("tika");
        assertThat(taskInput.get("ocr")).isEqualTo(true);
        String version = (String) taskInput.get("version");
        assertThat(version).excludes("Apache");
        assertThat(version.split("\\.").length).isEqualTo(3);
    }

    @Test
    public void test_task_input_records_ocr_off_when_the_run_disabled_it() {
        PropertiesProvider properties = new PropertiesProvider(Map.of("ocr", "false"));

        assertThat(new PageArtifact(properties).taskInput().get("ocr")).isEqualTo(false);
        assertThat(new PageArtifact(properties).taskInput())
                .isNotEqualTo(new PageArtifact(new PropertiesProvider()).taskInput());
    }
}
