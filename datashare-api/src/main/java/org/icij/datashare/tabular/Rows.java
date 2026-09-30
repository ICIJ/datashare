package org.icij.datashare.tabular;

import java.util.stream.Stream;

/**
 * The rows of one section of a source, with the section the reader resolved: a workbook's sheet
 * name, the Tika fallback's table index, or null for a format with one table. It is the section
 * actually read, not the one the mapping asked for: "1" and "Sheet1" can name the same sheet.
 */
public record Rows(String sheet, Stream<Row> rows) implements AutoCloseable {
    @Override
    public void close() {
        rows.close();
    }
}
