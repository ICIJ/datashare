package org.icij.datashare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import static java.lang.Character.toUpperCase;
import static java.lang.System.getenv;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

public class PropertiesProvider {
    private static final String PREFIX = "DS_DOCKER_";
    private static final String DEFAULT_DATASHARE_PROPERTIES_FILE_NAME = "datashare.properties";
    public static final String CONFIG_FILE_PARAMETER_KEY = "configFile";
    private Logger logger = LoggerFactory.getLogger(getClass());
    private final Path configPath;
    private volatile Properties cachedProperties;

    public PropertiesProvider() {this((String) null);}
    public PropertiesProvider(String fileName) {
        this.configPath = getFilePath(fileName);
    }

    public PropertiesProvider(final Properties properties) {
        this.cachedProperties = properties;
        configPath = null;
    }

    public PropertiesProvider(final Map<String, String> hashMap) {
        cachedProperties = fromMap(hashMap);
        configPath = null;
    }

    public Properties getProperties() {
        if (cachedProperties == null) {
            synchronized(this) {
                if (cachedProperties == null) {
                    Properties localProperties = new Properties();
                    try {
                        InputStream propertiesStream = new FileInputStream(configPath.toFile());
                        logger.info("reading properties from {}", configPath);
                        localProperties.load(propertiesStream);
                        loadEnvVariables(localProperties);
                    } catch (IOException | NullPointerException e) {
                        logger.warn("no {} file found, using default values", configPath);
                    }
                    cachedProperties = localProperties;
                    logger.info("properties set to {}", cachedProperties);
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
        putAllIfIsAbsent(getProperties(), properties);
        logger.info("merged properties (without override) with {}", properties);
        return this;
    }

    public PropertiesProvider overrideWith(final Properties properties) {
        logger.info("overriding properties with {}", properties);
        getProperties().putAll(properties);
        return this;
    }

    public Properties createMerged(Properties properties) {
        Properties mergedProperties = (Properties) getProperties().clone();
        putAllIfIsAbsent(mergedProperties, properties);
        return mergedProperties;
    }

    public Map<String, Object> getFilteredProperties(String... excludedKeyPatterns) {
        return getProperties().entrySet().
                stream().filter(e -> stream(excludedKeyPatterns).noneMatch(s -> Pattern.matches(s, (String)e.getKey()))).
                collect(toMap(e -> (String)e.getKey(), Map.Entry::getValue));
    }

    public void save() throws IOException {
        logger.info("writing properties to file {}", configPath);
        if (configPath == null) {
            throw new ConfigurationNotFound();
        }
        Properties toSave = new Properties();
        toSave.putAll(getFilteredProperties("user.*"));
        toSave.store(new FileOutputStream(configPath.toFile()), "Datashare properties");
    }

    public static Properties fromMap(Map<String, String> map) {
        if (map == null) return null;
        Properties properties = new Properties();
        properties.putAll(map);
        return properties;
    }

    private void putAllIfIsAbsent(Properties dest, Properties propertiesToMerge) {
        for (Map.Entry entry: propertiesToMerge.entrySet()) {
            dest.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private Path getFilePath(String fileName) {
        Path path;
        if (fileName == null) {
            URL url = Thread.currentThread().getContextClassLoader().getResource(DEFAULT_DATASHARE_PROPERTIES_FILE_NAME);
            if (url == null) return null;
            path = Paths.get(url.getPath());
        } else {
            path = Paths.get(fileName);
        }
        return isFileReadable(path) ? path : null;
    }

    boolean isFileReadable(final Path filePath) {
        if (filePath.toFile().exists()) {
            if (!filePath.toFile().canWrite()) {
                logger.warn("{} is not writable. The config file won't be able to be saved", filePath);
            }
            return true;
        } else {
            try {
                filePath.toFile().createNewFile();
                filePath.toFile().delete();
                return true;
            } catch (IOException e) {
                logger.warn("{} is not writable. The config file won't be able to be saved", filePath);
                return false;
            }
        }
    }

    public static class ConfigurationNotFound extends RuntimeException {
        ConfigurationNotFound() { super("cannot find configuration file");}
    }
}
