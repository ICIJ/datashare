package org.icij.datashare.tabular;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Records whether the reader closed the stream it was handed, which is the RowSource contract. */
class TrackingInputStream extends ByteArrayInputStream {
    boolean closed = false;

    TrackingInputStream(byte[] content) {
        super(content);
    }

    TrackingInputStream(String content) {
        this(content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        closed = true;
        super.close();
    }
}
