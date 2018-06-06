package org.icij.datashare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Optional;
import java.util.Properties;

public class PropertiesProvider {
    private Logger logger = LoggerFactory.getLogger(getClass());
    static String DEFAULT_NAME="datashare.properties";
    private final String fileName;
    private Properties cachedProperties;

    public PropertiesProvider() { fileName = DEFAULT_NAME;}
    public PropertiesProvider(String fileName) {
        this.fileName = fileName;
    }
    public PropertiesProvider(final Properties properties) {
        this.cachedProperties = properties;
        fileName = null;
    }
    public PropertiesProvider(final HashMap<String, String> hashMap) {
        cachedProperties = new Properties();
        cachedProperties.putAll(hashMap);
        fileName = null;
    }

    public Properties getProperties() {
        if (cachedProperties == null) {
            cachedProperties = new Properties();
            URL propertiesUrl = Thread.currentThread().getContextClassLoader().getResource(fileName);
            try {
                logger.info("reading properties from {}", propertiesUrl);
                cachedProperties.load(propertiesUrl.openStream());
            } catch (IOException|NullPointerException e) {
                logger.warn("no datashare.properties found, using empty properties");
            }
        }
        return cachedProperties;
    }

    public Optional<String> get(final String propertyName) {
        return getProperties().getProperty(propertyName) == null ?
                Optional.empty():
                Optional.of((getProperties().getProperty(propertyName)));
    }

    public PropertiesProvider mergeWith(final Properties properties) {
        getProperties().putAll(properties);
        return this;
    }

    public Properties createMerged(Properties properties) {
        Properties mergedProperties = (Properties) getProperties().clone();
        mergedProperties.putAll(properties);
        return mergedProperties;
    }
}
