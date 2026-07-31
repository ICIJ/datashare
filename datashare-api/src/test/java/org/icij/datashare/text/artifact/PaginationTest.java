package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;
import java.util.List;
import static org.fest.assertions.Assertions.assertThat;

public class PaginationTest {
    private final ObjectMapper mapper = JsonObjectMapper.getMapper();

    @Test
    public void test_filesystem_pages_serialize_as_the_convention_shape() throws Exception {
        String json = mapper.writeValueAsString(Pages.filesystem(12));
        assertThat(json).isEqualTo("{\"total\":12,\"pagination\":{\"type\":\"filesystem\"}}");
    }

    @Test
    public void test_byte_ranges_pages_written_by_another_producer_are_readable() throws Exception {
        // The only reason `ranges` exists in the record: reading a docling-written entry. Nothing in
        // Java writes this scheme, so there is no factory for it.
        Pages read = mapper.readValue(
                "{\"total\":2,\"pagination\":{\"type\":\"byteRanges\",\"ranges\":[[0,10],[10,20]]}}", Pages.class);
        assertThat(read.total()).isEqualTo(2);
        assertThat(read.pagination().type()).isEqualTo("byteRanges");
        assertThat(read.pagination().ranges()).hasSize(2);
    }

    @Test
    public void test_pages_round_trip() throws Exception {
        Pages read = mapper.readValue(mapper.writeValueAsString(Pages.filesystem(3)), Pages.class);
        assertThat(read.total()).isEqualTo(3);
        assertThat(read.pagination().type()).isEqualTo("filesystem");
        assertThat(read.pagination().ranges()).isNull();
    }
}
