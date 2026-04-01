package org.icij.datashare.cli.command;

import org.icij.datashare.user.User;

import java.util.Properties;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.PropertiesProvider.DIGEST_PROJECT_NAME_OPT;

/**
 * Static utility for post-processing picocli-collected properties.
 * Handles digest project name defaulting, OAuth system property, and option alias resolution.
 */
public final class DatashareOptions {

    private DatashareOptions() {}

    /**
     * Post-process properties to apply digest project name defaulting,
     * alias mapping, and system property settings.
     */
    public static void postProcess(Properties props) {
        // digestProjectName defaulting (same logic as DatashareCli.parseArguments)
        if (!Boolean.parseBoolean(props.getProperty("noDigestProject"))
                && props.getProperty(DIGEST_PROJECT_NAME_OPT) == null) {
            String defaultDigestProjectName = ofNullable(props.getProperty(DEFAULT_PROJECT_OPT))
                    .filter(s -> !s.isEmpty())
                    .orElse("local-datashare");
            props.setProperty(DIGEST_PROJECT_NAME_OPT, defaultDigestProjectName);
        }

        // oauthUserProjectsAttribute system property
        String projectsAttr = props.getProperty("oauthUserProjectsAttribute");
        if (projectsAttr != null && !User.DEFAULT_PROJECTS_KEY.equals(projectsAttr)) {
            System.setProperty(User.JVM_PROJECT_KEY, projectsAttr);
        }

        // Alias mapping (port -> tcpListenPort)
        if (props.containsKey("port")) {
            props.setProperty("tcpListenPort", props.getProperty("port"));
            props.remove("port");
        }
    }

    static void putIfNotNull(Properties props, String key, String value) {
        if (value != null) {
            props.setProperty(key, value);
        }
    }
}
