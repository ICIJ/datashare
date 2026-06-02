package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.file.Paths.get;
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

    @Test public void test_diagnose_records_failure_for_corrupt_ooxml() {
        Path file = get(getClass().getResource("/diagnostic/corrupt.docx").getPath());
        ReportDiagnostic.Result result = ReportDiagnostic.diagnose(file, "FAILURE_NOT_PARSED");
        assertThat(result.newStatus()).isEqualTo("FAILURE");
        assertThat(result.rootCauseClass()).isNotEmpty();
        assertThat(result.originalStatus()).isEqualTo("FAILURE_NOT_PARSED");
    }

    @Test public void test_diagnose_records_success_for_valid_text() {
        Path file = get(getClass().getResource("/diagnostic/hello.txt").getPath());
        ReportDiagnostic.Result result = ReportDiagnostic.diagnose(file, "FAILURE_NOT_PARSED");
        assertThat(result.newStatus()).isEqualTo("SUCCESS");
        assertThat(result.rootCauseClass()).isEqualTo("");
    }

    @Test public void test_diagnose_records_failure_for_missing_file() {
        ReportDiagnostic.Result result = ReportDiagnostic.diagnose(get("/does/not/exist.pdf"), "FAILURE_NOT_FOUND");
        assertThat(result.newStatus()).isEqualTo("FAILURE");
        assertThat(result.rootCauseClass()).isNotEmpty();
    }

    @Test public void test_summarize_groups_by_status_and_cause() {
        List<ReportDiagnostic.Result> results = List.of(
            new ReportDiagnostic.Result("/a", 1, "FAILURE_NOT_PARSED", "FAILURE",
                "java.io.IOException", "java.io.IOException", "offset N", "Frame.a", "trace", 5),
            new ReportDiagnostic.Result("/b", 1, "FAILURE_NOT_PARSED", "FAILURE",
                "java.io.IOException", "java.io.IOException", "offset N", "Frame.a", "trace", 5),
            new ReportDiagnostic.Result("/c", 1, "FAILURE_UNKNOWN", "SUCCESS",
                "", "", "", "", "", 5));

        Map<String, Long> summary = ReportDiagnostic.summarize(results);

        assertThat(summary.get("FAILURE | java.io.IOException | offset N")).isEqualTo(2L);
        assertThat(summary.get("SUCCESS |  | ")).isEqualTo(1L);
    }

    @Test public void test_main_writes_outputs_and_survives_per_entry() throws Exception {
        String corrupt = getClass().getResource("/diagnostic/corrupt.docx").getPath();
        String valid = getClass().getResource("/diagnostic/hello.txt").getPath();
        File json = folder.newFile("in.json");
        Files.write(json.toPath(),
            ("{\"" + corrupt + "\":\"4|x\",\"" + valid + "\":\"4|y\"}").getBytes(StandardCharsets.ISO_8859_1));
        File outDir = folder.newFolder("out");

        ReportDiagnostic.main(new String[]{json.getAbsolutePath(), outDir.getAbsolutePath()});

        Path jsonl = outDir.toPath().resolve("diagnostic.jsonl");
        Path summary = outDir.toPath().resolve("diagnostic-summary.txt");
        assertThat(jsonl.toFile().exists()).isTrue();
        assertThat(summary.toFile().exists()).isTrue();
        assertThat(Files.readAllLines(jsonl)).hasSize(2);
    }
}
