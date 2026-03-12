package org.icij.datashare.web;

import static java.util.Optional.ofNullable;

public record WebResponseRange(int from, int size) {

    public WebResponseRange {
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0");
        }
    }

    public WebResponseRange(String from, String size) {
        this(parseValue(from), parseValue(size));
    }

    private static int parseValue(String value) {
        return Integer.parseInt(ofNullable(value).orElse("0"));
    }
}