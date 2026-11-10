package org.icij.datashare;

import org.simpleframework.common.buffer.Allocator;
import org.simpleframework.common.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounds a request body while simple-http stores it, which happens before any fluent-http filter
 * runs. A declared Content-Length above the limit is refused before a byte is stored. A chunked
 * body has no declared size, so its buffer counts what is appended. Each body gets its own count,
 * shared with the child buffers simple-http carves out of it for multipart parts.
 */
class MaxBodySizeAllocator implements Allocator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaxBodySizeAllocator.class);
    private final Allocator source;
    private final long maxBytes;

    MaxBodySizeAllocator(Allocator source, long maxBytes) {
        this.source = source;
        this.maxBytes = maxBytes;
    }

    @Override
    public Buffer allocate() throws IOException {
        Buffer buffer = source.allocate();
        return countedBuffer(buffer);
    }

    @Override
    public Buffer allocate(long size) throws IOException {
        if (size > maxBytes) {
            throw refuse(size);
        }
        Buffer buffer = source.allocate(size);
        return countedBuffer(buffer);
    }

    private Buffer countedBuffer(Buffer buffer) {
        AtomicLong bodyBytes = new AtomicLong();
        return new CountedBuffer(buffer, bodyBytes);
    }

    private BodyTooLargeException refuse(long size) {
        LOGGER.warn("rejecting request body of {} bytes: above the {}-byte limit", size, maxBytes);
        return new BodyTooLargeException(size, maxBytes);
    }

    private class CountedBuffer implements Buffer {
        private final Buffer delegate;
        private final AtomicLong bodyBytes;

        CountedBuffer(Buffer delegate, AtomicLong bodyBytes) {
            this.delegate = delegate;
            this.bodyBytes = bodyBytes;
        }

        @Override
        public Buffer allocate() throws IOException {
            Buffer child = delegate.allocate();
            return new CountedBuffer(child, bodyBytes);
        }

        @Override
        public Buffer append(byte[] bytes) throws IOException {
            return append(bytes, 0, bytes.length);
        }

        @Override
        public Buffer append(byte[] bytes, int offset, int length) throws IOException {
            long total = bodyBytes.addAndGet(length);
            if (total > maxBytes) {
                throw refuse(total);
            }
            delegate.append(bytes, offset, length);
            return this;
        }

        @Override
        public InputStream open() throws IOException {
            return delegate.open();
        }

        @Override
        public String encode() throws IOException {
            return delegate.encode();
        }

        @Override
        public String encode(String charset) throws IOException {
            return delegate.encode(charset);
        }

        @Override
        public void clear() throws IOException {
            delegate.clear();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public long length() {
            return delegate.length();
        }
    }
}
