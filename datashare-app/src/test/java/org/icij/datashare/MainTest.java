package org.icij.datashare;

import org.icij.datashare.cli.CliExitException;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import picocli.CommandLine;

import static org.fest.assertions.Assertions.assertThat;

public class MainTest {

    @Test
    public void test_legacy_no_args() {
        assertThat(Main.isLegacyInvocation(new String[]{})).isTrue();
    }

    @Test
    public void test_legacy_empty_arg() {
        assertThat(Main.isLegacyInvocation(new String[]{""})).isTrue();
    }

    @Test
    public void test_legacy_whitespace_arg() {
        assertThat(Main.isLegacyInvocation(new String[]{"  "})).isTrue();
    }

    @Test
    public void test_legacy_long_flag() {
        assertThat(Main.isLegacyInvocation(new String[]{"--mode=SERVER"})).isTrue();
    }

    @Test
    public void test_legacy_short_flag() {
        assertThat(Main.isLegacyInvocation(new String[]{"-m", "CLI"})).isTrue();
    }

    @Test
    public void test_legacy_help_flag() {
        assertThat(Main.isLegacyInvocation(new String[]{"--help"})).isTrue();
    }

    @Test
    public void test_legacy_version_flag() {
        assertThat(Main.isLegacyInvocation(new String[]{"-v"})).isTrue();
    }

    @Test
    public void test_legacy_settings_flag() {
        assertThat(Main.isLegacyInvocation(new String[]{"-s", "/path/to/settings"})).isTrue();
    }

    @Test
    public void test_legacy_stages_flag() {
        assertThat(Main.isLegacyInvocation(new String[]{"--stages=SCAN,INDEX"})).isTrue();
    }

    @Test
    public void test_legacy_plugin_list_flag() {
        assertThat(Main.isLegacyInvocation(new String[]{"--pluginList"})).isTrue();
    }

    @Test
    public void test_legacy_api_key_flag() {
        assertThat(Main.isLegacyInvocation(new String[]{"-k", "alice"})).isTrue();
    }

    @Test
    public void test_legacy_unknown_word() {
        // Unknown first words should go to legacy path (backward compat)
        assertThat(Main.isLegacyInvocation(new String[]{"unknown"})).isTrue();
    }

    @Test
    public void test_legacy_random_text() {
        assertThat(Main.isLegacyInvocation(new String[]{"foo", "bar"})).isTrue();
    }

    @Test
    public void test_new_app() {
        assertThat(Main.isLegacyInvocation(new String[]{"app", "start"})).isFalse();
    }

    @Test
    public void test_new_app_with_options() {
        assertThat(Main.isLegacyInvocation(new String[]{"app", "start", "--mode", "SERVER"})).isFalse();
    }

    @Test
    public void test_new_worker() {
        assertThat(Main.isLegacyInvocation(new String[]{"worker", "run"})).isFalse();
    }

    @Test
    public void test_new_stage() {
        assertThat(Main.isLegacyInvocation(new String[]{"stage", "run", "SCAN"})).isFalse();
    }

    @Test
    public void test_new_plugin() {
        assertThat(Main.isLegacyInvocation(new String[]{"plugin", "list"})).isFalse();
    }

    @Test
    public void test_new_plugin_install() {
        assertThat(Main.isLegacyInvocation(new String[]{"plugin", "install", "foo"})).isFalse();
    }

    @Test
    public void test_new_extension() {
        assertThat(Main.isLegacyInvocation(new String[]{"extension", "install", "foo"})).isFalse();
    }

    @Test
    public void test_new_extension_list() {
        assertThat(Main.isLegacyInvocation(new String[]{"extension", "list"})).isFalse();
    }

    @Test
    public void test_new_api_key() {
        assertThat(Main.isLegacyInvocation(new String[]{"api-key", "create", "alice"})).isFalse();
    }

    @Test
    public void test_new_api_key_get() {
        assertThat(Main.isLegacyInvocation(new String[]{"api-key", "get", "alice"})).isFalse();
    }

    @Test
    public void test_new_api_key_delete() {
        assertThat(Main.isLegacyInvocation(new String[]{"api-key", "delete", "alice"})).isFalse();
    }

    @Test
    public void test_new_help_subcommand() {
        assertThat(Main.isLegacyInvocation(new String[]{"help"})).isFalse();
    }

