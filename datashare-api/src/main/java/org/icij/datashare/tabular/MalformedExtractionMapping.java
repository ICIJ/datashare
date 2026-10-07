package org.icij.datashare.tabular;

public class MalformedExtractionMapping extends IllegalArgumentException {
    public final String reason;

    public MalformedExtractionMapping(String reason) {
        this(reason, null);
    }

    public MalformedExtractionMapping(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }
}
