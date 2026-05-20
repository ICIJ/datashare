package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import org.icij.datashare.cli.Prompter;
import org.junit.Test;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class UserCommandTest extends AbstractDatashareCommandTest {

    @Test
    public void test_user_without_subcommand_exits_2() {
        int exitCode = parseExitCode("user");
        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    public void test_user_create_happy_path() {
        Properties props = parse("user", "create", "alice",
                "--email", "alice@example.org",
                "--password", "supersecret");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props.getProperty("userCreate")).isEqualTo("alice");
        assertThat(props.getProperty("userCreate.email")).isEqualTo("alice@example.org");
        assertThat(props.getProperty("userCreate.password")).isEqualTo("supersecret");
        assertThat(props.getProperty("userCreate.provider")).isEqualTo("local");
        // Name is omitted when not explicitly set; the service defaults it to login.
        assertThat(props.getProperty("userCreate.name")).isNull();
    }

    @Test
    public void test_user_create_with_groups() {
        Properties props = parse("user", "create", "alice",
                "--email", "alice@example.org",
                "--password", "pw",
                "--groups", "p1,p2");
        assertThat(props.getProperty("userCreate.groups")).isEqualTo("p1,p2");
    }

    @Test
    public void test_user_create_with_name() {
        Properties props = parse("user", "create", "alice",
                "--email", "alice@example.org",
                "--password", "pw",
                "--name", "Alice Smith");
        assertThat(props.getProperty("userCreate.name")).isEqualTo("Alice Smith");
    }

    @Test
    public void test_user_create_invalid_login_exits_5() {
        int exit = parseExitCode("user", "create", "Alice",
                "--email", "alice@example.org", "--password", "pw");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_user_create_invalid_email_exits_5() {
        int exit = parseExitCode("user", "create", "alice",
                "--email", "not-an-email", "--password", "pw");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_user_create_invalid_provider_exits_5() {
        int exit = parseExitCode("user", "create", "alice",
                "--email", "alice@example.org", "--password", "pw",
                "--provider", "ldap");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_user_create_no_input_missing_required_exits_2() {
        int exit = parseExitCode("user", "create", "alice", "--no-input");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    public void test_user_create_if_not_exists_flag() {
        Properties props = parse("user", "create", "alice",
                "--email", "alice@example.org", "--password", "pw",
                "--if-not-exists");
        assertThat(props.getProperty("userCreate.ifNotExists")).isEqualTo("true");
    }

    @Test
    public void test_user_create_if_not_exists_omitted_when_false() {
        Properties props = parse("user", "create", "alice",
                "--email", "alice@example.org", "--password", "pw");
        assertThat(props.getProperty("userCreate.ifNotExists")).isNull();
    }

    @Test
    public void test_user_create_json_flag() {
        Properties props = parse("user", "create", "alice",
                "--email", "alice@example.org", "--password", "pw",
                "--json");
        assertThat(props.getProperty("userCreate.json")).isEqualTo("true");
    }

    @Test
    public void test_user_create_overrides_shared_mode() {
        Properties props = parse("user", "create", "alice",
                "--email", "alice@example.org", "--password", "pw");
        assertThat(props).includes(entry("mode", "CLI"));
    }

    @Test
    public void test_user_delete_happy_path() {
        Properties props = parse("user", "delete", "alice", "--yes");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props.getProperty("userDelete")).isEqualTo("alice");
        // ifExists defaults to false and is omitted from the dispatch props.
        assertThat(props.getProperty("userDelete.ifExists")).isNull();
        // The --yes flag is consumed by the CLI and never reaches dispatch.
        assertThat(props.getProperty("userDelete.yes")).isNull();
    }

    @Test
    public void test_user_delete_if_exists() {
        Properties props = parse("user", "delete", "alice", "--if-exists", "--yes");
        assertThat(props.getProperty("userDelete.ifExists")).isEqualTo("true");
    }

    @Test
    public void test_user_delete_invalid_login_exits_5() {
        int exit = parseExitCode("user", "delete", "Alice", "--yes");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_user_delete_no_input_missing_login_exits_2() {
        int exit = parseExitCode("user", "delete", "--no-input");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    public void test_user_delete_no_input_acts_as_implicit_yes() {
        Properties props = parse("user", "delete", "alice", "--no-input");
        assertThat(props.getProperty("userDelete")).isEqualTo("alice");
    }

    @Test
    public void test_user_delete_json_flag() {
        Properties props = parse("user", "delete", "alice", "--yes", "--json");
        assertThat(props.getProperty("userDelete.json")).isEqualTo("true");
    }

    @Test
    public void test_user_create_prompts_for_email_and_password() {
        UserCreateCommand cmd = new UserCreateCommand();
        StringWriter sink = new StringWriter();
        cmd.prompterOverride = new Prompter(
                new BufferedReader(new StringReader("alice@example.org\n")),
                new PrintWriter(sink, true),
                () -> "secret".toCharArray());
        cmd.loginPositional = "alice";
        cmd.provider = "local";
        cmd.noInput = false;
        cmd.spec = new CommandLine(cmd).getCommandSpec();
        cmd.run();
        Properties props = cmd.getSubcommandProperties();
        assertThat(props.getProperty("userCreate.email")).isEqualTo("alice@example.org");
        assertThat(props.getProperty("userCreate.password")).isEqualTo("secret");
    }

    @Test
    public void test_user_delete_declined_at_confirm_emits_aborted_and_exits_0() {
        UserDeleteCommand cmd = new UserDeleteCommand();
        StringWriter sink = new StringWriter();
        // Confirm prompt receives "n" and returns false; command should abort.
        cmd.prompterOverride = new Prompter(
                new BufferedReader(new StringReader("n\n")),
                new PrintWriter(sink, true),
                () -> new char[0]);
        cmd.loginPositional = "alice";
        cmd.yes = false;
        cmd.noInput = false;
        cmd.spec = new CommandLine(cmd).getCommandSpec();
        try {
            cmd.run();
            org.junit.Assert.fail("expected CliExitException(0)");
        } catch (CliExitException e) {
            assertThat(e.exitCode()).isEqualTo(0);
        }
        Properties props = cmd.getSubcommandProperties();
        // Aborted runs must not produce a dispatch marker.
        assertThat(props.getProperty("userDelete")).isNull();
    }

    @Test
    public void test_user_delete_confirmed_at_confirm_dispatches() {
        UserDeleteCommand cmd = new UserDeleteCommand();
        StringWriter sink = new StringWriter();
        cmd.prompterOverride = new Prompter(
                new BufferedReader(new StringReader("y\n")),
                new PrintWriter(sink, true),
                () -> new char[0]);
        cmd.loginPositional = "alice";
        cmd.yes = false;
        cmd.noInput = false;
        cmd.spec = new CommandLine(cmd).getCommandSpec();
        cmd.run();
        Properties props = cmd.getSubcommandProperties();
        assertThat(props.getProperty("userDelete")).isEqualTo("alice");
    }
}
