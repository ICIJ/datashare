package org.icij.datashare.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "api-key", mixinStandardHelpOptions = true,
        description = "Manage API keys for programmatic access.",
        subcommands = {ApiKeyCreateCommand.class, ApiKeyGetCommand.class, ApiKeyDeleteCommand.class})
public class ApiKeyCommand implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // No subcommand specified: print usage and exit 0 so the user knows what subcommands are available.
        spec.commandLine().usage(System.out);
    }
}
