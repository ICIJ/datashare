package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "entities", mixinStandardHelpOptions = true,
        description = "Work with the structured entities of a project.", subcommands = {EntitiesExportCommand.class})
public class EntitiesCommand implements Runnable {
    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getErr());
        throw new CliExitException(2);
    }
}
