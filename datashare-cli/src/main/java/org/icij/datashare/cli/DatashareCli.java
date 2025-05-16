package org.icij.datashare.cli;


import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.cli.spi.CliExtension;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DIGEST_PROJECT_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NO_DIGEST_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.OAUTH_USER_PROJECTS_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.OPT_ALIASES;


public class DatashareCli {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatashareCli.class);
    public Properties properties;
    public static final char SEPARATOR = PipelineHelper.STAGES_SEPARATOR;

    public DatashareCli parseArguments(String[] args) {
        OptionParser parser = createParser();
        OptionSpec<Void> helpOpt = DatashareCliOptions.help(parser);
        OptionSpec<Void> versionOpt = DatashareCliOptions.version(parser);

        List<CliExtension> extensions  = CliExtensionService.getInstance().getExtensions();
        OptionSpec<String> extOption = DatashareCliOptions.extOption(parser);
        if (extensions.size() > 1) {
            System.out.println("For now we only allow one CLI extension");
            System.exit(2);
        } else if (extensions.size() == 1) {
            extensions.get(0).addOptions(parser);
            OptionSet options = parser.parse(args);
            if (options.has(extOption)) {
                String extId = options.valueOf(extOption);
                if (!extId.equals(extensions.get(0).identifier())) {
                    System.out.println("Unknown extension: " + extId);
                    System.exit(3);
                }
                OptionSet extOptions = parser.parse(args);
                if (extOptions.has(helpOpt)) {
                    OptionParser extParser = createExtParser(extensions.get(0));
                    printHelp(extParser);
                    System.exit(0);
                }
                properties = asProperties(extOptions, null);
                return this;
            }
        }

        try {
            OptionSet options = parser.parse(args);
            if (options.has(helpOpt)) {
                printHelp(parser);
                System.exit(0);
            }
            if (options.has(versionOpt)) {
                System.out.println(getVersion());
                System.exit(0);
            }

            properties = asProperties(options, null);
            if (!Boolean.parseBoolean(properties.getProperty(NO_DIGEST_PROJECT_OPT))
                    && properties.getProperty(DIGEST_PROJECT_NAME_OPT) == null) {
                properties.setProperty(DIGEST_PROJECT_NAME_OPT, properties.getProperty(DEFAULT_PROJECT_OPT));
            }
            if (!User.DEFAULT_PROJECTS_KEY.equals(properties.getProperty(OAUTH_USER_PROJECTS_KEY_OPT))) {
                LOGGER.info("settings system property {} to {}", User.JVM_PROJECT_KEY, properties.getProperty(OAUTH_USER_PROJECTS_KEY_OPT));
                System.setProperty(User.JVM_PROJECT_KEY, properties.getProperty(OAUTH_USER_PROJECTS_KEY_OPT));
            }
            // Retro-compatibility so the alias options is are mapped to the right property
            for (String alias : OPT_ALIASES.keySet()) {
                if (properties.containsKey(alias)) {
                    properties.setProperty(OPT_ALIASES.get(alias), properties.getProperty(alias));
                    properties.remove(alias);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse arguments.", e);
            printHelp(parser);
            System.exit(1);
        }
        return this;
    }

    private static OptionParser createExtParser(CliExtension cliExtension) {
        OptionParser parser = new OptionParser();
        DatashareCliOptions.extOption(parser);
        DatashareCliOptions.mode(parser);
        cliExtension.addOptions(parser);
        return parser;
    }

    OptionParser createParser() {
        OptionParser parser = new OptionParser();

        DatashareCliOptions.settings(parser);

        DatashareCliOptions.pluginsDir(parser);
        DatashareCliOptions.pluginList(parser);
        DatashareCliOptions.pluginInstall(parser);
        DatashareCliOptions.pluginDelete(parser);
        DatashareCliOptions.extensionsDir(parser);
        DatashareCliOptions.extensionList(parser);
        DatashareCliOptions.extensionInstall(parser);
        DatashareCliOptions.extensionDelete(parser);
        DatashareCliOptions.tcpListenPort(parser);
        DatashareCliOptions.mode(parser);
        DatashareCliOptions.charset(parser);
        DatashareCliOptions.stages(parser);
        DatashareCliOptions.dataDir(parser);
        DatashareCliOptions.artifactDir(parser);
        DatashareCliOptions.enableOcr(parser);
        DatashareCliOptions.language(parser);
        DatashareCliOptions.ocrLanguage(parser);
        DatashareCliOptions.ocrType(parser);
        DatashareCliOptions.nlpPipeline(parser);
        DatashareCliOptions.nlpMaxTextLength(parser);
        DatashareCliOptions.nlpBatchSize(parser);
        DatashareCliOptions.resume(parser);
        DatashareCliOptions.scroll(parser);
        DatashareCliOptions.scrollSize(parser);
        DatashareCliOptions.scrollSlices(parser);
        DatashareCliOptions.batchSearchScroll(parser);
        DatashareCliOptions.batchSearchScrollSize(parser);
        DatashareCliOptions.batchDownloadScroll(parser);
        DatashareCliOptions.batchDownloadScrollSize(parser);
        DatashareCliOptions.redisPoolSize(parser);
        DatashareCliOptions.elasticsearchDataPath(parser);
        DatashareCliOptions.reportName(parser);
        DatashareCliOptions.parallelism(parser);
        DatashareCliOptions.fileParserParallelism(parser);
        DatashareCliOptions.nlpParallelism(parser);
        DatashareCliOptions.followSymlinks(parser);
        DatashareCliOptions.enableBrowserOpenLink(parser);
        DatashareCliOptions.embeddedDocumentDownloadMaxSize(parser);
        DatashareCliOptions.batchSearchMaxTime(parser);
        DatashareCliOptions.batchThrottle(parser);
        DatashareCliOptions.batchQueueType(parser);
        DatashareCliOptions.sessionStoreType(parser);
        DatashareCliOptions.batchDownloadTimeToLive(parser);
        DatashareCliOptions.batchDownloadMaxNbFiles(parser);
        DatashareCliOptions.batchDownloadMaxSize(parser);
        DatashareCliOptions.batchDownloadEncrypt(parser);
        DatashareCliOptions.batchDownloadDir(parser);
        DatashareCliOptions.smtpUrl(parser);
        DatashareCliOptions.maxContentLength(parser);
        DatashareCliOptions.clusterName(parser);
        DatashareCliOptions.createIndex(parser);
        DatashareCliOptions.defaultUser(parser);
        DatashareCliOptions.defaultUserProjectKey(parser);
        DatashareCliOptions.defaultProject(parser);
        DatashareCliOptions.oauthClaimIdAttribute(parser);
        DatashareCliOptions.esHost(parser);
        DatashareCliOptions.queueName(parser);
        DatashareCliOptions.cors(parser);
        DatashareCliOptions.queueType(parser);
        DatashareCliOptions.busType(parser);
        DatashareCliOptions.messageBusAddress(parser);
        DatashareCliOptions.redisAddress(parser);
        DatashareCliOptions.dataSourceUrl(parser);
        DatashareCliOptions.rootHost(parser);
        DatashareCliOptions.genApiKey(parser);
        DatashareCliOptions.delApiKey(parser);
        DatashareCliOptions.getApiKey(parser);
        DatashareCliOptions.sessionTtlSeconds(parser);
        DatashareCliOptions.protectedUriPrefix(parser);
        DatashareCliOptions.authUsersProvider(parser);
        DatashareCliOptions.oauthSecret(parser);
        DatashareCliOptions.oauthClient(parser);
        DatashareCliOptions.oauthApiUrl(parser);
        DatashareCliOptions.oauthAuthorizeUrl(parser);
        DatashareCliOptions.oauthTokenUrl(parser);
        DatashareCliOptions.authFilter(parser);
        DatashareCliOptions.oauthCallbackPath(parser);
        DatashareCliOptions.oauthScope(parser);
        DatashareCliOptions.oauthDefaultProject(parser);
        DatashareCliOptions.digestMethod(parser);
        DatashareCliOptions.digestProjectName(parser);
        DatashareCliOptions.noDigestProject(parser);
        DatashareCliOptions.logLevel(parser);
        DatashareCliOptions.searchQuery(parser);
        DatashareCliOptions.taskRoutingStrategy(parser);
        DatashareCliOptions.taskRoutingKey(parser);
        DatashareCliOptions.pollingInterval(parser);
        DatashareCliOptions.taskRepositoryType(parser);
        DatashareCliOptions.taskManagerPollingInterval(parser);
        DatashareCliOptions.taskWorkers(parser);
        return parser;
    }

    Properties asProperties(OptionSet options, String prefix) {
        Properties properties = new Properties();
        for (Map.Entry<OptionSpec<?>, List<?>> entry : options.asMap().entrySet()) {
            OptionSpec<?> spec = entry.getKey();
            if (options.has(spec) || !entry.getValue().isEmpty()) {
                properties.setProperty(
                        asPropertyKey(prefix, spec),
                        asPropertyValue(entry.getValue()));
            }
        }
        return properties;
    }

    private String asPropertyKey(String prefix, OptionSpec<?> spec) {
        List<String> flags = spec.options();
        for (String flag : flags)
            if (1 < flag.length())
                return null == prefix ? flag : (prefix + '.' + flag);
        throw new IllegalArgumentException("No usable non-short flag: " + flags);
    }

    private String asPropertyValue(List<?> values) {
        // last value is used for bash script option overriding:
        // when in datashare shell script we call java ... -m EMBEDDED $@
        // if the user provided -m SERVER then values will be [EMBEDDED,SERVER] so this function will keep the user option!
        // it has to be refactored because we can't use lists with separator in jopts simple, we use lists as string
        String stringValue = !values.isEmpty() ? String.valueOf(values.get(values.size() - 1)): "";
        return stringValue.isEmpty() ? "true": stringValue;
    }

    private void printHelp(OptionParser parser) {
        try {
            System.out.println("Usage: ");
            parser.printHelpOn(System.out);
        } catch (IOException e) {
            LOGGER.debug("Failed to print help message", e);
        }
    }

    public String getVersion() throws IOException {
        Properties versions = new Properties();
        InputStream gitProperties = getClass().getResourceAsStream("/git.properties");
        if (gitProperties != null) {
            versions.load(gitProperties);
        }
        return versions.getProperty("git.build.version");
    }

    public Mode mode() {
        return Mode.valueOf(ofNullable(properties).orElse(new Properties()).getProperty("mode"));
    }

    public boolean isWebServer() {
        return Mode.valueOf(ofNullable(properties).orElse(new Properties()).getProperty("mode")).isWebServer();
    }
}
