package org.icij.datashare.tabular;

import static org.junit.Assert.fail;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class JsonRowSourceTest {
    private final JsonRowSource source = new JsonRowSource();

    private List<Row> read(String content) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
             Stream<Row> rows = source.rows(stream, RowSourceOptions.defaults())) {
            return rows.toList();
        }
    }

    @Test
    public void test_supports_json_content_types() {
        assertThat(source.supports("application/json")).isTrue();
        assertThat(source.supports(JsonRowSource.NDJSON_CONTENT_TYPE)).isTrue();
        assertThat(source.supports("text/csv")).isFalse();
    }

    @Test
    public void test_reads_an_array_of_objects() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"name\":\"ACME\"},{\"id\":2,\"name\":\"Globex\"}]");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).number()).isEqualTo(1L);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
        assertThat(rows.get(1).values().get("id")).isEqualTo("2");
    }

    @Test
    public void test_reads_line_delimited_objects() throws Exception {
        List<Row> rows = read("{\"id\":1,\"name\":\"ACME\"}\n{\"id\":2,\"name\":\"Globex\"}\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).values().get("name")).isEqualTo("Globex");
    }

    @Test
    public void test_flattens_nested_objects_to_dotted_columns() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"addr\":{\"city\":\"Paris\",\"zip\":\"75002\"}}]");

        assertThat(rows.get(0).values().get("addr.city")).isEqualTo("Paris");
        assertThat(rows.get(0).values().get("addr.zip")).isEqualTo("75002");
        assertThat(rows.get(0).values().get("addr")).isNull();
    }

    @Test
    public void test_an_array_value_is_refused_naming_its_row_and_column() throws Exception {
        Map<String, String> columnByContent = Map.of(
                "[{\"id\":1,\"tags\":[\"a\",\"b\"]}]", "tags",
                "[{\"id\":1,\"kids\":[{\"n\":1}]}]", "kids",
                "[{\"id\":1,\"addr\":{\"lines\":[]}}]", "addr.lines");
        for (Map.Entry<String, String> arrayValue : columnByContent.entrySet()) {
            try {
                read(arrayValue.getKey());
                fail("an array has no tabular representation: joining or skipping it loses data");
            } catch (IllegalArgumentException failure) {
                assertThat(failure.getMessage()).contains("row 1");
                assertThat(failure.getMessage()).contains(arrayValue.getValue());
            }
        }
    }

    @Test
    public void test_null_becomes_the_empty_string() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"name\":null}]");

        assertThat(rows.get(0).values().get("name")).isEqualTo("");
    }

    @Test
    public void test_records_may_carry_different_keys() throws Exception {
        List<Row> rows = read("[{\"id\":1},{\"id\":2,\"name\":\"Globex\"}]");

        assertThat(rows.get(0).values().get("name")).isNull();
        assertThat(rows.get(1).values().get("name")).isEqualTo("Globex");
    }

    @Test
    public void test_empty_array_is_empty_not_an_error() throws Exception {
        assertThat(read("[]")).isEmpty();
    }

    @Test
    public void test_a_record_that_is_not_an_object_fails() throws Exception {
        try {
            read("[1,2,3]");
            throw new AssertionError("expected an IllegalArgumentException");
        } catch (IllegalArgumentException failure) {
            assertThat(failure.getMessage()).contains("row 1");
        }
    }

    @Test
    public void test_a_top_level_scalar_fails() throws Exception {
        try {
            read("42");
            throw new AssertionError("expected an IllegalArgumentException");
        } catch (IllegalArgumentException failure) {
            assertThat(failure.getMessage()).contains("row 1");
        }
    }

    @Test
    public void test_content_after_the_root_array_is_refused() throws Exception {
        try {
            read("[{\"a\":1}] {\"b\":2}");
            fail("two dumps concatenated must not import the first one and report success");
        } catch (IllegalArgumentException failure) {
            assertThat(failure.getMessage()).contains("content after the end of the json array");
        }
    }

    @Test
    public void test_an_empty_source_is_refused() throws Exception {
        for (String empty : List.of("", "   ")) {
            try {
                read(empty);
                fail("an export truncated to nothing must not import as a clean success");
            } catch (IllegalArgumentException failure) {
                assertThat(failure.getMessage()).contains("the source is empty");
            }
        }
    }

}
