package org.icij.datashare.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "worker", mixinStandardHelpOptions = true,
        description = "Manage task workers for background processing.",
        subcommands = {WorkerRunCommand.class})
public class WorkerCommand implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // No subcommand specified: print usage and exit 0 so the user knows what subcommands are available.
        spec.commandLine().usage(System.out);
    }
}
