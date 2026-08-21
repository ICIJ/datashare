package org.icij.datashare.tabular;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class TikaTableRowSourceTest {
    private final TikaTableRowSource source = new TikaTableRowSource();

    private List<Row> read(byte[] content, RowSourceOptions options) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(content);
             Stream<Row> rows = source.rows(stream, options)) {
            return rows.toList();
        }
    }

    private List<Row> readHtml(String html, RowSourceOptions options) throws Exception {
        return read(html.getBytes(StandardCharsets.UTF_8), options.withContentType("text/html"));
    }

    @Test
    public void test_supports_the_verified_tier_two_types_only() {
        assertThat(source.supports("application/vnd.oasis.opendocument.spreadsheet")).isTrue();
        assertThat(source.supports("application/vnd.ms-excel.sheet.binary.macroenabled.12")).isTrue();
        assertThat(source.supports("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isTrue();
        assertThat(source.supports("application/msword")).isTrue();
        assertThat(source.supports("text/html")).isTrue();
        assertThat(source.supports("application/vnd.apple.numbers")).isTrue();
    }

    @Test
    public void test_does_not_claim_pdf_or_rtf_or_tier_one_types() {
        assertThat(source.supports("application/pdf")).isFalse();
        assertThat(source.supports("application/rtf")).isFalse();
        assertThat(source.supports("text/csv")).isFalse();
        assertThat(source.supports("application/vnd.ms-excel")).isFalse();
    }

    @Test
    public void test_reads_an_html_table() throws Exception {
        List<Row> rows = readHtml(
                "<html><body><table>"
                        + "<tr><th>id</th><th>name</th></tr>"
                        + "<tr><td>1</td><td>ACME</td></tr>"
                        + "<tr><td>2</td><td>Globex</td></tr>"
                        + "</table></body></html>",
                RowSourceOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).number()).isEqualTo(1L);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
        assertThat(rows.get(1).values().get("id")).isEqualTo("2");
    }

    @Test
    public void test_reads_the_first_table_by_default() throws Exception {
        List<Row> rows = readHtml(
                "<html><body>"
                        + "<table><tr><th>id</th></tr><tr><td>first</td></tr></table>"
                        + "<table><tr><th>id</th></tr><tr><td>second</td></tr></table>"
                        + "</body></html>",
                RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("id")).isEqualTo("first");
    }

    @Test
    public void test_reads_the_table_at_the_index_in_the_options() throws Exception {
        List<Row> rows = readHtml(
                "<html><body>"
                        + "<table><tr><th>id</th></tr><tr><td>first</td></tr></table>"
                        + "<table><tr><th>id</th></tr><tr><td>second</td></tr></table>"
                        + "</body></html>",
                new RowSourceOptions(null, null, null, null, null, 2));

        assertThat(rows.get(0).values().get("id")).isEqualTo("second");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_header_row_with_colspan_fails() throws Exception {
        readHtml("<html><body><table>"
                + "<tr><th colspan=\"2\">both</th></tr>"
                + "<tr><td>1</td><td>ACME</td></tr>"
                + "</table></body></html>", RowSourceOptions.defaults());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_data_row_with_colspan_fails() throws Exception {
        readHtml("<html><body><table>"
                + "<tr><th>id</th><th>name</th></tr>"
                + "<tr><td colspan=\"2\">both</td></tr>"
                + "</table></body></html>", RowSourceOptions.defaults());
    }

    @Test
    public void test_a_row_with_more_cells_than_the_header_keeps_the_declared_columns() throws Exception {
        List<Row> rows = readHtml(
                "<html><body><table>"
                        + "<tr><th>id</th><th>name</th></tr>"
                        + "<tr><td>1</td><td>ACME</td><td>stray</td></tr>"
                        + "</table></body></html>",
                RowSourceOptions.defaults());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).values()).hasSize(2);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_a_nested_table_does_not_add_rows_to_the_outer_table() throws Exception {
        List<Row> rows = readHtml(
                "<html><body><table>"
                        + "<tr><th>id</th><th>name</th></tr>"
                        + "<tr><td>1</td><td>ACME"
                        + "<table><tr><th>inner</th></tr><tr><td>nested</td></tr></table>"
                        + "</td></tr>"
                        + "<tr><td>2</td><td>Globex</td></tr>"
                        + "</table></body></html>",
                RowSourceOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).values()).hasSize(2);
        assertThat(rows.get(0).values().get("id")).isEqualTo("1");
        assertThat(rows.get(1).values().get("name")).isEqualTo("Globex");
    }

    @Test
    public void test_closing_the_returned_stream_closes_the_source() throws Exception {
        TrackingInputStream stream = new TrackingInputStream(
                "<html><body><table><tr><th>id</th></tr><tr><td>1</td></tr></table></body></html>");

        try (Stream<Row> rows = source.rows(stream, RowSourceOptions.defaults().withContentType("text/html"))) {
            rows.toList();
        }

        assertThat(stream.closed).isTrue();
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_no_table_at_all_fails() throws Exception {
        readHtml("<html><body><p>no table here</p></body></html>", RowSourceOptions.defaults());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_table_index_out_of_range_fails() throws Exception {
        readHtml("<html><body><table><tr><th>id</th></tr></table></body></html>",
                new RowSourceOptions(null, null, null, null, null, 9));
    }

    @Test
    public void test_reads_a_table_from_a_docx() throws Exception {
        byte[] docx = docxWithTable();

        List<Row> rows = read(docx, RowSourceOptions.defaults().withContentType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    private static byte[] docxWithTable() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("id");
            table.getRow(0).getCell(1).setText("name");
            table.getRow(1).getCell(0).setText("1");
            table.getRow(1).getCell(1).setText("ACME");
            document.write(out);
            return out.toByteArray();
        }
    }

    @Test
    public void test_supports_the_modern_numbers_generations() {
        assertThat(source.supports("application/vnd.apple.numbers.13")).isTrue();
        assertThat(source.supports("application/vnd.apple.numbers.18")).isTrue();
    }

    @Test
    public void test_a_nested_table_stays_out_of_the_outer_cell_value() throws Exception {
        List<Row> rows = readHtml("<html><body><table>"
                + "<tr><th>id</th><th>name</th></tr>"
                + "<tr><td>1</td><td>ACME<table><tr><th>inner</th></tr><tr><td>nested</td></tr></table></td></tr>"
                + "</table></body></html>", RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    /**
     * Word, TinyMCE and CKEditor write colspan="1" on every cell they emit, and a span of one merges
     * nothing.
     */
    @Test
    public void test_a_span_of_one_is_not_a_merged_cell() throws Exception {
        List<Row> rows = readHtml("<html><body><table>"
                + "<tr><th colspan=\"1\">id</th><th rowspan=\"1\">name</th></tr>"
                + "<tr><td>1</td><td>ACME</td></tr></table></body></html>", RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    /**
     * Tika renders ODF's table:number-columns-repeated as a colspan, and LibreOffice writes it on the
     * trailing empty cells of every sheet, so an empty span is padding and has to expand back into the
     * columns it stands for rather than be read as a merge.
     */
    @Test
    public void test_an_empty_repeated_cell_expands_instead_of_failing() throws Exception {
        List<Row> rows = readHtml("<html><body><table>"
                + "<tr><th>id</th><th></th><th></th><th>name</th></tr>"
                + "<tr><td>1</td><td colspan=\"2\"></td><td>ACME</td></tr></table></body></html>",
                RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_a_cell_with_content_across_two_columns_is_still_refused() throws Exception {
        try {
            readHtml("<html><body><table><tr><th>id</th><th>name</th></tr>"
                    + "<tr><td colspan=\"2\">merged</td></tr></table></body></html>", RowSourceOptions.defaults());
            throw new AssertionError("a value spanning two columns cannot be lined up with either header");
        } catch (IllegalArgumentException failure) {
            assertThat(failure.getMessage()).contains("merged cells");
        }
    }

    @Test
    public void test_a_tfoot_written_before_the_body_stays_after_it() throws Exception {
        List<Row> rows = readHtml("<html><body><table>"
                + "<thead><tr><th>id</th></tr></thead>"
                + "<tfoot><tr><td>total</td></tr></tfoot>"
                + "<tbody><tr><td>b1</td></tr></tbody></table></body></html>", RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("id")).isEqualTo("b1");
        assertThat(rows.get(1).values().get("id")).isEqualTo("total");
    }

}
