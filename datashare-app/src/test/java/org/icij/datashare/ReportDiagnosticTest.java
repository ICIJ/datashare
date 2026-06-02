package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class ReportDiagnosticTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

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

    @Test public void test_loadFailures_excludes_success_and_keeps_codes() throws Exception {
        File json = folder.newFile("map.json");
        String content = "{\"/a.txt\":\"0\",\"/b.pdf\":\"8|binary\",\"/c.xlsx\":\"4|x\"}";
        Files.write(json.toPath(), content.getBytes(StandardCharsets.ISO_8859_1));

        Map<String, String> failures = ReportDiagnostic.loadFailures(json.toPath());

        assertThat(failures).hasSize(2);
        assertThat(failures.get("/b.pdf")).isEqualTo("8");
        assertThat(failures.get("/c.xlsx")).isEqualTo("4");
        assertThat(failures.containsKey("/a.txt")).isFalse();
    }
}
