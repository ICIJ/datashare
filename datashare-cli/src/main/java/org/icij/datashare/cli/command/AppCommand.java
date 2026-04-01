package org.icij.datashare.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "app", mixinStandardHelpOptions = true,
        description = "Manage and run the Datashare web application.",
        subcommands = {AppServeCommand.class})
public class AppCommand implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // No subcommand specified: print usage and exit 0 so the user knows what subcommands are available.
        spec.commandLine().usage(System.out);
    }
}
