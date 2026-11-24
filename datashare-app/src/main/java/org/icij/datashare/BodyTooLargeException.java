package org.icij.datashare;

import java.io.IOException;

public class BodyTooLargeException extends IOException {
    public BodyTooLargeException(long size, long maxBytes) {
        super(String.format("Request body of %s bytes is above the %s bytes limit", size, maxBytes));
    }

    public BodyTooLargeException(long maxBytes) {
        super(String.format("Request body is above the %s byte limit", maxBytes));
    }
}
