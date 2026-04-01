package org.icij.datashare;

import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.command.DatashareCommand;
import org.icij.datashare.cli.command.DatashareHelpFactory;
import org.icij.datashare.cli.command.DatashareSubcommand;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tray.DatashareSystemTray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import picocli.CommandLine;

import java.io.Closeable;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.Set;

import static java.util.Optional.ofNullable;

/**
 * Datashare supports two CLI invocation styles: legacy (jopt-simple) and new picocli subcommands.
 * The legacy path is used when: no args are provided, the first arg starts with -, or the
 * first arg is an unknown word (i.e. not a recognized subcommand).
 * The new picocli path is used when the first arg is a known subcommand: app, worker,
 * stage, plugin, extension, api-key, or help.
 */
public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final Set<String> SUBCOMMAND_NAMES = Set.of("app", "worker", "stage", "plugin", "extension", "api-key", "help");

    /**
     * Application entry point. Routes to the legacy jopt-simple parser or the picocli subcommand
     * parser based on isLegacyInvocation(), then starts the appropriate mode.
     */
    public static void main(String[] args) throws Exception {
        Properties properties;

        if (isLegacyInvocation(args)) {
            DatashareCli cli = new DatashareCli().parseArguments(args);
            properties = cli.properties;
        } else {
            DatashareCommand cmd = new DatashareCommand();
            CommandLine commandLine = DatashareHelpFactory.configure(new CommandLine(cmd));
            commandLine.setOverwrittenOptionsAllowed(true);
            commandLine.setAbbreviatedOptionsAllowed(false);
            commandLine.setAbbreviatedSubcommandsAllowed(false);
            commandLine.setCaseInsensitiveEnumValuesAllowed(true);
            boolean noColor = System.getenv("NO_COLOR") != null
                    || java.util.Arrays.asList(args).contains("--no-color");
            if (noColor) {
                commandLine.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
            }
            // The execution strategy works in two phases:
            // 1. It scans the parse-result hierarchy for any --help or --version request and
            //    delegates to RunLast if found, so the correct subcommand-level help page is shown.
            // 2. It walks down to the deepest executed subcommand in the hierarchy (mirroring
            //    picocli's RunLast behavior) and registers it on DatashareCommand so its
            //    properties can be collected after execution.
            commandLine.setExecutionStrategy(parseResult -> {
                // Walk the parse-result hierarchy to the deepest subcommand, short-circuiting
                // immediately if any level has requested help/version so RunLast shows the
                // right help page.
                CommandLine.ParseResult current = parseResult;
                while (current != null) {
                    if (current.isUsageHelpRequested() || current.isVersionHelpRequested()) {
                        return new CommandLine.RunLast().execute(parseResult);
                    }
                    if (!current.hasSubcommand()) break;
                    current = current.subcommand();
                }
                Object userObject = current.commandSpec().userObject();
                if (userObject instanceof DatashareSubcommand) {
                    cmd.setExecutedSubcommand((DatashareSubcommand) userObject);
                }
                return new CommandLine.RunLast().execute(parseResult);
            });
            int exitCode = commandLine.execute(args);
            if (exitCode != 0) System.exit(exitCode);
            if (cmd.getExecutedSubcommand() == null) System.exit(0);
            properties = cmd.collectProperties();
        }

        Mode mode = Mode.valueOf(properties.getProperty("mode", "LOCAL"));
        LOGGER.info("Running datashare {}", mode.isWebServer() ? "web server" : "");
        LOGGER.info("JVM version {}", System.getProperty("java.version"));
        LOGGER.info("JVM charset encoding {}", Charset.defaultCharset());
        Level logLevel = Level.toLevel(properties.getProperty("logLevel"));
        LOGGER.info("Log level set to {}", logLevel);

        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(logLevel);

        CommonMode commonMode = CommonMode.create(properties);
        Runtime.getRuntime().addShutdownHook(commonMode.closeThread());

        if (mode.isWebServer()) {
            ofNullable(DatashareSystemTray.create(commonMode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT_OPT))).
                    ifPresent(commonMode::addCloseable);
            WebApp.start(commonMode);
        } else if (mode == Mode.TASK_WORKER) {
            TaskWorkerApp.start(commonMode);
        } else {
            CliApp.start(properties);
        }
        LOGGER.info("exiting main");
    }

    /**
     * Returns true (use legacy jopt-simple path) when: no args are given, the first arg is
     * blank, the first arg starts with - (flag-style), or the first arg is not a recognized
     * subcommand name. Returns false (use picocli path) only when the first arg exactly
     * matches a known subcommand.
     */
    static boolean isLegacyInvocation(String[] args) {
        if (args.length == 0) return true;
        String first = args[0].trim();
        if (first.isEmpty() || first.startsWith("-")) return true;
        return !SUBCOMMAND_NAMES.contains(first);
    }
}
