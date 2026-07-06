package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class PipelineTaskTest {
    @Test
    public void test_isPoison_path() {
        assertThat(PipelineTask.isPoison(Paths.get("POISON"))).isTrue();
        assertThat(PipelineTask.isPoison(Paths.get("/real/doc.txt"))).isFalse();
        assertThat(PipelineTask.isPoison((Path) null)).isFalse();
    }

    @Test
    public void test_isPoison_string() {
        assertThat(PipelineTask.isPoison("POISON")).isTrue();
        assertThat(PipelineTask.isPoison("realId")).isFalse();
        assertThat(PipelineTask.isPoison((String) null)).isFalse();
    }

    @Test
    public void test_pipeline_queue_poll_defaults_to_120s() {
        assertThat(PipelineTask.pipelineQueuePoll(new PropertiesProvider()))
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    public void test_pipeline_queue_poll_honors_explicit_value() {
        PropertiesProvider pp = new PropertiesProvider(Map.of("queuePoll", "5s"));
        assertThat(PipelineTask.pipelineQueuePoll(pp)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    public void test_pipeline_queue_poll_honors_explicit_zero() {
        PropertiesProvider pp = new PropertiesProvider(Map.of("queuePoll", "0"));
        assertThat(PipelineTask.pipelineQueuePoll(pp)).isEqualTo(Duration.ZERO);
    }
}
