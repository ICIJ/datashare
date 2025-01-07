package org.icij.datashare.batch;

import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class WebQueryPaginationTest  {
    @Test
    public void test_from_empty_map() {
        assertThat(WebQueryPagination.fromMap(Map.of())).isEqualTo(new WebQueryPagination("name", "asc", 0, Integer.MAX_VALUE));
    }

    @Test
    public void test_from_map() {
        assertThat(WebQueryPagination.fromMap(Map.of("sort", "field"))).isEqualTo(new WebQueryPagination("field", "asc", 0, Integer.MAX_VALUE));
        assertThat(WebQueryPagination.fromMap(Map.of("order", "desc"))).isEqualTo(new WebQueryPagination(null, "desc", 0, Integer.MAX_VALUE));
        assertThat(WebQueryPagination.fromMap(Map.of("from", 5))).isEqualTo(new WebQueryPagination(null, null, 5, Integer.MAX_VALUE));
        assertThat(WebQueryPagination.fromMap(Map.of("size", 10))).isEqualTo(new WebQueryPagination(null, null, 0, 10));
    }

    @Test
    public void test_from_map_string_values() {
        assertThat(WebQueryPagination.fromMap(Map.of("from", "5"))).isEqualTo(new WebQueryPagination(null, null, 5, Integer.MAX_VALUE));
        assertThat(WebQueryPagination.fromMap(Map.of("size", "10"))).isEqualTo(new WebQueryPagination(null, null, 0, 10));
    }
}