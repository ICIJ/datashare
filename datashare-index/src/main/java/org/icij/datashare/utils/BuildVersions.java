package org.icij.datashare.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** Build versions of the code that renders artifact payloads, read once from a resource maven filters
 *  at build time. Artifacts record them in their taskInput, so skip-if-current sees a payload an
 *  earlier release rendered as stale. */
public class BuildVersions {
    // A filtered resource, not the jar manifest or META-INF/maven/.../pom.properties: those only exist
    // once the module is packaged, so the lookup would yield null in target/classes, where the tests run.
    private static final String RESOURCE = "/build-versions.properties";
    private static final Properties VERSIONS = read();
    public static final String DATASHARE = version("datashare");
    public static final String EXTRACT = version("extract");

    private BuildVersions() {}

    private static Properties read() {
        Properties properties = new Properties();
        try (InputStream resource = BuildVersions.class.getResourceAsStream(RESOURCE)) {
            properties.load(Objects.requireNonNull(resource, RESOURCE + " is not on the classpath"));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + RESOURCE, failure);
        }
        return properties;
    }

    // Throws rather than degrading to "unknown": a null or an unfiltered "${project.version}" in a
    // taskInput would silently freeze skip-if-current on every document.
    private static String version(String name) {
        String version = VERSIONS.getProperty(name, "");
        if (!version.matches("\\d.*")) {
            throw new IllegalStateException(RESOURCE + " was not filtered by maven: " + name + "='" + version + "'");
        }
        return version;
    }
}
