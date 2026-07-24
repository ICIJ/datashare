package org.icij.datashare.extract;

import java.nio.file.Path;

/**
 * Called by {@link Scanner} once for each file found while scanning a directory tree.
 */
public interface ScanCallback {
    void onFile(Path path) throws Exception;
}
