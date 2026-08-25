package org.icij.datashare.tabular;

public class InvalidPropertyMapping extends IllegalArgumentException {
    public final String reason;

    public InvalidPropertyMapping(String reason, String message) {
        super(message);
        this.reason = reason;
    }
}
