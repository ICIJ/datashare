package org.icij.datashare.cli.command;

import org.icij.datashare.user.User;

import java.util.Properties;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.PropertiesProvider.DIGEST_PROJECT_NAME_OPT;
import static org.icij.datashare.PropertiesProvider.TCP_LISTEN_PORT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NO_DIGEST_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.OAUTH_USER_PROJECTS_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PORT_OPT;

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
        if (!Boolean.parseBoolean(props.getProperty(NO_DIGEST_PROJECT_OPT))
                && props.getProperty(DIGEST_PROJECT_NAME_OPT) == null) {
            String defaultDigestProjectName = ofNullable(props.getProperty(DEFAULT_PROJECT_OPT))
                    .filter(s -> !s.isEmpty())
                    .orElse("local-datashare");
            props.setProperty(DIGEST_PROJECT_NAME_OPT, defaultDigestProjectName);
        }

        // oauthUserProjectsAttribute system property
        String projectsAttr = props.getProperty(OAUTH_USER_PROJECTS_KEY_OPT);
        if (projectsAttr != null && !User.DEFAULT_PROJECTS_KEY.equals(projectsAttr)) {
            System.setProperty(User.JVM_PROJECT_KEY, projectsAttr);
        }

        // Alias mapping (port -> tcpListenPort)
        if (props.containsKey(PORT_OPT)) {
            props.setProperty(TCP_LISTEN_PORT_OPT, props.getProperty(PORT_OPT));
            props.remove(PORT_OPT);
        }
    }

    static void putIfNotNull(Properties props, String key, String value) {
        if (value != null) {
            props.setProperty(key, value);
        }
    }
}
