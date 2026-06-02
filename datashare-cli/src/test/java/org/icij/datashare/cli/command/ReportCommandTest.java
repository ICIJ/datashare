package org.icij.datashare.cli.command;

import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class ReportCommandTest extends AbstractDatashareCommandTest {

    @Test
    public void test_report_diagnostic_emits_map_and_out_in_cli_mode() {
        Properties props = parse("report", "diagnostic", "/tmp/report-map.json", "/tmp/out");
        assertThat(props).includes(entry("reportDiagnosticMap", "/tmp/report-map.json"));
        assertThat(props).includes(entry("reportDiagnosticOut", "/tmp/out"));
        assertThat(props).includes(entry("mode", "CLI"));
    }

    @Test
    public void test_report_diagnostic_defaults_out_to_current_dir() {
        Properties props = parse("report", "diagnostic", "/tmp/report-map.json");
        assertThat(props).includes(entry("reportDiagnosticMap", "/tmp/report-map.json"));
        assertThat(props).includes(entry("reportDiagnosticOut", "."));
    }

    @Test
    public void test_report_diagnostic_without_map_exits_2() {
        int exit = parseExitCode("report", "diagnostic");
        assertThat(exit).isEqualTo(2);
    }
}
