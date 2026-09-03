package org.icij.datashare.tabular;

public class UnusableDateFormat extends IllegalArgumentException {
    public UnusableDateFormat(String pattern, String because) {
        super("'%s' %s".formatted(pattern, because));
    }
}
