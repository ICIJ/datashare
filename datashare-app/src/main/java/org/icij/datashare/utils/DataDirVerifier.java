package org.icij.datashare.utils;

import org.icij.datashare.PropertiesProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.icij.datashare.PropertiesProvider.DATA_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DATA_DIR;

public class DataDirVerifier {
    final PropertiesProvider propertiesProvider;

    public DataDirVerifier(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    public String value() {
        return propertiesProvider.get(DATA_DIR_OPT).orElse(DEFAULT_DATA_DIR);
    }

    public Path path() {
        return Paths.get(this.value());
    }

    public boolean allowed(Path path) {
        return real(path).startsWith(real(this.path()));
    }

    /** Throws when the path is outside the data directory; the message names both. */
    public void check(Path path) {
        if (!allowed(path)) {
            throw new IllegalArgumentException(path + " must be inside the data directory " + value());
        }
    }

    // Links are resolved on the longest part of the path that exists, so a path that is not created yet can
    // still be checked. The rest is appended and normalized.
    private static Path real(Path path) {
        Path absolute = path.toAbsolutePath();
        for (Path existing = absolute; existing != null; existing = existing.getParent()) {
            try {
                Path real = existing.toRealPath();
                int count = existing.getNameCount();
                return count == absolute.getNameCount() ? real :
                       real.resolve(absolute.subpath(count, absolute.getNameCount())).normalize();
            } catch (IOException notThere) {
                // try the parent
            }
        }
        return absolute.normalize();
    }
}
