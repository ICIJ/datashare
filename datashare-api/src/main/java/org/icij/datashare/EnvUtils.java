package org.icij.datashare;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;

public class EnvUtils {
    private static final Properties envProperties = new Properties();

    static {
        getEnvFile().ifPresent((f) ->
            {
                if (f.toFile().exists()) {
                    try (FileInputStream input = new FileInputStream(f.toFile())) {
                        envProperties.load(input);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to dev env configuration", e);
                    }
                }
            }
        );
    }

    /**
     * Resolves the value of a property by its name from the loaded environment properties.
     *
     * @param propertyName the name of the property to resolve
     * @return the value of the property, or {@code null} if not found
     */
    public static Object resolve(String propertyName) {
        return envProperties.get(propertyName);
    }

    /**
     * Resolves the value of a property by its name from the loaded environment properties.
     * If the property is not found, returns the provided default value.
     *
     * @param propertyName the name of the property to resolve
     * @param defaultValue the default value to return if the property is not found
     * @return the value of the property, or {@code null} if not found
     */
    public static String resolve(String propertyName, String defaultValue) {
        return Optional.ofNullable(resolve(propertyName)).map(String::valueOf).orElse(defaultValue);
    }


    /**
     * Resolves the URI for a given service key from the environment properties.
     * If the property is not found, returns the provided default URI.
     *
     * @param serviceKey the key identifying the service (property name will be {@code serviceKey + "Uri"})
     * @param defaultUri the default URI to return if the property is not found
     * @return the resolved URI as a string, or {@code defaultUri} if not found
     */
    public static String resolveUri(String serviceKey, String defaultUri) {
        return resolve(serviceKey + "Uri", defaultUri);
    }

    /**
     * Retrieves the path to the environment configuration file, if specified.
     *
     * @return an {@link Optional} containing the path to the environment file, or empty if not specified
     */
    private static Optional<Path> getEnvFile() {
        return Optional.ofNullable(System.getProperty("devenv.file")).map(Paths::get);
    }
}
