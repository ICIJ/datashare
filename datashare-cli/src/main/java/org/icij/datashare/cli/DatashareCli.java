package org.icij.datashare.cli;

import com.google.common.base.Joiner;
import joptsimple.AbstractOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.icij.datashare.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

import static java.util.Optional.ofNullable;


public class DatashareCli {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatashareCli.class);
    public Properties properties;

    public boolean parseArguments(String[] args) {
        OptionParser parser = new OptionParser();
        AbstractOptionSpec<Void> helpOpt = DatashareCliOptions.help(parser);

        DatashareCliOptions.configFile(parser);
        DatashareCliOptions.tcpListenPort(parser);
        DatashareCliOptions.mode(parser);
        DatashareCliOptions.stages(parser);
        DatashareCliOptions.dataDir(parser);
        DatashareCliOptions.enableOcr(parser);
        DatashareCliOptions.nlpPipelines(parser);
        DatashareCliOptions.resume(parser);
        DatashareCliOptions.parallelism(parser);
        DatashareCliOptions.fileParserParallelism(parser);
        DatashareCliOptions.nlpParallelism(parser);
        DatashareCliOptions.followSymlinks(parser);

        DatashareCliOptions.clusterName(parser);

        OptionSpec<String> userOption = DatashareCliOptions.defaultUser(parser);
        OptionSpec<String> projectOption = DatashareCliOptions.defaultProject(parser);

        DatashareCliOptions.esHost(parser);
        DatashareCliOptions.queueName(parser);

        DatashareCliOptions.cors(parser);
        DatashareCliOptions.messageBusAddress(parser);
        DatashareCliOptions.redisAddress(parser);
        DatashareCliOptions.dataSourceUrl(parser);
        DatashareCliOptions.rootHost(parser);

        DatashareCliOptions.sessionTtlSeconds(parser);
        DatashareCliOptions.protectedUriPrefix(parser);
        DatashareCliOptions.oauthSecret(parser);
        DatashareCliOptions.oauthClient(parser);
        DatashareCliOptions.oauthApiUrl(parser);
        DatashareCliOptions.oauthAuthorizeUrl(parser);
        DatashareCliOptions.oauthTokenUrl(parser);
        DatashareCliOptions.authFilter(parser);
        DatashareCliOptions.oauthCallbackPath(parser);

        try {
            OptionSet options = parser.parse(args);
            if (options.hasArgument(userOption) && options.hasArgument(projectOption)) {
                throw new IllegalArgumentException("you should provide either user or project but not both");
            }
            if (options.has(helpOpt)) {
                printHelp(parser);
                return false;
            }
            properties = asProperties(options, null);
            if (options.hasArgument(userOption)) {
                properties.setProperty(projectOption.options().get(1),
                        userOption.value(options).trim() + "-datashare");
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to parse arguments.", e);
            printHelp(parser);
            return false;
        }
    }

    // from https://pholser.github.io/jopt-simple/examples.html
    private Properties asProperties(OptionSet options, String prefix) {
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
        String stringValue = Joiner.on(",").join(values);
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

    public boolean isWebServer() {
        return Mode.valueOf(ofNullable(properties).orElse(new Properties()).getProperty("mode")).isWebServer();
    }

    public enum Stage {
        SCAN,
        FILTER,
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
