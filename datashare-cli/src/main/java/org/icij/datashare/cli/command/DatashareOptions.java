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
 * Applies transformations to the properties collected by picocli before they
 * reach the application layer. This mirrors the equivalent logic in the legacy
 * DatashareCli.parseArguments path so that both invocation styles produce
 * identical properties for downstream consumers.
 *
 * Called once by DatashareCommand.collectProperties() after merging global
 * and subcommand properties.
 */
public final class DatashareOptions {

    private DatashareOptions() {}

    /**
     * Normalizes the raw picocli properties so the application layer receives
     * a consistent set of key/value pairs regardless of which CLI path was used.
     *
     * Three transformations are applied in order:
     *
     * 1. Digest project name defaulting — when the user has not explicitly
     *    disabled digest-project (--noDigestProject) and has not set a custom
     *    digest project name, the default project name is copied into
     *    digestProjectName. This ensures document hashes are project-scoped
     *    by default.
     *
     * 2. OAuth system property propagation — if the user overrides
     *    --oauthUserProjectsAttribute, the value is also set as the JVM
     *    system property so that the User class can read it at runtime
     *    without accessing the properties directly.
     *
     * 3. Port alias resolution — the legacy CLI accepted --port which the
     *    application layer expects as tcpListenPort. This renames the key
     *    so downstream code does not need to know about the alias.
     */
    public static void postProcess(Properties props) {
        if (!Boolean.parseBoolean(props.getProperty(NO_DIGEST_PROJECT_OPT))
                && props.getProperty(DIGEST_PROJECT_NAME_OPT) == null) {
            String defaultDigestProjectName = ofNullable(props.getProperty(DEFAULT_PROJECT_OPT))
                    .filter(s -> !s.isEmpty())
                    .orElse("local-datashare");
            props.setProperty(DIGEST_PROJECT_NAME_OPT, defaultDigestProjectName);
        }

        String projectsAttr = props.getProperty(OAUTH_USER_PROJECTS_KEY_OPT);
        if (projectsAttr != null && !User.DEFAULT_PROJECTS_KEY.equals(projectsAttr)) {
            System.setProperty(User.JVM_PROJECT_KEY, projectsAttr);
        }

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
