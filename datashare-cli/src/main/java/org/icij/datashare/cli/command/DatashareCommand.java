package org.icij.datashare.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import org.icij.datashare.PropertiesProvider;

import java.util.Properties;

@Command(name = "datashare",
        mixinStandardHelpOptions = true,
        versionProvider = DatashareVersionProvider.class,
        subcommands = {
                AppCommand.class,
                WorkerCommand.class,
                StageCommand.class,
                PluginCommand.class,
                ExtensionCommand.class,
                ApiKeyCommand.class,
                UserCommand.class,
                ProjectCommand.class,
                CommandLine.HelpCommand.class
        },
        description = "Datashare - Index and search your documents")
public class DatashareCommand implements Runnable {

    @Mixin
    GlobalOptions globalOptions = new GlobalOptions();

    private DatashareSubcommand executedSubcommand;

    @Override
    public void run() {
        // No subcommand specified: legacy invocations are handled by the old parser.
    }

    public void setExecutedSubcommand(DatashareSubcommand subcommand) {
        this.executedSubcommand = subcommand;
    }

    public DatashareSubcommand getExecutedSubcommand() {
        return executedSubcommand;
    }

    /**
     * Collect properties from global options and the executed subcommand.
     * Subcommand properties override global options on conflict.
     */
    public Properties collectProperties() {
        Properties props = globalOptions.toProperties();
        DatashareOptions.putAll(props, executedSubcommand);
        DatashareOptions.postProcess(props);
        return props;
    }

    /**
     * Puts the settings file above the annotation defaults: picocli consults an IDefaultValueProvider
     * before an option's declared defaultValue, and an explicitly typed argument still wins over both.
     * Read from args because the provider has to be installed before parsing, and a no-op on empty
     * settings so a run with no file behaves exactly as before.
     */
    public static void applySettingsDefaults(CommandLine commandLine, String[] args) {
        Properties settings = new PropertiesProvider(settingsPathFrom(args)).getProperties();
        if (!settings.isEmpty()) {
            commandLine.setDefaultValueProvider(new CommandLine.PropertiesDefaultProvider(settings));
        }
    }

    /** Reads -s/--settings out of the raw args, in both the "-s value" and "-s=value" forms. */
    private static String settingsPathFrom(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-s".equals(arg) || "--settings".equals(arg)) {
                return i + 1 < args.length ? args[i + 1] : null;
            }
            if (arg.startsWith("-s=")) {
                return arg.substring("-s=".length());
            }
            if (arg.startsWith("--settings=")) {
                return arg.substring("--settings=".length());
            }
        }
        return null;
    }
}
