package org.icij.datashare.text.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** The datashare version, read once from a resource maven filters at build time. Artifacts record it
 *  in their taskInput, so skip-if-current sees a payload an earlier release rendered as stale. */
class DatashareVersion {
    // A filtered resource, not the jar manifest or META-INF/maven/.../pom.properties: those only exist
    // once the module is packaged, so the lookup would yield null in target/classes, where the tests run.
    private static final String RESOURCE = "/datashare-version.properties";
    static final String VALUE = read();

    private DatashareVersion() {}

    // Throws rather than degrading to "unknown": a null or an unfiltered "${project.version}" in a
    // taskInput would silently freeze skip-if-current on every document.
    private static String read() {
        Properties properties = new Properties();
        try (InputStream resource = DatashareVersion.class.getResourceAsStream(RESOURCE)) {
            properties.load(Objects.requireNonNull(resource, RESOURCE + " is not on the classpath"));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + RESOURCE, failure);
        }
        String version = properties.getProperty("version", "");
        if (!version.matches("\\d.*")) {
            throw new IllegalStateException(RESOURCE + " was not filtered by maven: version='" + version + "'");
        }
        return version;
    }
}
