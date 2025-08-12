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
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Character.toUpperCase;
import static java.lang.System.getenv;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

public class PropertiesProvider {
    public static final String PLUGINS_DIR = "pluginsDir";
    public static final String EXTENSIONS_DIR = "extensionsDir";
    public static final String TCP_LISTEN_PORT = "tcpListenPort";
    private static final String PREFIX = "DS_DOCKER_";
    private static final String DEFAULT_DATASHARE_PROPERTIES_FILE_NAME = "datashare.properties";
    public static final String SETTINGS_FILE_PARAMETER_KEY = "settings";
    public static final String QUEUE_NAME_OPTION = "queueName";
    public static final String SET_NAME_OPTION = "filterSet";
    public static final String MAP_NAME_OPTION = "reportName";
    public static final String DATA_DIR_OPTION = "dataDir";
    public static final String DEFAULT_PROJECT_OPTION = "defaultProject";
    public static final String DIGEST_PROJECT_NAME_OPTION = "digestProjectName";
    public static final String RESUME_OPTION = "resume";
    public static final String SYNC_MODELS_OPTION = "syncModels";
    public static final List<String> QUEUE_HASH_PROPERTIES = List.of(DATA_DIR_OPTION);
    public static final String QUEUE_SEPARATOR = ":";


    private Logger logger = LoggerFactory.getLogger(getClass());
    private final Path settingsPath;
    private volatile Properties cachedProperties;

    public PropertiesProvider() {this((String) null);}
    public PropertiesProvider(String fileName) {
        this.settingsPath = getFilePath(fileName);
    }

    public PropertiesProvider(final Properties properties) {
        this.cachedProperties = properties;
        settingsPath = null;
    }

    public PropertiesProvider(final Map<String, Object> hashMap) {
        cachedProperties = fromMap(hashMap);
        settingsPath = null;
    }

    public static PropertiesProvider ofNullable(final Map<String, Object> hashMap) {
        Map<String, Object> collect = hashMap.entrySet().stream().filter(e -> e.getValue() != null)
            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new PropertiesProvider(collect);
    }

    public static Map<String, Object> propertiesToMap(final Properties properties) {
        return properties.entrySet().stream().collect(
                Collectors.toMap(
                        e -> String.valueOf(e.getKey()),
                        Map.Entry::getValue,
                        (prev, next) -> next, HashMap::new
                ));
    }

    public Properties getProperties() {
        if (cachedProperties == null) {
            synchronized(this) {
                if (cachedProperties == null) {
                    Properties localProperties = new Properties();
                    try {
                        InputStream propertiesStream = new FileInputStream(settingsPath.toFile());
                        logger.info("reading properties from {}", settingsPath);
                        localProperties.load(propertiesStream);
                    } catch (IOException | NullPointerException e) {
                        logger.warn("no {} file found, using default values", settingsPath);
                    }
                    loadEnvVariables(localProperties);
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

    public PropertiesProvider overrideWith(final PropertiesProvider propertiesProvider) {
        return this.overrideWith(propertiesProvider.getProperties());
    }

    public PropertiesProvider overrideQueueNameWithHash() {
        Map<String, String> map = Map.of(QUEUE_NAME_OPTION, queueNameWithHash());
        return new PropertiesProvider(createOverriddenWith(map));
    }

    public PropertiesProvider overrideQueueNameWithHash(String defaultProject) {
        Properties properties = createOverriddenWith(Map.of(DEFAULT_PROJECT_OPTION, defaultProject));
        PropertiesProvider propertiesProvider = new PropertiesProvider(properties);
        return propertiesProvider.overrideQueueNameWithHash();
    }

    public Properties createOverriddenWith(Map<String, String> map) {
        Properties overriddenProperties = (Properties) getProperties().clone();
        overriddenProperties.putAll(map);
        return overriddenProperties;
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
        logger.info("writing properties to file {}", settingsPath);
        if (settingsPath == null) {
            throw new SettingsNotFound();
        }
        Properties toSave = new Properties();
        toSave.putAll(getFilteredProperties("user.*"));
        toSave.store(new FileOutputStream(settingsPath.toFile()), "Datashare properties");
    }

    public static Properties fromMap(Map<String, Object> map) {
        if (map == null) return null;
        Properties properties = new Properties();
        properties.putAll(map);
        return properties;
    }

    public Object setProperty(String key, String value) {
        return getProperties().setProperty(key, value);
    }

    public List<String> queueHashProperties() {
        return QUEUE_HASH_PROPERTIES.stream()
                .map(p -> get(p).orElse("-"))
                .map(String::hashCode)
                .map(String::valueOf)
                .collect(Collectors.toList());
    }

    public String queueHash() {
        String defaultProject = get(DEFAULT_PROJECT_OPTION).orElse("-");
        return Stream.concat(Stream.of(defaultProject), queueHashProperties().stream())
                .collect(Collectors.joining(QUEUE_SEPARATOR));
    }

    public String queueNameWithHash() {
        return queueName() + QUEUE_SEPARATOR + queueHash();
    }

    public String queueName() {
        return get(QUEUE_NAME_OPTION).orElse("extract:queue");
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
                logger.warn("{} is not writable. The settings file won't be able to be saved", filePath);
            }
            return true;
        } else {
            try {
                filePath.toFile().createNewFile();
                filePath.toFile().delete();
                return true;
            } catch (IOException e) {
                logger.warn("{} is not writable. The settings file won't be able to be saved", filePath);
                return false;
            }
        }
    }

    public static class SettingsNotFound extends RuntimeException {
        SettingsNotFound() { super("cannot find settings file");}
    }
}
