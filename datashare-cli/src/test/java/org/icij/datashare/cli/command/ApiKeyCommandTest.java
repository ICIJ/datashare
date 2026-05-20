package org.icij.datashare.cli.command;

import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class ApiKeyCommandTest extends AbstractDatashareCommandTest {

    @Test
    public void test_api_key_create() {
        Properties props = parse("api-key", "create", "alice");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("createApiKey", "alice"));
    }

    @Test
    public void test_api_key_get() {
        Properties props = parse("api-key", "get", "alice");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("apiKey", "alice"));
    }

    @Test
    public void test_api_key_delete() {
        Properties props = parse("api-key", "delete", "alice");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("deleteApiKey", "alice"));
    }

    @Test
    public void test_api_key_create_missing_user_fails() {
        int exitCode = parseExitCode("api-key", "create");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_api_key_get_missing_user_fails() {
        int exitCode = parseExitCode("api-key", "get");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_api_key_delete_missing_user_fails() {
        int exitCode = parseExitCode("api-key", "delete");
        assertThat(exitCode).isNotEqualTo(0);
    }
}
