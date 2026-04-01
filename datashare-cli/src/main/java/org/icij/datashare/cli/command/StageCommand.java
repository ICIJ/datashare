package org.icij.datashare.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "stage", mixinStandardHelpOptions = true,
        description = "Run document processing pipeline stages.",
        subcommands = {StageRunCommand.class})
public class StageCommand implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // No subcommand specified: print usage and exit 0 so the user knows what subcommands are available.
        spec.commandLine().usage(System.out);
    }
}
