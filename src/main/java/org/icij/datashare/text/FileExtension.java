package org.icij.datashare.text;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;


public class FileExtension {
    private static final Properties mimeTypesExtensions;

    public static String get(final String mimeType) {
        return mimeTypesExtensions.getProperty(mimeType, "bin");
    }

    static {
        mimeTypesExtensions = new Properties();
        try {
            mimeTypesExtensions.load(ClassLoader.getSystemResourceAsStream("fileExtensions.properties"));
        } catch (IOException e) {
            LoggerFactory.getLogger(FileExtension.class).warn("cannot find fileExtensions.properties in classpath");
        }
    }
}
