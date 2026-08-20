package org.icij.datashare.tabular;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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

    private static List<Row> read(RowSource source, byte[] content, RowSourceOptions options)
            throws Exception {
        try (InputStream stream = new ByteArrayInputStream(content);
             Stream<Row> rows = source.rows(stream, options)) {
            return rows.toList();
        }
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
