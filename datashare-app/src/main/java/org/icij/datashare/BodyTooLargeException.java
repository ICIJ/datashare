package org.icij.datashare;

import org.simpleframework.common.buffer.BufferException;

/** A {@link BufferException} so simple-http drops the connection, the only refusal it offers mid-body. */
class BodyTooLargeException extends BufferException {
    final long size;

    BodyTooLargeException(long size, long maxBytes) {
        super("request body of %s bytes is above the %s-byte limit", size, maxBytes);
        this.size = size;
    }
}
