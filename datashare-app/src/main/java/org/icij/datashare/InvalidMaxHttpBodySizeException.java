package org.icij.datashare;

import static org.icij.datashare.cli.DatashareCliOptions.MAX_HTTP_BODY_SIZE_OPT;

class InvalidMaxHttpBodySizeException extends RuntimeException {
    final String value;

    InvalidMaxHttpBodySizeException(String value, Throwable cause) {
        super("invalid %s \"%s\": expected a number of bytes".formatted(MAX_HTTP_BODY_SIZE_OPT, value), cause);
        this.value = value;
    }
}
