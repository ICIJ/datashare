package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "project", mixinStandardHelpOptions = true,
        description = "Manage Datashare projects (create, delete, grant, revoke).",
        subcommands = {
            ProjectCreateCommand.class,
            ProjectDeleteCommand.class,
            ProjectGrantCommand.class
        })
public class ProjectCommand implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // No subcommand: print usage to stderr and exit 2 to match the git-style "missing
        // subcommand" convention; lets scripts distinguish a user error from
        // a successful invocation.
        spec.commandLine().usage(spec.commandLine().getErr());
        throw new CliExitException(2);
    }
}
