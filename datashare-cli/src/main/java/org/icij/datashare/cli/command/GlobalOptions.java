package org.icij.datashare.cli.command;

import org.icij.datashare.EnvUtils;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Properties;

import static org.icij.datashare.PropertiesProvider.DATA_DIR_OPT;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.PropertiesProvider.DIGEST_PROJECT_NAME_OPT;
import static org.icij.datashare.PropertiesProvider.EXTENSIONS_DIR_OPT;
import static org.icij.datashare.PropertiesProvider.PLUGINS_DIR_OPT;
import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPT;
import static org.icij.datashare.PropertiesProvider.SETTINGS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.*;

/**
 * Global options defined on the root command.
 * They must be specified before the subcommand name, e.g.:
 *   datashare --elasticsearchAddress http://... app start
 *
 * They are NOT inherited into subcommands so that subcommand help pages
 * only show options that are relevant to that specific command.
 */
public class GlobalOptions {

    private static String home() { return System.getProperty("user.home", ""); }

    @Option(names = {"-s", "--settings"}, description = "Property settings file")
    String settings;

    @Option(names = {"--logLevel"}, description = "Log level", defaultValue = "INFO")
    String logLevel;

    @Option(names = {"--charset"}, description = "Datashare default charset")
    String charset = Charset.defaultCharset().toString();

    @Option(names = {"-P", "--defaultProject"}, description = "Default project name", defaultValue = "local-datashare")
    String defaultProject;

    @Option(names = {"--digestAlgorithm"}, description = "Digest algorithm", defaultValue = "SHA_384")
    String digestAlgorithm;

    @Option(names = {"--digestProjectName"}, description = "Include project name in document hash")
    String digestProjectName;

    @Option(names = {"--noDigestProject"}, description = "Disable project name in document hash", defaultValue = "false")
    boolean noDigestProject;

    @Option(names = {"--elasticsearchAddress"}, description = "Elasticsearch host address")
    String elasticsearchAddress = EnvUtils.resolveUri("elasticsearch", "http://elasticsearch:9200");

    @Option(names = {"--elasticsearchPath"}, description = "Path for launching Elasticsearch")
    String elasticsearchPath = Paths.get(home(), ".local/share/datashare", "elasticsearch").toString();

    @Option(names = {"--elasticsearchDataPath"}, description = "Data path for embedded Elasticsearch")
    String elasticsearchDataPath = Paths.get(home(), ".local/share/datashare", "es").toString();

    @Option(names = {"--elasticsearchSettings"}, description = "Path to elasticsearch.yml settings")
    String elasticsearchSettings = Paths.get(home(), ".local/share/datashare", "elasticsearch.yml").toString();

    @Option(names = {"--redisAddress"}, description = "Redis address")
    String redisAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");

    @Option(names = {"--redisPoolSize"}, description = "Redis pool size", defaultValue = "5")
    int redisPoolSize;

    @Option(names = {"--messageBusAddress"}, description = "Message bus address")
    String messageBusAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");

    @Option(names = {"--busType"}, description = "Backend data bus type", defaultValue = "MEMORY")
    String busType;

    @Option(names = {"--queueName"}, description = "Extract queue name", defaultValue = "extract:queue")
    String queueName;

    @Option(names = {"--queueType"}, description = "Backend queues type", defaultValue = "MEMORY")
    String queueType;

    @Option(names = {"--queueCapacity"}, description = "Queue capacity", defaultValue = "1000000")
    int queueCapacity;

    @Option(names = {"--dataSourceUrl"}, description = "Datasource URL")
    String dataSourceUrl = "jdbc:sqlite:file:" + Paths.get(home(), ".local/share/datashare", "dist/datashare.db");

    @Option(names = {"--clusterName"}, description = "Cluster name", defaultValue = "datashare")
    String clusterName;

    @Option(names = {"--pluginsDir"}, description = "Plugins directory")
    String pluginsDir = Paths.get(home(), ".local/share/datashare", "plugins").toString();

