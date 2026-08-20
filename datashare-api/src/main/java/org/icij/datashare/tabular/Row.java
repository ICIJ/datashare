package org.icij.datashare.tabular;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One data row. {@code number} is the 1-based ordinal of the emitted row, not the row's position in
 * the file: the header row and the rows a reader skips, an entirely blank one for instance, consume
 * no number.
 */
public record Row(long number, Map<String, String> values) {

    /**
     * Applies the one header rule every reader shares: a blank name means the column is dropped
     * (represented by a null entry the caller skips), a duplicate name is a mapping the reader
     * cannot honour unambiguously and so fails.
     */
    public static List<String> headers(List<String> rawNames) {
        List<String> headers = new ArrayList<>();
        for (String rawName : rawNames) {
            String name = rawName == null ? "" : rawName.strip();
            if (name.isEmpty()) {
                headers.add(null);
                continue;
            }
            if (headers.contains(name)) {
                throw new IllegalArgumentException("duplicate column name in header: " + name);
            }
            headers.add(name);
        }
        return headers;
    }

    /**
     * Maps a row's cells onto the header names, in header order. Every column the header declares is
     * present, padded with an empty string when the row is short, so a consumer never has to tell a
     * missing cell from an empty one. A column whose header name is blank is dropped, and cells past
     * the last declared column are ignored.
     */
    public static Map<String, String> values(List<String> headers, List<String> cells) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int column = 0; column < headers.size(); column++) {
            if (headers.get(column) != null) {
                values.put(headers.get(column), column < cells.size() ? cells.get(column) : "");
            }
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * The same mapping, refusing a row carrying more fields than the header declares: that is the
     * signature of a wrong delimiter or of a separator inside an unquoted value, and importing such a
     * row would silently misalign every value in it. Only a surplus cell that is not blank counts,
     * because both of those signatures put content in it: a trailing delimiter in a csv and a styled
     * or previously emptied cell past the last filled column in a workbook produce an empty surplus
     * that carries no data and cannot misalign anything.
     */
    public static Map<String, String> values(List<String> headers, List<String> cells, long number) {
        if (cells.stream().skip(headers.size()).anyMatch(cell -> !cell.isBlank())) {
            throw new IllegalArgumentException("row " + number + " has " + cells.size()
                    + " fields but the header declares " + headers.size());
        }
        return values(headers, cells);
    }
}
