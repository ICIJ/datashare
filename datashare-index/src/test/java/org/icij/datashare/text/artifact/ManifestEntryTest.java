package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class ManifestEntryTest {
    private final ObjectMapper mapper = JsonObjectMapper.getMapper();

    @Test
    public void test_single_file_entry_serializes_without_null_fields() throws Exception {
        ManifestEntry entry = ManifestEntry.singleFile(Map.of("type", "raw", "version", 1),
                "application/pdf", "report.pdf").withStatus("complete");

        String json = mapper.writeValueAsString(entry);

        assertThat(json).contains("\"status\":\"complete\"");
        assertThat(json).contains("\"contentType\":\"application/pdf\"");
        assertThat(json).contains("\"filename\":\"report.pdf\"");
        assertThat(json).doesNotContain("total");
        assertThat(json).doesNotContain("pagination");
        assertThat(json).doesNotContain("confidence");
    }

    @Test
    public void test_round_trip_preserves_task_input_for_equality() throws Exception {
        ManifestEntry entry = ManifestEntry.singleFile(Map.of("type", "raw", "version", 1),
                "application/pdf", "report.pdf").withStatus("complete");

        ManifestEntry read = mapper.readValue(mapper.writeValueAsString(entry), ManifestEntry.class);

        assertThat(read.taskInput()).isEqualTo(Map.of("type", "raw", "version", 1));
        assertThat(read.isComplete()).isTrue();
    }

    @Test
    public void test_is_complete_false_when_status_absent() {
        ManifestEntry entry = ManifestEntry.singleFile(Map.of("type", "raw", "version", 1), "text/plain", "a.txt");
        assertThat(entry.isComplete()).isFalse();
    }

    @Test
    public void test_paginated_entry_carries_total_and_pagination() {
        ManifestEntry entry = ManifestEntry.paginated(Map.of("type", "structure", "version", 1),
                12, Map.of("type", "filesystem"));
        assertThat(entry.total()).isEqualTo(12);
        assertThat(entry.pagination()).isEqualTo(Map.of("type", "filesystem"));
        assertThat(entry.contentType()).isNull();
    }
}
