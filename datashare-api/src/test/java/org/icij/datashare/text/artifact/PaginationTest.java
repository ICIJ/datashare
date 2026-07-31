package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.util.List;
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
        // `ranges` is the `byteRanges` pagination shape from the storage convention (docs repo
        // document-artifacts-convention.md), which datashare-python writes from its own producers: an
        // entry it wrote must read back here with the same page count and offsets.
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

    @Test
    public void test_a_byte_ranges_entry_serializes_as_the_convention_shape() throws Exception {
        String json = mapper.writeValueAsString(ManifestEntry.paginated(Map.of("pipeline", "tika"),
                List.of(new long[]{0, 125}, new long[]{125, 400})));

        assertThat(json).contains(
                "\"pages\":{\"total\":2,\"pagination\":{\"type\":\"byteRanges\",\"ranges\":[[0,125],[125,400]]}}");
    }

    @Test
    public void test_byte_ranges_total_is_derived_from_the_range_count() {
        Map<String, Object> taskInput = Map.of("pipeline", "tika");
        assertThat(ManifestEntry.paginated(taskInput,
                List.of(new long[]{0, 1}, new long[]{1, 2}, new long[]{2, 3})).pages().total()).isEqualTo(3);
        assertThat(ManifestEntry.paginated(taskInput, List.of()).pages().total()).isEqualTo(0);
    }
}
