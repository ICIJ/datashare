package org.icij.datashare;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class ReportDiagnosticTest {
    @Test public void test_normalizeMessage_collapses_decimal_offsets() {
        assertThat(ReportDiagnostic.normalizeMessage("Error: End-of-File, expected line at offset 170"))
                .isEqualTo("Error: End-of-File, expected line at offset N");
    }

    @Test public void test_normalizeMessage_collapses_hex() {
        assertThat(ReportDiagnostic.normalizeMessage("Invalid header signature; read 0xA2A3526F, expected 0xE11AB1A1"))
                .isEqualTo("Invalid header signature; read 0xN, expected 0xN");
    }

    @Test public void test_normalizeMessage_handles_null() {
        assertThat(ReportDiagnostic.normalizeMessage(null)).isEqualTo("");
    }

    @Test public void test_statusName_maps_known_codes() {
        assertThat(ReportDiagnostic.statusName("0")).isEqualTo("SUCCESS");
        assertThat(ReportDiagnostic.statusName("4")).isEqualTo("FAILURE_NOT_PARSED");
        assertThat(ReportDiagnostic.statusName("8")).isEqualTo("FAILURE_UNKNOWN");
        assertThat(ReportDiagnostic.statusName("99")).isEqualTo("UNKNOWN_CODE(99)");
    }
}