    @Test
    public void test_new_subcommand_after_options() {
        // Shell script injects --elasticsearch* options before the subcommand
        assertThat(Main.isLegacyInvocation(new String[]{"--elasticsearchPath", "/path", "help"})).isFalse();
    }

    @Test
    public void test_new_app_after_options() {
        assertThat(Main.isLegacyInvocation(new String[]{"--elasticsearchPath", "/path", "app", "start"})).isFalse();
    }

    @Test
    public void test_cli_exit_exception_sets_its_own_exit_code() {
        CommandLine commandLine = new CommandLine(new ThrowingCommand(5));
        commandLine.setExecutionExceptionHandler(Main.cliExitHandler(new AtomicBoolean()));

        assertThat(commandLine.execute()).isEqualTo(5);
    }

    @Test
    public void test_other_exceptions_keep_the_default_handling() {
        CommandLine commandLine = new CommandLine(new ThrowingCommand(-1));
        commandLine.setExecutionExceptionHandler(Main.cliExitHandler(new AtomicBoolean()));

        assertThat(commandLine.execute()).isEqualTo(1);
    }

    @Test
    public void test_cli_exit_exception_with_code_zero_still_exits_zero() {
        // UserDeleteCommand throws CliExitException(0) on the --if-exists noop path:
        // a handler mapping "threw" to "failed" would turn that into a spurious failure
        CommandLine commandLine = new CommandLine(new ThrowingCommand(0));
        commandLine.setExecutionExceptionHandler(Main.cliExitHandler(new AtomicBoolean()));

        assertThat(commandLine.execute()).isEqualTo(0);
    }

    @Test
    public void test_a_cli_only_task_flag_under_a_web_server_mode_is_refused() {
        Properties properties = new Properties();
        properties.setProperty("mode", "LOCAL");
        properties.setProperty("grantAdmin", "alice");

        assertThat(Main.cliOnlyTaskOptIn(properties)).isEqualTo("grantAdmin");
    }

    @Test
    public void test_no_task_flag_means_no_refusal() {
        Properties properties = new Properties();
        properties.setProperty("mode", "LOCAL");

        assertThat(Main.cliOnlyTaskOptIn(properties)).isNull();
    }

    @Test
    public void test_a_task_flag_under_cli_mode_is_fine() {
        Properties properties = new Properties();
        properties.setProperty("mode", "CLI");
        properties.setProperty("grantAdmin", "alice");

        assertThat(Main.cliOnlyTaskOptIn(properties)).isNull();
    }

    @Test
    public void test_an_absent_mode_defaults_to_local_and_still_refuses() {
        // the legacy parser leaves mode unset, which is exactly the reported case:
        // `datashare --grantAdmin alice` started a web server instead of running the task
        Properties properties = new Properties();
        properties.setProperty("createApiKey", "alice");

        assertThat(Main.cliOnlyTaskOptIn(properties)).isEqualTo("createApiKey");
    }

    @Test
    public void test_a_cli_exit_exception_is_recorded_as_handled() {
        // exit code 0 alone cannot tell runPicocli that the command already finished its work.
        // Without this flag, declining a `user delete` confirmation (CliExitException(0)) falls
        // through to startApplication with empty properties and starts a full indexing pipeline.
        AtomicBoolean handled = new AtomicBoolean();
        CommandLine commandLine = new CommandLine(new ThrowingCommand(0));
        commandLine.setExecutionExceptionHandler(Main.cliExitHandler(handled));

        assertThat(commandLine.execute()).isEqualTo(0);
        assertThat(handled.get()).isTrue();
    }

    @Test
    public void test_a_command_that_does_not_throw_is_not_recorded_as_handled() {
        AtomicBoolean handled = new AtomicBoolean();
        CommandLine commandLine = new CommandLine(new QuietCommand());
        commandLine.setExecutionExceptionHandler(Main.cliExitHandler(handled));

        assertThat(commandLine.execute()).isEqualTo(0);
        assertThat(handled.get()).isFalse();
    }

    @CommandLine.Command(name = "quiet")
    static class QuietCommand implements Runnable {
        @Override
        public void run() {
        }
    }

    @CommandLine.Command(name = "throwing")
    static class ThrowingCommand implements Runnable {
        private final int code;

        ThrowingCommand(int code) {
            this.code = code;
        }

        @Override
        public void run() {
            throw code < 0 ? new IllegalStateException("boom") : new CliExitException(code);
        }
    }
}
