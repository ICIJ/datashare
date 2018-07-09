package org.icij.datashare.cli;

import com.google.common.base.Joiner;
import joptsimple.*;
import org.icij.datashare.WebApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

import static java.lang.Boolean.parseBoolean;
import static org.icij.datashare.cli.DatashareCliOptions.WEB_SERVER_OPT;


public final class DatashareCli {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatashareCli.class);
    private static List<DatashareCli.Stage> stages = new ArrayList<>();

    static Properties properties;
    static boolean webServer = false;

    public static void main(String[] args) throws Exception {
        if (!parseArguments(args)) {
            LOGGER.info("Exiting...");
            System.exit(1);
        }
        LOGGER.info("Running datashare " + (webServer ? "web server" : ""));
        LOGGER.info("with properties: " + properties);

        if (webServer) {
            WebApp.start(properties);
        } else {
            CliApp.start(properties);
        }
    }

    static boolean parseArguments(String[] args) {
        OptionParser parser = new OptionParser();
        AbstractOptionSpec<Void> helpOpt = DatashareCliOptions.help(parser);

        OptionSpec<DatashareCli.Stage> stagesOpt = DatashareCliOptions.stages(parser);
        DatashareCliOptions.dataDir(parser);
        DatashareCliOptions.enableOcr(parser);
        DatashareCliOptions.nlpPipelines(parser);
        DatashareCliOptions.resume(parser);
        DatashareCliOptions.parallelism(parser);
        DatashareCliOptions.fileParserParallelism(parser);
        DatashareCliOptions.nlpParallelism(parser);
        DatashareCliOptions.indexerHost(parser);
        DatashareCliOptions.cors(parser);
        DatashareCliOptions.esHost(parser);
        DatashareCliOptions.indexName(parser);
        DatashareCliOptions.clusterName(parser);
        DatashareCliOptions.web(parser);
        DatashareCliOptions.auth(parser);
        DatashareCliOptions.messageBusAddress(parser);
        DatashareCliOptions.redisAddress(parser);

        try {
            OptionSet options = parser.parse(args);

            if (options.has(helpOpt) || args.length == 0) {
                printHelp(parser);
                return false;
            }

            stages.addAll(options.valuesOf(stagesOpt));
            stages.sort(DatashareCli.Stage.comparator);
            properties = asProperties(options, null);
            webServer = parseBoolean(properties.getProperty(WEB_SERVER_OPT));
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to parse arguments.", e);
            printHelp(parser);
            return false;
        }
    }

    // from https://pholser.github.io/jopt-simple/examples.html
    private static Properties asProperties(OptionSet options, String prefix) {
        Properties properties = new Properties();
        for (Map.Entry<OptionSpec<?>, List<?>> entry : options.asMap().entrySet()) {
            OptionSpec<?> spec = entry.getKey();
            properties.setProperty(
                    asPropertyKey(prefix, spec),
                    asPropertyValue(entry.getValue(), options.has(spec)));
        }
        return properties;
    }

    private static String asPropertyKey(String prefix, OptionSpec<?> spec) {
        List<String> flags = spec.options();
        for (String flag : flags)
            if (1 < flag.length())
                return null == prefix ? flag : (prefix + '.' + flag);
        throw new IllegalArgumentException("No usable non-short flag: " + flags);
    }

    private static String asPropertyValue(List<?> values, boolean present) {
        // Simple flags have no values; treat presence/absence as true/false
        return values.isEmpty() ? String.valueOf(present) : Joiner.on(",").join(values);
    }

    private static void printHelp(OptionParser parser) {
        try {
            System.out.println("Usage: ");
            parser.printHelpOn(System.out);
        } catch (IOException e) {
            LOGGER.debug("Failed to print help message", e);
        }
    }

    public enum Stage {
        SCAN,
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
