package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class PaginationTest {
    private final ObjectMapper mapper = JsonObjectMapper.getMapper();

    @Test
    public void test_a_paginated_entry_serializes_as_the_convention_shape() throws Exception {
        String json = mapper.writeValueAsString(
                ManifestEntry.paginated(Map.of("pipeline", "tika"), 12).withStatus(ManifestEntryStatus.COMPLETE));

        // total and pagination live in the entry itself, beside status and taskInput.
        assertThat(json).contains("\"total\":12");
        assertThat(json).contains("\"pagination\":{\"type\":\"filesystem\"}");
        assertThat(json).doesNotContain("ranges");
    }

    @Test
    public void test_byte_ranges_pagination_written_by_another_producer_is_readable() throws Exception {
        // The only reason `ranges` exists in the record: nothing in Java writes that scheme.
        ManifestEntry read = mapper.readValue("{\"status\":\"complete\",\"total\":2,"
                + "\"pagination\":{\"type\":\"byteRanges\",\"ranges\":[[0,10],[10,20]]}}", ManifestEntry.class);

        assertThat(read.total()).isEqualTo(2);
        assertThat(read.pagination().type()).isEqualTo("byteRanges");
        assertThat(read.pagination().ranges()).hasSize(2);
    }

    @Test
    public void test_pagination_round_trip() throws Exception {
        Pagination read = mapper.readValue(mapper.writeValueAsString(Pagination.filesystem()), Pagination.class);
        assertThat(read.type()).isEqualTo("filesystem");
        assertThat(read.ranges()).isNull();
    }
}
