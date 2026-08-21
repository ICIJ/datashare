package org.icij.datashare.tabular;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

public class DelimitedRowSourceTest {
    private final DelimitedRowSource source = new DelimitedRowSource();

    private List<Row> read(String content, RowSourceOptions options) throws Exception {
        return read(content.getBytes(StandardCharsets.UTF_8), options);
    }

    private List<Row> read(byte[] content, RowSourceOptions options) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(content);
             Stream<Row> rows = source.rows(stream, options)) {
            return rows.toList();
        }
    }

    @Test
    public void test_supports_delimited_content_types() {
        assertThat(source.supports("text/csv")).isTrue();
        assertThat(source.supports("text/tab-separated-values")).isTrue();
        assertThat(source.supports("text/plain")).isTrue();
        assertThat(source.supports("application/json")).isFalse();
    }

    @Test
    public void test_reads_comma_separated_rows_with_header() throws Exception {
        List<Row> rows = read("id,name\n1,ACME\n2,Globex\n", RowSourceOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).number()).isEqualTo(1L);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
        assertThat(rows.get(1).number()).isEqualTo(2L);
        assertThat(rows.get(1).values().get("id")).isEqualTo("2");
    }

    @Test
    public void test_uses_the_delimiter_from_options() throws Exception {
        List<Row> rows = read("id;name\n1;ACME\n",
                RowSourceOptions.defaults().withDelimiter(';'));

        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_quoted_field_keeps_delimiter_and_newline() throws Exception {
        List<Row> rows = read("id,name\n1,\"ACME, Inc.\nsecond line\"\n", RowSourceOptions.defaults());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME, Inc.\nsecond line");
    }

    @Test
    public void test_reads_with_the_given_charset() throws Exception {
        byte[] latin1 = "id,name\n1,Café\n".getBytes(Charset.forName("windows-1252"));

        List<Row> rows = read(latin1,
                RowSourceOptions.defaults().withCharset(Charset.forName("windows-1252")));

        assertThat(rows.get(0).values().get("name")).isEqualTo("Café");
    }

    @Test
    public void test_drops_blank_named_columns() throws Exception {
        List<Row> rows = read("id,,name\n1,ignored,ACME\n", RowSourceOptions.defaults());

        assertThat(rows.get(0).values()).hasSize(2);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_duplicate_header_fails() throws Exception {
        read("id,name,name\n1,a,b\n", RowSourceOptions.defaults());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_no_header_row_fails() throws Exception {
        read("", RowSourceOptions.defaults());
    }

    @Test
    public void test_header_with_no_data_rows_is_empty_not_an_error() throws Exception {
        assertThat(read("id,name\n", RowSourceOptions.defaults())).isEmpty();
    }

    @Test
    public void test_a_short_row_pads_the_missing_columns() throws Exception {
        List<Row> rows = read("id,name,country\n1,ACME\n", RowSourceOptions.defaults());

        assertThat(rows.get(0).values()).hasSize(3);
        assertThat(rows.get(0).values().get("country")).isEqualTo("");
    }

    @Test
    public void test_a_row_with_more_fields_than_the_header_fails() throws Exception {
        try {
            read("id,name\n1,ACME,extra\n", RowSourceOptions.defaults());
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("row 1");
            assertThat(e.getMessage()).contains("3 fields");
            assertThat(e.getMessage()).contains("declares 2");
        }
    }

    /**
     * A trailing delimiter is one of the commonest real CSV shapes. The surplus field it produces is
     * empty, so it carries no data and cannot be the misalignment a long row is refused for.
     */
    @Test
    public void test_a_trailing_delimiter_does_not_fail_the_row() throws Exception {
        List<Row> rows = read("id,name\n1,ACME,\n", RowSourceOptions.defaults());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).values()).hasSize(2);
        assertThat(rows.get(0).values().get("id")).isEqualTo("1");
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_skips_a_row_of_empty_fields_without_consuming_a_number() throws Exception {
        List<Row> rows = read("id,name\n,\n1,ACME\n", RowSourceOptions.defaults());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).number()).isEqualTo(1L);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_malformed_record_names_the_row_number() throws Exception {
        try {
            read("id,name\n1,\"unterminated\n", RowSourceOptions.defaults());
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("row 1");
        }
    }

    @Test
    public void test_a_byte_order_mark_is_not_part_of_the_first_column_name() throws Exception {
        byte[] withBom = ("\uFEFFid,name\n1,ACME\n").getBytes(StandardCharsets.UTF_8);

        List<Row> rows = read(withBom, RowSourceOptions.defaults());

        assertThat(rows.get(0).values().keySet()).containsOnly("id", "name");
        assertThat(rows.get(0).values().get("id")).isEqualTo("1");
    }

    @Test
    public void test_a_header_row_whose_every_name_is_blank_is_refused() throws Exception {
        try {
            read(",,\nid,name,country\n1,ACME,FR\n", RowSourceOptions.defaults());
            fail("a title or spacer row above the data must not import every row as an empty map");
        } catch (IllegalArgumentException failure) {
            assertThat(failure.getMessage()).contains("every name is blank");
        }
    }

    @Test
    public void test_a_tab_separated_content_type_implies_the_tab_delimiter() throws Exception {
        for (String contentType : List.of("text/tab-separated-values", "text/tsv")) {
            List<Row> rows = read("id\tname\n1\tACME\n",
                    RowSourceOptions.defaults().withContentType(contentType));

            assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
        }
    }

    @Test
    public void test_the_quote_character_is_configurable() throws Exception {
        List<Row> rows = read("id,name\n1,'ACME, Inc'\n",
                RowSourceOptions.defaults().withQuote('\''));

        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME, Inc");
    }

    /**
     * Tika settles on a single-byte charset for a large mostly-ascii export, and reading utf-8 as
     * latin-1 never reports an error, so the detected charset cannot be taken at face value.
     */
    @Test
    public void test_a_utf8_source_detected_as_latin1_is_still_read_as_utf8() throws Exception {
        List<Row> rows = read("id,name\n1,R\u00e9publique\n",
                RowSourceOptions.defaults().withCharset(Charset.forName("ISO-8859-1")));

        assertThat(rows.get(0).values().get("name")).isEqualTo("R\u00e9publique");
    }

    @Test
    public void test_a_genuinely_latin1_source_keeps_the_detected_charset() throws Exception {
        byte[] latin1 = "id,name\n1,R\u00e9publique\n".getBytes(Charset.forName("ISO-8859-1"));

        List<Row> rows = read(latin1, RowSourceOptions.defaults().withCharset(Charset.forName("ISO-8859-1")));

        assertThat(rows.get(0).values().get("name")).isEqualTo("R\u00e9publique");
    }

    @Test
    public void test_a_failure_after_the_header_still_releases_the_source() throws Exception {
        TrackingInputStream stream = new TrackingInputStream("id,id\n1,2\n".getBytes(StandardCharsets.UTF_8));

        try {
            source.rows(stream, RowSourceOptions.defaults());
            fail("a duplicate header name must be refused");
        } catch (IllegalArgumentException expected) {
            assertThat(stream.closed).isTrue();
        }
    }

}
