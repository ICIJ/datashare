package org.icij.datashare.cli.command;

import org.icij.datashare.user.User;

import java.util.Properties;

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
        // Default the digest project name to the current project so document
        // hashes are project-scoped, unless the user explicitly disabled it.
        boolean isDigestProjectDisabled = Boolean.parseBoolean(props.getProperty(NO_DIGEST_PROJECT_OPT));
        boolean hasDigestProjectName = props.getProperty(DIGEST_PROJECT_NAME_OPT) != null;
        if (!isDigestProjectDisabled && !hasDigestProjectName) {
            String projectName = props.getProperty(DEFAULT_PROJECT_OPT);
            boolean hasProjectName = projectName != null && !projectName.isEmpty();
            String digestProjectName = hasProjectName ? projectName : "local-datashare";
            props.setProperty(DIGEST_PROJECT_NAME_OPT, digestProjectName);
        }

        // The User class reads the projects key from a JVM system property
        // (System.getProperty) rather than from the Properties map. This bridges
        // the two: without it, User.getDefaultProjectsKey() would always fall
        // back to the hardcoded default "groups_by_applications.datashare".
        String projectsAttribute = props.getProperty(OAUTH_USER_PROJECTS_KEY_OPT);
        boolean hasCustomProjectsAttribute = projectsAttribute != null
                && !User.DEFAULT_PROJECTS_KEY.equals(projectsAttribute);
        if (hasCustomProjectsAttribute) {
            System.setProperty(User.JVM_PROJECT_KEY, projectsAttribute);
        }

        // Resolve the legacy --port alias to the canonical tcpListenPort key
        // so downstream code only needs to look up one property name.
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

    static <E extends Enum<E>> void putIfNotNull(Properties props, String key, E value) {
        if (value != null) {
            props.setProperty(key, value.name());
        }
    }

    static void putIfNotNull(Properties props, String key, Object value) {
        if (value != null) {
            props.setProperty(key, String.valueOf(value));
        }
    }

    static void put(Properties props, String key, Object value) {
        props.setProperty(key, String.valueOf(value));
    }

    static void putAll(Properties props, Properties source) {
        if (source != null) {
            props.putAll(source);
        }
    }

    static void putIfTrue(Properties props, String key, boolean value) {
        if (value) {
            props.setProperty(key, "true");
        }
    }

    static void putAll(Properties props, DatashareSubcommand subcommand) {
        if (subcommand != null) {
            props.putAll(subcommand.getSubcommandProperties());
        }
    }
}
