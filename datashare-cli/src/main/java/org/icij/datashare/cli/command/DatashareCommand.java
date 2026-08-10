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
     * Read from args because the provider has to be installed before parsing, and a no-op unless the
     * operator passed -s, so a run with no file behaves exactly as before.
     */
    public static void applySettingsDefaults(CommandLine commandLine, String[] args) {
        String settingsPath = settingsPathFrom(args);
        if (settingsPath == null) {
            // Not "no file": PropertiesProvider(null) resolves a classpath datashare.properties, which
            // is not what -s asked for.
            return;
        }
        // The file alone, not getProperties(), which also folds in DS_DOCKER_* env vars: those are a
        // separate tier that CommonMode ranks, and promoting them here would make them beat an option
        // default only when -s happens to be passed.
        Properties settings = new PropertiesProvider(settingsPath).getFileProperties();
        CommandLine.IDefaultValueProvider fromSettings = new CommandLine.PropertiesDefaultProvider(settings);
        // Arity-0 booleans are excluded: picocli sets a matched flag to !defaultValue, so taking that
        // default from the file inverts the flag, and "resume=true" in the file would make -r mean
        // "do not resume". They keep their declared default here and CommonMode's overrideWith fold-in
        // is what applies the file's value, as it did before this provider existed.
        commandLine.setDefaultValueProvider(
                arg -> arg.arity().max() == 0 ? null : fromSettings.defaultValue(arg));
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
