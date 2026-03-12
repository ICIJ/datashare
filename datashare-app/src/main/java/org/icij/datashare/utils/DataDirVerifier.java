package org.icij.datashare.utils;

import org.icij.datashare.PropertiesProvider;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.icij.datashare.PropertiesProvider.DATA_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DATA_DIR;

public class DataDirVerifier {
    final PropertiesProvider propertiesProvider;

    public DataDirVerifier(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    public String value () {
        return propertiesProvider.get(DATA_DIR_OPT).orElse(DEFAULT_DATA_DIR);
    }

    public Path path () {
        return Paths.get(this.value());
    }

    public boolean allowed (Path path) {
        return path.equals(this.path()) || path.startsWith(this.path());
    }
}
