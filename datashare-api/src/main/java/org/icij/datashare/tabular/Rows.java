package org.icij.datashare.tabular;

import java.util.List;
import java.util.stream.Stream;

/**
 * The rows of one section of a source, with the section the reader resolved: a workbook's sheet
 * name, the Tika fallback's table index, or null for a format with one table. It is the section
 * actually read, not the one the mapping asked for: "1" and "Sheet1" can name the same sheet.
 *
 * <p>{@code header} is every column the source declares up front, blank names dropped, or null for a
 * source whose records carry their own names, which only the whole read can tell.
 */
public record Rows(String sheet, List<String> header, Stream<Row> rows) implements AutoCloseable {
    @Override
    public void close() {
        rows.close();
    }
}
