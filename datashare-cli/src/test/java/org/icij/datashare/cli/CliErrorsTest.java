package org.icij.datashare.cli;

import org.junit.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

public class CliErrorsTest {
    @CommandLine.Command(name = "noop")
    static class NoopCommand implements Runnable {
        @Override
        public void run() {
        }
    }

    private static CommandLine commandLine(StringWriter err) {
        return new CommandLine(new NoopCommand()).setErr(new PrintWriter(err, true));
    }

    @Test
    public void test_fail_with_json_emits_an_error_object_on_stderr() {
        StringWriter err = new StringWriter();

        try {
            CliErrors.fail(commandLine(err).getCommandSpec(), true, "validation", "email is not valid", 5);
            fail("expected CliExitException");
        } catch (CliExitException e) {
            assertThat(e.exitCode()).isEqualTo(5);
        }
        assertThat(err.toString().trim())
                .isEqualTo("{\"error\":\"validation\",\"message\":\"email is not valid\"}");
    }

    @Test
    public void test_fail_without_json_emits_a_plain_line_on_stderr() {
        StringWriter err = new StringWriter();

        try {
            CliErrors.fail(commandLine(err).getCommandSpec(), false, "validation", "email is not valid", 5);
            fail("expected CliExitException");
        } catch (CliExitException e) {
            assertThat(e.exitCode()).isEqualTo(5);
        }
        assertThat(err.toString().trim()).isEqualTo("error: email is not valid");
    }

    @Test
    public void test_fail_escapes_a_message_holding_quotes() {
        StringWriter err = new StringWriter();

        try {
            CliErrors.fail(commandLine(err).getCommandSpec(), true, "validation", "bad \"value\"", 5);
            fail("expected CliExitException");
        } catch (CliExitException e) {
            // escaping is the point here, the code is incidental
        }
        assertThat(err.toString().trim())
                .isEqualTo("{\"error\":\"validation\",\"message\":\"bad \\\"value\\\"\"}");
    }

    @Test
    public void test_fail_escapes_a_message_holding_a_newline() {
        StringWriter err = new StringWriter();

        try {
            CliErrors.fail(commandLine(err).getCommandSpec(), true, "validation", "line one\nline two", 5);
            fail("expected CliExitException");
        } catch (CliExitException e) {
            // a raw newline would split the object across two lines and break line-oriented parsers
        }
        assertThat(err.toString().trim())
                .isEqualTo("{\"error\":\"validation\",\"message\":\"line one\\nline two\"}");
    }

    @Test
    public void test_fail_with_json_and_a_null_message_still_emits_an_object() {
        StringWriter err = new StringWriter();

        try {
            CliErrors.fail(commandLine(err).getCommandSpec(), true, "runtime", null, 1);
            fail("expected CliExitException");
        } catch (CliExitException e) {
            assertThat(e.exitCode()).isEqualTo(1);
        }
        assertThat(err.toString().trim()).isEqualTo("{\"error\":\"runtime\",\"message\":null}");
    }
}
