package org.icij.datashare.tabular;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One data row. {@code number} is 1-based and excludes the header row. */
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
}
