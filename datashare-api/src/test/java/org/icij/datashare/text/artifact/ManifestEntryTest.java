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
                "application/pdf", "report.pdf").withStatus(ManifestEntryStatus.COMPLETE);

        String json = mapper.writeValueAsString(entry);

        assertThat(json).contains("\"status\":\"complete\"");
        assertThat(json).contains("\"contentType\":\"application/pdf\"");
        assertThat(json).contains("\"filename\":\"report.pdf\"");
        assertThat(json).doesNotContain("pages");
        assertThat(json).doesNotContain("confidence");
        assertThat(json).doesNotContain("label");
    }

    @Test
    public void test_round_trip_preserves_task_input_for_equality() throws Exception {
        ManifestEntry entry = ManifestEntry.singleFile(Map.of("type", "raw", "version", 1),
                "application/pdf", "report.pdf").withStatus(ManifestEntryStatus.COMPLETE);

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
    public void test_with_terminal_status_stamps_complete_when_status_absent() {
        ManifestEntry entry = ManifestEntry.singleFile(Map.of("type", "raw", "version", 1), "text/plain", "a.txt");
        assertThat(entry.withTerminalStatus().status()).isEqualTo(ManifestEntryStatus.COMPLETE);
    }

    @Test
    public void test_with_terminal_status_keeps_an_already_terminal_status() {
        ManifestEntry entry = ManifestEntry.empty(Map.of("type", "raw", "version", 1));
        assertThat(entry.withTerminalStatus().status()).isEqualTo(ManifestEntryStatus.EMPTY);
    }

    @Test
    public void test_is_current_for_same_task_input() {
        ManifestEntry entry = ManifestEntry.empty(Map.of("type", "raw", "version", 1));
        assertThat(entry.isCurrentFor(Map.of("type", "raw", "version", 1))).isTrue();
    }

    @Test
    public void test_is_not_current_for_another_task_input() {
        ManifestEntry entry = ManifestEntry.empty(Map.of("type", "raw", "version", 1));
        assertThat(entry.isCurrentFor(Map.of("type", "raw", "version", 2))).isFalse();
    }

    @Test
    public void test_is_not_current_when_status_absent() {
        ManifestEntry entry = ManifestEntry.singleFile(Map.of("type", "raw", "version", 1), "text/plain", "a.txt");
        assertThat(entry.isCurrentFor(Map.of("type", "raw", "version", 1))).isFalse();
    }

    @Test
    public void test_is_not_current_when_task_input_absent() {
        ManifestEntry entry = ManifestEntry.singleFile(null, "text/plain", "a.txt").withStatus(ManifestEntryStatus.COMPLETE);
        assertThat(entry.isCurrentFor(Map.of("type", "raw", "version", 1))).isFalse();
    }

    @Test
    public void test_paginated_entry_carries_its_page_count_and_pagination() {
        ManifestEntry entry = ManifestEntry.paginated(Map.of("pipeline", "tika", "version", "3.3.0"), 12);
        assertThat(entry.pages().total()).isEqualTo(12);
        assertThat(entry.pages().pagination().type()).isEqualTo("filesystem");
        assertThat(entry.contentType()).isNull();
    }

    @Test
    public void test_an_entry_with_no_pages_object_has_no_page_attributes() throws Exception {
        assertThat(mapper.readValue("{\"status\":\"complete\"}", ManifestEntry.class).pages()).isNull();
    }
}
