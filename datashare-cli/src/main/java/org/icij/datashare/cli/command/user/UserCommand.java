package org.icij.datashare.cli.command.user;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "user", mixinStandardHelpOptions = true,
        description = "Manage Datashare users (create, delete).",
        subcommands = { /* leaves added in tasks 6 + 7 */ })
public class UserCommand implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }
}
