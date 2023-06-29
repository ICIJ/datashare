package org.icij.datashare.utils;

import org.icij.datashare.PropertiesProvider;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DataDirVerifier {
    final PropertiesProvider propertiesProvider;

    public DataDirVerifier(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    public String value () {
        return propertiesProvider.get("dataDir").orElse("/home/datashare/data");
    }

    public Path path () {
        return Paths.get(this.value());
    }

    public boolean allowed (Path path) {
        return path.equals(this.path()) || path.startsWith(this.path());
    }
}