    @Option(names = {"--extensionsDir"}, description = "Extensions directory")
    String extensionsDir = Paths.get(home(), ".local/share/datashare", "extensions").toString();

    @Option(names = {"-u", "--defaultUserName"}, description = "Default local user name", defaultValue = "local")
    String defaultUserName;

    @Option(names = {"--oauthUserProjectsAttribute"}, description = "OAuth user projects key", defaultValue = "groups_by_applications.datashare")
    String oauthUserProjectsAttribute;

    @Option(names = {"--ext"}, description = "Run CLI extension")
    String ext;

    @Option(names = {"-d", "--dataDir"}, description = "Document source files directory")
    File dataDir = new File(Paths.get(home(), "Datashare").toString());

    @Option(names = {"-l", "--language"}, description = "Indexing language")
    String language;

    @Option(names = {"--no-color"}, description = "Disable ANSI color output", defaultValue = "false")
    boolean noColor;

    /** Converts the parsed global option fields into a Properties map for the rest of the application. */
    public Properties toProperties() {
        Properties props = new Properties();

        DatashareOptions.putIfNotNull(props, SETTINGS_OPT, settings);
        DatashareOptions.putIfNotNull(props, LOG_LEVEL_OPT, logLevel);
        DatashareOptions.putIfNotNull(props, CHARSET_OPT, charset);
        DatashareOptions.putIfNotNull(props, DEFAULT_PROJECT_OPT, defaultProject);
        DatashareOptions.putIfNotNull(props, DIGEST_ALGORITHM_OPT, digestAlgorithm);
        DatashareOptions.putIfNotNull(props, DIGEST_PROJECT_NAME_OPT, digestProjectName);
        props.setProperty(NO_DIGEST_PROJECT_OPT, String.valueOf(noDigestProject));
        DatashareOptions.putIfNotNull(props, ELASTICSEARCH_ADDRESS_OPT, elasticsearchAddress);
        DatashareOptions.putIfNotNull(props, ELASTICSEARCH_PATH_OPT, elasticsearchPath);
        DatashareOptions.putIfNotNull(props, ELASTICSEARCH_DATA_PATH_OPT, elasticsearchDataPath);
        DatashareOptions.putIfNotNull(props, ELASTICSEARCH_SETTINGS_OPT, elasticsearchSettings);
        DatashareOptions.putIfNotNull(props, REDIS_ADDRESS_OPT, redisAddress);
        props.setProperty(REDIS_POOL_SIZE_OPT, String.valueOf(redisPoolSize));
        DatashareOptions.putIfNotNull(props, MESSAGE_BUS_OPT, messageBusAddress);
        DatashareOptions.putIfNotNull(props, BUS_TYPE_OPT, busType);
        DatashareOptions.putIfNotNull(props, QUEUE_NAME_OPT, queueName);
        DatashareOptions.putIfNotNull(props, QUEUE_TYPE_OPT, queueType);
        props.setProperty(QUEUE_CAPACITY_OPT, String.valueOf(queueCapacity));
        DatashareOptions.putIfNotNull(props, DATA_SOURCE_URL_OPT, dataSourceUrl);
        DatashareOptions.putIfNotNull(props, CLUSTER_NAME_OPT, clusterName);
        DatashareOptions.putIfNotNull(props, PLUGINS_DIR_OPT, pluginsDir);
        DatashareOptions.putIfNotNull(props, EXTENSIONS_DIR_OPT, extensionsDir);
        DatashareOptions.putIfNotNull(props, DEFAULT_USER_NAME_OPT, defaultUserName);
        DatashareOptions.putIfNotNull(props, OAUTH_USER_PROJECTS_KEY_OPT, oauthUserProjectsAttribute);
        DatashareOptions.putIfNotNull(props, EXT_OPT, ext);
        if (dataDir != null) props.setProperty(DATA_DIR_OPT, dataDir.toString());
        DatashareOptions.putIfNotNull(props, LANGUAGE_OPT, language);

        return props;
    }

}
