package org.icij.datashare;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class PropertiesProvider {
    static String DEFAULT_NAME="datashare.properties";
    private final String fileName;

    public PropertiesProvider() {
        fileName = DEFAULT_NAME;
    }

    public PropertiesProvider(String fileName) {
        this.fileName = fileName;
    }

    public Properties getProperties() throws IOException {
        Properties prop = new Properties();
        InputStream inStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);
        if (inStream == null) {
            throw new IllegalArgumentException(fileName + " not found in classpath");
        }
        prop.load(inStream);
        return prop;
    }
}
