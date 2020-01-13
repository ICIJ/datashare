package org.icij.datashare.text.indexing.elasticsearch;

public class ExtractException extends RuntimeException {
    public ExtractException(String reason, Throwable source) {
        super(reason, source);
    }
}
