package org.icij.datashare.tabular;

import java.io.IOException;
import java.io.InputStream;

/**
 * Streams the rows of a tabular source with its columns intact. Implementations are stateless and
 * reusable; each call to {@link #rows} owns the stream it is given and releases it when the returned
 * {@code Rows} is closed, so callers must use try-with-resources.
 */
public interface RowSource {
    boolean supports(String contentType);

    Rows rows(InputStream source, RowSourceOptions options) throws IOException;
}
