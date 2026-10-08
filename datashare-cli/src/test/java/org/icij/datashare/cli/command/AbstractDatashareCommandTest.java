package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import org.junit.After;
import org.junit.Before;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
        CommandLine commandLine = configure(cmd, args);
        commandLine.execute(args);
        return cmd.collectProperties();
    }

    /** stderr emitted by the last {@link #parseExitCodeCapturingErr} call. */
    protected String lastErr;

    /**
     * Runs the command with stderr captured. picocli builds its error writer from System.err when
     * the CommandLine is constructed, which {@link #parseExitCode} does inside this call, so the
     * swap has to wrap the whole thing rather than just the execute.
     */
    protected int parseExitCodeCapturingErr(String... args) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true));
        try {
            return parseExitCode(args);
        } finally {
            System.setErr(original);
            lastErr = buffer.toString();
        }
    }

    protected int parseExitCode(String... args) {
        DatashareCommand cmd = new DatashareCommand();
        CommandLine commandLine = configure(cmd, args);
        return commandLine.execute(args);
    }

    private static CommandLine configure(DatashareCommand cmd, String[] args) {
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
        DatashareCommand.applySettingsDefaults(commandLine, args);
        return commandLine;
    }
}
