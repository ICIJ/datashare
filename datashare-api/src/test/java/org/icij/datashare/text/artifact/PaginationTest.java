package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class PaginationTest {
    private final ObjectMapper mapper = JsonObjectMapper.getMapper();

    @Test
    public void test_a_paginated_entry_serializes_as_the_convention_shape() throws Exception {
        JsonNode entry = mapper.valueToTree(
                ManifestEntry.paginated(Map.of("pipeline", "tika"), 12).withStatus(ManifestEntryStatus.COMPLETE));

        // Page attributes belong under `pages`, never beside status and taskInput.
        assertThat(entry.get("total")).isNull();
        assertThat(entry.get("pages").get("total").asInt()).isEqualTo(12);
        assertThat(entry.get("pages").get("pagination").get("type").asText()).isEqualTo("filesystem");
        assertThat(entry.get("pages").get("pagination").has("ranges")).isFalse();
    }

    @Test
    public void test_byte_ranges_pagination_written_by_another_producer_is_readable() throws Exception {
        // The only reason ByteRangePagination exists: nothing in Java writes that scheme.
        ManifestEntry read = mapper.readValue("{\"status\":\"complete\",\"pages\":{\"total\":2,"
                + "\"pagination\":{\"type\":\"byteRanges\",\"ranges\":[[0,10],[10,20]]}}}", ManifestEntry.class);

        assertThat(read.pages().total()).isEqualTo(2);
        assertThat(read.pages().pagination()).isInstanceOf(ByteRangePagination.class);
        assertThat(((ByteRangePagination) read.pages().pagination()).ranges()).hasSize(2);
    }

    @Test
    public void test_pagination_round_trip() throws Exception {
        Pagination read = mapper.readValue(mapper.writeValueAsString(new FilesystemPagination()), Pagination.class);
        assertThat(read).isInstanceOf(FilesystemPagination.class);
        assertThat(read.type()).isEqualTo("filesystem");
    }
}
