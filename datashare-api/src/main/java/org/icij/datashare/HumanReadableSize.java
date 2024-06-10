package org.icij.datashare;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HumanReadableSize {
    static Pattern pattern = Pattern.compile("([0-9]+)([KMG])");
    public enum Unit {
        K(1024), M(K.bytes * 1024), G(M.bytes * 1024);
        private final long bytes;
        Unit(long bytes) {
            this.bytes = bytes;
        }
    }

    /**
     * parse a size in bytes, KB, GB, MB
     *
     * @param humanReadableSize: size with K/M/G like 1M, 12G
     * @return size in bytes
     * @throws IllegalArgumentException if size is not parsable
     */
    public static long parse(String humanReadableSize) {
        Matcher match = pattern.matcher(humanReadableSize);
        if (match.matches()) {
            long value = Long.parseLong(match.group(1));
            Unit unit = Unit.valueOf(match.group(2));
            return value * unit.bytes;
        }
        return Long.parseLong(humanReadableSize);
    }
}
