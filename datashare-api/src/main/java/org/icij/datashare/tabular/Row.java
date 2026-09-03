package org.icij.datashare.tabular;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One data row. {@code number} is the 1-based ordinal of the emitted row, not the row's position in
 * the file: the header row and the rows a reader skips, an entirely blank one for instance, consume
 * no number.
 */
public record Row(long number, Map<String, String> values) {
    /** The whitespace rule headers and cells share: the space a spreadsheet writes as U+00A0 reads
     *  as a space, a zero-width character or stray BOM is removed (it is not whitespace to strip(),
     *  yet it would silently split one key value into two entity ids), and surrounding whitespace
     *  is not content. Runs on every cell of every row, so a cell holding none of it, which is the
     *  overwhelming case, walks away with the string it came in with. */
    static String clean(String name) {
        for (int index = 0; index < name.length(); index++) {
            char letter = name.charAt(index);
            if (invisible(letter) || nonBreakingSpace(letter)) {
                return rewritten(name);
            }
        }
        return name.strip();
    }

    private static String rewritten(String name) {
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char letter = name.charAt(index);
            if (!invisible(letter)) {
                cleaned.append(nonBreakingSpace(letter) ? ' ' : letter);
            }
        }
        return cleaned.toString().strip();
    }

    private static boolean invisible(char letter) {
        return letter == '\uFEFF' || letter >= '\u200B' && letter <= '\u200D';
    }

    private static boolean nonBreakingSpace(char letter) {
        return letter == '\u00A0' || letter == '\u2007' || letter == '\u202F';
    }

    /**
     * Applies the one header rule every reader shares: a blank name means the column is dropped
     * (represented by a null entry the caller skips), a duplicate name is a mapping the reader
     * cannot honour unambiguously and so fails, and a header row with no name at all is not a header
     * row: importing every data row as an empty map would report a total loss as a success.
     */
    public static List<String> headers(List<String> rawNames) {
        List<String> headers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rawName : rawNames) {
            String name = rawName == null ? "" : clean(rawName);
            if (name.isEmpty()) {
                headers.add(null);
                continue;
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("duplicate column name in header: " + name);
            }
            headers.add(name);
        }
        if (seen.isEmpty()) {
            throw new IllegalArgumentException("no column name in the header row: every name is blank, "
                    + "so the row above the data is a title or a spacer rather than a header");
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
