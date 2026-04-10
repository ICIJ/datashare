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
        Properties properties = resolveProperties(args);
        startApplication(properties);
    }

    private static Properties resolveProperties(String[] args) throws Exception {
        if (isLegacyInvocation(args)) {
            DatashareCli cli = new DatashareCli().parseArguments(args);
            return cli.properties;
        }
        return runPicocli(args);
    }

    private static Properties runPicocli(String[] args) {
        DatashareCommand cmd = new DatashareCommand();
        CommandLine commandLine = DatashareHelpFactory.configure(new CommandLine(cmd));
        commandLine.setOverwrittenOptionsAllowed(true);
        commandLine.setAbbreviatedOptionsAllowed(false);
        commandLine.setAbbreviatedSubcommandsAllowed(false);
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        applyColorScheme(commandLine, args);
        // The execution strategy walks the parse-result hierarchy to the deepest subcommand,
        // short-circuiting to RunLast if --help or --version is requested at any level so the
        // right help page is shown. Otherwise it registers the executed subcommand on
        // DatashareCommand so its properties can be collected after execution.
        commandLine.setExecutionStrategy(parseResult -> resolveSubcommand(parseResult, cmd));
        int exitCode = commandLine.execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
        if (cmd.getExecutedSubcommand() == null) {
            System.exit(0);
        }
        return cmd.collectProperties();
    }

    private static void applyColorScheme(CommandLine commandLine, String[] args) {
        boolean isColorDisabled = System.getenv("NO_COLOR") != null
                || java.util.Arrays.asList(args).contains("--no-color");
        if (isColorDisabled) {
            commandLine.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        }
    }

    private static int resolveSubcommand(CommandLine.ParseResult parseResult, DatashareCommand cmd) {
        CommandLine.ParseResult current = parseResult;
        while (current != null) {
            if (current.isUsageHelpRequested() || current.isVersionHelpRequested()) {
                return new CommandLine.RunLast().execute(parseResult);
            }
            if (!current.hasSubcommand()) {
                break;
            }
            current = current.subcommand();
        }
        Object userObject = current.commandSpec().userObject();
        if (userObject instanceof DatashareSubcommand) {
            cmd.setExecutedSubcommand((DatashareSubcommand) userObject);
        }
        return new CommandLine.RunLast().execute(parseResult);
    }

    private static void startApplication(Properties properties) throws Exception {
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
            String port = commonMode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT_OPT);
            Closeable tray = DatashareSystemTray.create(port);
            ofNullable(tray).ifPresent(commonMode::addCloseable);
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
        if (args.length == 0) {
            return true;
        }
        String first = args[0].trim();
        if (first.isEmpty() || first.startsWith("-")) {
            return true;
        }
        return !SUBCOMMAND_NAMES.contains(first);
    }
}
