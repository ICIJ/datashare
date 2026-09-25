package org.icij.datashare.tabular;

import java.util.stream.Stream;

/**
 * The rows of one section of a source, with the section the reader resolved: a workbook's sheet
 * name, the Tika fallback's table index, or null for a format with one table. The caller gets what
 * was read rather than what was asked for, because a statement id hashes the section and retraction
 * keys on it, so "1" and "Sheet1" have to agree while two tables of one document must not.
 */
public record Rows(String sheet, Stream<Row> rows) implements AutoCloseable {
    @Override
    public void close() {
        rows.close();
    }
}
