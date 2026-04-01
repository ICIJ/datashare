package org.icij.datashare.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

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
        if (executedSubcommand != null) {
            props.putAll(executedSubcommand.getSubcommandProperties());
        }
        DatashareOptions.postProcess(props);
        return props;
    }
}
