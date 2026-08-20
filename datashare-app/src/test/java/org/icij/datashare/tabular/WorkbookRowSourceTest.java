package org.icij.datashare.tabular;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class WorkbookRowSourceTest {
    private final WorkbookRowSource source = new WorkbookRowSource();

    private List<Row> read(byte[] workbook, RowSourceOptions options) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(workbook);
             Stream<Row> rows = source.rows(stream, options)) {
            return rows.toList();
        }
    }

    private static byte[] bytes(Workbook workbook) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            workbook.close();
            return out.toByteArray();
        }
    }

    private static void fill(Sheet sheet) {
        sheet.createRow(0).createCell(0).setCellValue("id");
        sheet.getRow(0).createCell(1).setCellValue("name");
        sheet.createRow(1).createCell(0).setCellValue("1");
        sheet.getRow(1).createCell(1).setCellValue("ACME");
    }

    @Test
    public void test_supports_excel_content_types() {
        assertThat(source.supports("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isTrue();
        assertThat(source.supports("application/vnd.ms-excel")).isTrue();
        assertThat(source.supports("application/vnd.ms-excel.sheet.macroenabled.12")).isTrue();
        assertThat(source.supports("application/vnd.ms-excel.sheet.binary.macroenabled.12")).isFalse();
        assertThat(source.supports("text/csv")).isFalse();
    }

    @Test
    public void test_reads_xlsx_rows() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        fill(workbook.createSheet("companies"));

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).number()).isEqualTo(1L);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_reads_legacy_xls_rows() throws Exception {
        HSSFWorkbook workbook = new HSSFWorkbook();
        fill(workbook.createSheet("companies"));

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_reads_the_first_sheet_by_default() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        fill(workbook.createSheet("first"));
        Sheet second = workbook.createSheet("second");
        second.createRow(0).createCell(0).setCellValue("id");
        second.createRow(1).createCell(0).setCellValue("999");

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("id")).isEqualTo("1");
    }

    @Test
    public void test_reads_the_sheet_named_in_the_options() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        fill(workbook.createSheet("first"));
        Sheet second = workbook.createSheet("second");
        second.createRow(0).createCell(0).setCellValue("id");
        second.createRow(1).createCell(0).setCellValue("999");

        List<Row> rows = read(bytes(workbook),
                new RowSourceOptions(null, null, null, null, "second", null));

        assertThat(rows.get(0).values().get("id")).isEqualTo("999");
    }

    @Test
    public void test_renders_a_date_cell_as_iso_date() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("dates");
        sheet.createRow(0).createCell(0).setCellValue("born");
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
        Cell cell = sheet.createRow(1).createCell(0);
        cell.setCellValue(LocalDateTime.of(1984, 3, 17, 0, 0));
        cell.setCellStyle(dateStyle);

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("born")).isEqualTo("1984-03-17");
    }

    @Test
    public void test_renders_a_datetime_cell_as_iso_datetime() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("dates");
        sheet.createRow(0).createCell(0).setCellValue("seen");
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
        Cell cell = sheet.createRow(1).createCell(0);
        cell.setCellValue(LocalDateTime.of(1984, 3, 17, 14, 30));
        cell.setCellStyle(style);

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("seen")).isEqualTo("1984-03-17T14:30:00");
    }

    @Test
    public void test_renders_a_formula_cell_as_its_computed_value() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("sums");
        sheet.createRow(0).createCell(0).setCellValue("total");
        sheet.createRow(1).createCell(0).setCellFormula("2+3");

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("total")).isEqualTo("5");
    }

    @Test
    public void test_skips_entirely_blank_rows() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("companies");
        fill(sheet);
        sheet.createRow(2).createCell(0).setCellValue("");
        sheet.createRow(3).createCell(0).setCellValue("2");
        sheet.getRow(3).createCell(1).setCellValue("Globex");

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).values().get("name")).isEqualTo("Globex");
        assertThat(rows.get(1).number()).isEqualTo(2L);
    }

    @Test
    public void test_blank_cell_is_the_empty_string() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("companies");
        sheet.createRow(0).createCell(0).setCellValue("id");
        sheet.getRow(0).createCell(1).setCellValue("name");
        sheet.createRow(1).createCell(0).setCellValue("1");

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("name")).isEqualTo("");
    }
}
