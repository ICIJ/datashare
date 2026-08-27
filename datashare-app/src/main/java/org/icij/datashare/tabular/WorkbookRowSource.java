package org.icij.datashare.tabular;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.apache.commons.io.IOUtils.closeQuietly;

/**
 * Reads a workbook with POI. The whole workbook is loaded in memory, roughly 5 to 10 times the file
 * size in heap: streaming would mean two Excel code paths, since HSSF has no practical streaming
 * equivalent, plus hand-wiring StylesTable and SharedStrings to reproduce cell formatting. The
 * RowSource contract is stream-shaped, so replacing this with an XSSFReader implementation later
 * touches neither the interface nor its callers.
 */
public class WorkbookRowSource implements RowSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkbookRowSource.class);

    public static final Set<String> SUPPORTED = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.ms-excel.sheet.macroenabled.12");

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        // POI closes the stream inside create() when it succeeds, but not when it throws, and an
        // encrypted or truncated workbook throws before there is any stream for the caller to close.
        Workbook workbook;
        try {
            workbook = WorkbookFactory.create(source);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(source);
            throw failure;
        }
        try {
            Sheet sheet = selectSheet(workbook, options.sheet());
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            return read(sheet, evaluator).stream().onClose(() -> close(workbook));
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
            throw new IllegalArgumentException("no such sheet: " + requested, notAnIndex);
        }
    }

    // Materialized rather than lazily streamed: the workbook is already fully in memory, so a lazy
    // spliterator over it would buy nothing and would keep the workbook open for the caller to leak.
    private static List<Row> read(Sheet sheet, FormulaEvaluator evaluator) {
        java.util.Iterator<org.apache.poi.ss.usermodel.Row> sheetRows = sheet.iterator();
        if (!sheetRows.hasNext()) {
            throw new IllegalArgumentException("no header row: the sheet is empty");
        }
        List<String> headers = Row.headers(cells(sheetRows.next(), evaluator));

        List<Row> rows = new ArrayList<>();
        long blank = 0;
        while (sheetRows.hasNext()) {
            List<String> values = cells(sheetRows.next(), evaluator);
            if (values.stream().allMatch(String::isEmpty)) {
                blank++;
                continue;
            }
            long number = rows.size() + 1L;
            rows.add(new Row(number, Row.values(headers, values, number)));
        }
        if (blank > 0) {
            LOGGER.info("skipped {} blank rows in sheet {}", blank, sheet.getSheetName());
        }
        return rows;
    }

    private static List<String> cells(org.apache.poi.ss.usermodel.Row row, FormulaEvaluator evaluator) {
        List<String> values = new ArrayList<>();
        for (int column = 0; column < row.getLastCellNum(); column++) {
            values.add(value(row.getCell(column), evaluator));
        }
        return values;
    }

    /**
     * The cell's own value, never its display string: DataFormatter renders what the spreadsheet shows,
     * so a mapping's date format would depend on how somebody styled the file and a General-formatted
     * account number would arrive as 1.23457E+11. Date cells are rendered ISO-8601 for the same reason.
     */
    private static String value(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        return switch (resultType(cell, evaluator)) {
            case NUMERIC -> numeric(cell);
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            default -> "";
        };
    }

    /**
     * Evaluation first, because a generator that stores no cached result leaves a formula cell with no
     * value until it is evaluated. The cached result is the fallback rather than the other way round,
     * because POI throws NotImplementedException on the functions it does not implement (XLOOKUP,
     * DATEDIF, the dynamic-array family) and one of those in a footer must not abort the whole sheet.
     */
    private static CellType resultType(Cell cell, FormulaEvaluator evaluator) {
        if (cell.getCellType() != CellType.FORMULA) {
            return cell.getCellType();
        }
        try {
            return evaluator.evaluateFormulaCell(cell);
        } catch (RuntimeException notEvaluable) {
            LOGGER.info("falling back to the cached result of {}: {}",
                    cell.getAddress(), notEvaluable.toString());
            return cell.getCachedFormulaResultType();
        }
    }

    private static String numeric(Cell cell) {
        double serial = cell.getNumericCellValue();
        if (!DateUtil.isCellDateFormatted(cell)) {
            return BigDecimal.valueOf(serial).stripTrailingZeros().toPlainString();
        }
        LocalDateTime moment = cell.getLocalDateTimeCellValue();
        // A serial below 1 has no date part at all: the format is time-only, and anchoring it on POI's
        // day zero would invent a 1899-12-31 the document never contained.
        if (serial < 1.0) {
            return moment.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        return moment.toLocalTime().equals(LocalTime.MIDNIGHT)
                ? moment.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : moment.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static void close(Workbook workbook) {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the workbook failed", e);
        }
    }
}
