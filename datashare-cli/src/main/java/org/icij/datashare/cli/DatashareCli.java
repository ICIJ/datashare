package org.icij.datashare.cli;


import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static java.util.Optional.ofNullable;


public class DatashareCli {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatashareCli.class);
    public Properties properties;

    public DatashareCli parseArguments(String[] args) {
        OptionParser parser = createParser();

        OptionSpec<Void> helpOpt = DatashareCliOptions.help(parser);
        OptionSpec<Void> versionOpt = DatashareCliOptions.version(parser);
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
        } catch (Exception e) {
            LOGGER.error("Failed to parse arguments.", e);
            printHelp(parser);
            System.exit(1);
        }
        return this;
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
        DatashareCliOptions.stages(parser);
        DatashareCliOptions.dataDir(parser);
        DatashareCliOptions.enableOcr(parser);
        DatashareCliOptions.language(parser);
        DatashareCliOptions.ocrLanguage(parser);
        DatashareCliOptions.nlpPipelines(parser);
        DatashareCliOptions.resume(parser);
        DatashareCliOptions.scrollSize(parser);
        DatashareCliOptions.scrollSlices(parser);
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
        DatashareCliOptions.defaultProject(parser);
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
        String stringValue = values.size() > 0 ? String.valueOf(values.get(values.size() - 1)): "";
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

    public enum Stage {
        SCAN,
        SCANIDX,
        DEDUPLICATE,
        INDEX,
        NLP;

        public static final Comparator<Stage> comparator = Comparator.comparing(Stage::ordinal);

        public static Optional<Stage> parse(final String stage) {
            if (stage == null || stage.isEmpty())
                return Optional.empty();
            try {
                return Optional.of(valueOf(stage.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }
}
