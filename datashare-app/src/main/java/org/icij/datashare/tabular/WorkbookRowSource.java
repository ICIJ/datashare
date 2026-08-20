package org.icij.datashare.tabular;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a workbook with POI. The whole workbook is loaded in memory, roughly 5 to 10 times the file
 * size in heap: streaming would mean two Excel code paths, since HSSF has no practical streaming
 * equivalent, plus hand-wiring StylesTable and SharedStrings to reproduce cell formatting. The
 * RowSource contract is stream-shaped, so replacing this with an XSSFReader implementation later
 * touches neither the interface nor its callers.
 */
public class WorkbookRowSource implements RowSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkbookRowSource.class);

    private static final Set<String> SUPPORTED = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.ms-excel.sheet.macroenabled.12");

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        Workbook workbook = WorkbookFactory.create(source);
        try {
            Sheet sheet = selectSheet(workbook, options.sheet());
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter();
            return read(sheet, evaluator, formatter).stream().onClose(() -> close(workbook));
        } catch (RuntimeException failure) {
            close(workbook);
            throw failure;
        }
    }

    /**
     * By name first, so a sheet literally named "2" wins over position 2, and only then as a 1-based
     * index when the name matches nothing and parses as an integer.
     */
    private static Sheet selectSheet(Workbook workbook, String requested) {
        if (requested == null) {
            return workbook.getSheetAt(0);
        }
        Sheet byName = workbook.getSheet(requested);
        if (byName != null) {
            return byName;
        }
        try {
            return workbook.getSheetAt(Integer.parseInt(requested.strip()) - 1);
        } catch (IllegalArgumentException notAnIndex) {
            throw new IllegalArgumentException("no such sheet: " + requested);
        }
    }

    // Materialized rather than lazily streamed: the workbook is already fully in memory, so a lazy
    // spliterator over it would buy nothing and would keep the workbook open for the caller to leak.
    private static List<Row> read(Sheet sheet, FormulaEvaluator evaluator, DataFormatter formatter) {
        java.util.Iterator<org.apache.poi.ss.usermodel.Row> sheetRows = sheet.iterator();
        if (!sheetRows.hasNext()) {
            throw new IllegalArgumentException("no header row: the sheet is empty");
        }
        List<String> headers = Row.headers(cells(sheetRows.next(), evaluator, formatter));

        List<Row> rows = new ArrayList<>();
        long blank = 0;
        while (sheetRows.hasNext()) {
            List<String> values = cells(sheetRows.next(), evaluator, formatter);
            if (values.stream().allMatch(String::isEmpty)) {
                blank++;
                continue;
            }
            rows.add(new Row(rows.size() + 1L, map(headers, values)));
        }
        if (blank > 0) {
            LOGGER.info("skipped {} blank rows in sheet {}", blank, sheet.getSheetName());
        }
        return rows;
    }

    private static List<String> cells(org.apache.poi.ss.usermodel.Row row, FormulaEvaluator evaluator, DataFormatter formatter) {
        List<String> values = new ArrayList<>();
        for (int column = 0; column < row.getLastCellNum(); column++) {
            values.add(value(row.getCell(column), evaluator, formatter));
        }
        return values;
    }

    /**
     * DataFormatter renders a date cell with the spreadsheet's own display style, which would make a
     * mapping's date format depend on how somebody styled the file. Date cells are therefore rendered
     * ISO-8601 instead, dropping the time part when it is midnight.
     */
    private static String value(Cell cell, FormulaEvaluator evaluator, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (isDate(cell, evaluator)) {
            LocalDateTime moment = cell.getLocalDateTimeCellValue();
            return moment.toLocalTime().equals(LocalTime.MIDNIGHT)
                    ? moment.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    : moment.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    private static boolean isDate(Cell cell, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType() == CellType.FORMULA
                ? evaluator.evaluateFormulaCell(cell)
                : cell.getCellType();
        return type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell);
    }

    private static Map<String, String> map(List<String> headers, List<String> values) {
        Map<String, String> mapped = new HashMap<>();
        for (int column = 0; column < headers.size(); column++) {
            if (headers.get(column) != null) {
                mapped.put(headers.get(column), column < values.size() ? values.get(column) : "");
            }
        }
        return mapped;
    }

    private static void close(Workbook workbook) {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the workbook failed", e);
        }
    }
}
