package org.icij.datashare.tabular;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

/**
 * The same logical table in every format a reader claims, asserted to produce identical rows. This is
 * what keeps a new reader honest: adding one means adding its fixture here.
 */
public class RowSourceEquivalenceTest {
    private static final List<Map<String, String>> EXPECTED = List.of(
            Map.of("id", "1", "name", "ACME", "country", "FR"),
            Map.of("id", "2", "name", "Globex", "country", "US"));

    /**
     * The source stream is deliberately not in the try-with-resources list: closing the returned row
     * stream is what has to release it, per the RowSource contract.
     */
    private static List<Row> read(RowSource source, byte[] content, RowSourceOptions options)
            throws Exception {
        TrackingInputStream stream = new TrackingInputStream(content);
        List<Row> read;
        try (Stream<Row> rows = source.rows(stream, options)) {
            read = rows.toList();
        }
        assertThat(stream.closed).isTrue();
        return read;
    }

    private static void assertEquivalent(List<Row> rows) {
        assertThat(rows).hasSize(2);
        for (int index = 0; index < EXPECTED.size(); index++) {
            assertThat(rows.get(index).number()).isEqualTo(index + 1L);
            assertThat(rows.get(index).values()).isEqualTo(EXPECTED.get(index));
        }
    }

    @Test
    public void test_comma_separated() throws Exception {
        assertEquivalent(read(new DelimitedRowSource(),
                "id,name,country\n1,ACME,FR\n2,Globex,US\n".getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_semicolon_separated() throws Exception {
        assertEquivalent(read(new DelimitedRowSource(),
                "id;name;country\n1;ACME;FR\n2;Globex;US\n".getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults().withDelimiter(';')));
    }

    @Test
    public void test_tab_separated() throws Exception {
        assertEquivalent(read(new DelimitedRowSource(),
                "id\tname\tcountry\n1\tACME\tFR\n2\tGlobex\tUS\n".getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults().withDelimiter('\t')));
    }

    @Test
    public void test_xlsx() throws Exception {
        assertEquivalent(read(new WorkbookRowSource(), workbook(new XSSFWorkbook()),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_legacy_xls() throws Exception {
        assertEquivalent(read(new WorkbookRowSource(), workbook(new HSSFWorkbook()),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_json_array() throws Exception {
        assertEquivalent(read(new JsonRowSource(),
                ("[{\"id\":1,\"name\":\"ACME\",\"country\":\"FR\"},"
                        + "{\"id\":2,\"name\":\"Globex\",\"country\":\"US\"}]")
                        .getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_line_delimited_json() throws Exception {
        assertEquivalent(read(new JsonRowSource(),
                ("{\"id\":1,\"name\":\"ACME\",\"country\":\"FR\"}\n"
                        + "{\"id\":2,\"name\":\"Globex\",\"country\":\"US\"}\n")
                        .getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_html_table_through_the_fallback() throws Exception {
        String html = "<html><body><table>"
                + "<tr><th>id</th><th>name</th><th>country</th></tr>"
                + "<tr><td>1</td><td>ACME</td><td>FR</td></tr>"
                + "<tr><td>2</td><td>Globex</td><td>US</td></tr>"
                + "</table></body></html>";

        assertEquivalent(read(new TikaTableRowSource(), html.getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults().withContentType("text/html")));
    }

    @Test
    public void test_open_document_spreadsheet_through_the_fallback() throws Exception {
        assertEquivalent(read(new TikaTableRowSource(), ods(),
                RowSourceOptions.defaults()
                        .withContentType("application/vnd.oasis.opendocument.spreadsheet")));
    }

    /**
     * A row missing its last column, in each header-based reader. Every column the header declares is
     * present in the map, so the mapping executor never branches on which format a row came from.
     */
    @Test
    public void test_a_short_row_pads_the_missing_column_in_every_reader() throws Exception {
        List<Map<String, String>> expected = List.of(
                Map.of("id", "1", "name", "ACME", "country", "FR"),
                Map.of("id", "2", "name", "Globex", "country", ""));

        List<List<Row>> reads = List.of(
                read(new DelimitedRowSource(),
                        "id,name,country\n1,ACME,FR\n2,Globex\n".getBytes(StandardCharsets.UTF_8),
                        RowSourceOptions.defaults()),
                read(new WorkbookRowSource(), raggedWorkbook(), RowSourceOptions.defaults()),
                read(new TikaTableRowSource(),
                        ("<html><body><table>"
                                + "<tr><th>id</th><th>name</th><th>country</th></tr>"
                                + "<tr><td>1</td><td>ACME</td><td>FR</td></tr>"
                                + "<tr><td>2</td><td>Globex</td></tr>"
                                + "</table></body></html>").getBytes(StandardCharsets.UTF_8),
                        RowSourceOptions.defaults().withContentType("text/html")));

        for (List<Row> rows : reads) {
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).values()).isEqualTo(expected.get(0));
            assertThat(rows.get(1).values()).isEqualTo(expected.get(1));
        }
    }

    private static byte[] raggedWorkbook() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("companies");
        String[][] table = {{"id", "name", "country"}, {"1", "ACME", "FR"}, {"2", "Globex"}};
        for (int rowIndex = 0; rowIndex < table.length; rowIndex++) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex);
            for (int column = 0; column < table[rowIndex].length; column++) {
                row.createCell(column).setCellValue(table[rowIndex][column]);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            workbook.close();
            return out.toByteArray();
        }
    }

    private static byte[] workbook(Workbook workbook) throws Exception {
        Sheet sheet = workbook.createSheet("companies");
        String[][] table = {{"id", "name", "country"}, {"1", "ACME", "FR"}, {"2", "Globex", "US"}};
        for (int rowIndex = 0; rowIndex < table.length; rowIndex++) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex);
            for (int column = 0; column < table[rowIndex].length; column++) {
                row.createCell(column).setCellValue(table[rowIndex][column]);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            workbook.close();
            return out.toByteArray();
        }
    }

    /**
     * A minimal OpenDocument spreadsheet, built by hand because POI cannot write ODS. Only the
     * mimetype entry and content.xml are needed for Tika's OpenDocument parser to emit a table.
     */
    private static byte[] ods() throws Exception {
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-content"
                + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
                + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
                + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
                + "<office:body><office:spreadsheet>"
                + "<table:table table:name=\"companies\">"
                + row("id", "name", "country")
                + row("1", "ACME", "FR")
                + row("2", "Globex", "US")
                + "</table:table>"
                + "</office:spreadsheet></office:body></office:document-content>";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String row(String... cells) {
        StringBuilder row = new StringBuilder("<table:table-row>");
        for (String cell : cells) {
            row.append("<table:table-cell><text:p>").append(cell).append("</text:p></table:table-cell>");
        }
        return row.append("</table:table-row>").toString();
    }
}
