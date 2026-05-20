package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import org.junit.After;
import org.junit.Before;
import picocli.CommandLine;

import java.util.Properties;

/**
 * Shared harness for CLI parsing tests. Each per-section *CommandTest extends
 * this so the picocli wiring lives in one place.
 */
abstract class AbstractDatashareCommandTest {

    @Before
    public void setUp() {
        System.setProperty("user.home", "/home/datashare");
    }

    @After
    public void tearDown() {
        System.clearProperty("user.home");
    }

    protected Properties parse(String... args) {
        DatashareCommand cmd = new DatashareCommand();
        CommandLine commandLine = configure(cmd);
        commandLine.execute(args);
        return cmd.collectProperties();
    }

    protected int parseExitCode(String... args) {
        DatashareCommand cmd = new DatashareCommand();
        CommandLine commandLine = configure(cmd);
        return commandLine.execute(args);
    }

    private static CommandLine configure(DatashareCommand cmd) {
        CommandLine commandLine = new CommandLine(cmd);
        commandLine.setOverwrittenOptionsAllowed(true);
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setExecutionStrategy(parseResult -> {
            CommandLine.ParseResult sub = parseResult;
            while (sub.hasSubcommand()) {
                sub = sub.subcommand();
            }
            Object userObject = sub.commandSpec().userObject();
            if (userObject instanceof DatashareSubcommand) {
                cmd.setExecutedSubcommand((DatashareSubcommand) userObject);
            }
            return new CommandLine.RunLast().execute(parseResult);
        });
        commandLine.setExecutionExceptionHandler((ex, cmd2, parseResult) -> {
            if (ex instanceof CliExitException ce) {
                return ce.exitCode();
            }
            Throwable cause = ex.getCause();
            if (cause instanceof CliExitException ce2) {
                return ce2.exitCode();
            }
            cmd2.getErr().println("error: " + ex.getMessage());
            return 1;
        });
        return commandLine;
    }
}
