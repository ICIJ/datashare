package org.icij.datashare;

import org.junit.Test;

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
}
