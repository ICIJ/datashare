package org.icij.datashare.cli.command;

import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class AppServeCommandTest extends AbstractDatashareCommandTest {

    @Test
    public void test_app_serve_defaults_to_local() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("mode", "LOCAL"));
    }

    @Test
    public void test_app_serve_server() {
        Properties props = parse("app", "start", "--mode", "server");
        assertThat(props).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_app_serve_embedded() {
        Properties props = parse("app", "start", "--mode", "embedded");
        assertThat(props).includes(entry("mode", "EMBEDDED"));
    }

    @Test
    public void test_app_serve_ner() {
        Properties props = parse("app", "start", "--mode", "ner");
        assertThat(props).includes(entry("mode", "NER"));
    }

    @Test
    public void test_app_serve_case_insensitive_uppercase() {
        Properties props = parse("app", "start", "--mode", "SERVER");
        assertThat(props).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_app_serve_case_insensitive_mixed() {
        Properties props = parse("app", "start", "--mode", "Local");
        assertThat(props).includes(entry("mode", "LOCAL"));
    }

    @Test
    public void test_app_serve_invalid_mode_rejected_by_parser() {
        // picocli rejects unknown enum values at parse time — expect non-zero exit
        int exitCode = parseExitCode("app", "start", "--mode", "INVALID");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_app_serve_with_temporal_address() {
        Properties props = parse("--temporalAddress", "http://my-temporal:7233", "app", "start");
        assertThat(props).includes(entry("temporalAddress", "http://my-temporal:7233"));
    }

    @Test
    public void test_app_serve_with_temporal_namespace() {
        Properties props = parse("--temporalNamespace", "my-ns", "app", "start");
        assertThat(props).includes(entry("temporalNamespace", "my-ns"));
    }
}
