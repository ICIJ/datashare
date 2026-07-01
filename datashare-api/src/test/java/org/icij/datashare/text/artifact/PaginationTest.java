package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;
import java.util.List;
import static org.fest.assertions.Assertions.assertThat;

public class PaginationTest {
    private final ObjectMapper mapper = JsonObjectMapper.getMapper();

    @Test
    public void test_filesystem_pagination_omits_byte_ranges() throws Exception {
        String json = mapper.writeValueAsString(Pagination.filesystem(12));
        assertThat(json).contains("\"type\":\"filesystem\"");
        assertThat(json).contains("\"total\":12");
        assertThat(json).doesNotContain("byteRanges");
    }

    @Test
    public void test_byte_ranges_pagination_carries_ranges() {
        Pagination p = Pagination.byteRanges(2, List.of(new long[]{0, 10}, new long[]{10, 20}));
        assertThat(p.type()).isEqualTo("byteRanges");
        assertThat(p.total()).isEqualTo(2);
        assertThat(p.byteRanges()).hasSize(2);
    }
}
