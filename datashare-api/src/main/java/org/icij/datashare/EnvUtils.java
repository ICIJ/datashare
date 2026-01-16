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
                try (FileInputStream input = new FileInputStream(f.toFile())) {
                    envProperties.load(input);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to dev env configuration", e);
                }
            }
        );
    }

    public static Object resolve(String propertyName) {
        return envProperties.get(propertyName);
    }

    public static String resolveHost(String hostKey) {
        return Optional.ofNullable(resolve(hostKey + "Host")).map(String::valueOf).orElse(hostKey);
    }

    private static Optional<Path> getEnvFile() {
        return Optional.ofNullable(System.getProperty("devenv.file")).map(Paths::get);
    }
}
