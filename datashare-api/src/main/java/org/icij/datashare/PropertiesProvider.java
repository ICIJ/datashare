package org.icij.datashare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import static java.lang.Character.toUpperCase;
import static java.lang.System.getenv;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

public class PropertiesProvider {
    private static final String PREFIX = "DS_DOCKER_";
    private static final String DEFAULT_DATASHARE_PROPERTIES_FILE_NAME = "datashare.properties";
    public static final String CONFIG_FILE_PARAMETER_KEY = "configFile";
    private Logger logger = LoggerFactory.getLogger(getClass());
    private final String fileName;
    private volatile Properties cachedProperties;

    public PropertiesProvider() {fileName = DEFAULT_DATASHARE_PROPERTIES_FILE_NAME;}
    public PropertiesProvider(String fileName) {
        this.fileName = ofNullable(fileName).orElse(DEFAULT_DATASHARE_PROPERTIES_FILE_NAME);
    }
    public PropertiesProvider(final Properties properties) {
        this.cachedProperties = properties;
        fileName = null;
    }
    public PropertiesProvider(final Map<String, String> hashMap) {
        cachedProperties = new Properties();
        cachedProperties.putAll(hashMap);
        fileName = null;
    }

    public static Properties fromMap(Map<String, String> map) {
        if (map == null) return null;
        Properties properties = new Properties();
        properties.putAll(map);
        return properties;
    }

    public Properties getProperties() {
        if (cachedProperties == null) {
            synchronized(this) {
                if (cachedProperties == null) {
                    Properties localProperties = new Properties();
                    try {
                        InputStream propertiesStream;
                        if (Files.isReadable(Paths.get(fileName))) {
                            propertiesStream = Files.newInputStream(Paths.get(fileName));
                        } else {
                            propertiesStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);
                        }
                        logger.info("reading properties from {}", fileName);
                        localProperties.load(propertiesStream);
                        loadEnvVariables(localProperties);
                    } catch (IOException | NullPointerException e) {
                        logger.warn("no {} file found, using default values", fileName);
                    }
                    cachedProperties = localProperties;
                }
            }
        }
        return cachedProperties;
    }

    private void loadEnvVariables(Properties properties) {
        Map<String, String> envVars = getenv().entrySet().stream().filter(entry -> entry.getKey().startsWith(PREFIX)).
                collect(toMap(k -> camelCasify(k.getKey().replace(PREFIX, "")), Map.Entry::getValue));
        logger.info("adding properties from env vars {}", envVars);
        properties.putAll(envVars);
    }

    private String camelCasify(String str) {
        String[] stringParts = str.toLowerCase().split("_");
        return stringParts[0] + stream(stringParts).skip(1).
                map(s -> toUpperCase(s.charAt(0)) + s.substring(1)).
                collect(joining());
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

    public Map<String, Object> getFilteredProperties(String... excludedKeyPatterns) {
        return getProperties().entrySet().
                stream().filter(e -> stream(excludedKeyPatterns).noneMatch(s -> Pattern.matches(s, (String)e.getKey()))).
                collect(toMap(e -> (String)e.getKey(), Map.Entry::getValue));
    }

    public void save() throws IOException {
        Path configFile = Paths.get(get(CONFIG_FILE_PARAMETER_KEY).orElse("./" + DEFAULT_DATASHARE_PROPERTIES_FILE_NAME));
        getProperties().store(new FileOutputStream(configFile.toFile()), "Datashare properties");
    }
}
