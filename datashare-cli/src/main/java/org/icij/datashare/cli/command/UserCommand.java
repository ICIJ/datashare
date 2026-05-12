package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "user", mixinStandardHelpOptions = true,
        description = "Manage Datashare users (create, delete).",
        subcommands = { UserCreateCommand.class, UserDeleteCommand.class })
public class UserCommand implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // Print usage to stderr and exit 2 to match the git-style "missing
        // subcommand" convention; lets scripts distinguish a user error from
        // a successful invocation.
        spec.commandLine().usage(spec.commandLine().getErr());
        throw new CliExitException(2);
    }
}
