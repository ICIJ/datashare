package org.icij.datashare.text.artifact;

import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class ArtifactPayloadTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    private static final Map<String, Object> TASK_INPUT = Map.of("type", "raw", "version", 1);

    private Path docDir() {
        return dir.getRoot().toPath();
    }

    // The entry raw records for an embedded node, as ArtifactProducer stores it.
    private ManifestEntry completeSingleFile() {
        return ManifestEntry.singleFile(TASK_INPUT, "image/jpeg", "image2.jpg").withTerminalStatus();
    }

    // What extract-lib leaves behind for an embedded node: the payload and its sidecar, in that order.
    private void writeRawPair() throws Exception {
        Files.createFile(docDir().resolve(ArtifactPath.RAW_FILE));
        Files.createFile(docDir().resolve(ArtifactPath.RAW_SIDECAR_FILE));
    }

    @Test
    public void test_raw_payload_on_disk_is_not_missing() throws Exception {
        writeRawPair();

        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.RAW, completeSingleFile())).isFalse();
    }

    @Test
    public void test_raw_payload_gone_is_missing() {
        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.RAW, completeSingleFile())).isTrue();
    }

    @Test
    public void test_an_unstamped_entry_is_checked_too() {
        // RawArtifact.produce and ManifestRecorder ask before withTerminalStatus() stamps the entry, so a
        // status-based test would answer "nothing to check" for every entry on the write side.
        ManifestEntry unstamped = ManifestEntry.singleFile(TASK_INPUT, "image/jpeg", "image2.jpg");

        assertThat(unstamped.status()).isNull();
        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.RAW, unstamped)).isTrue();
    }

    @Test
    public void test_an_empty_entry_advertises_no_payload() {
        // A root document's raw source is the on-disk original, so nothing is expected in this dir and a
        // re-run must not treat the absence as damage to repair, forever.
        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.RAW, ManifestEntry.empty(TASK_INPUT))).isFalse();
        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.STRUCTURE, ManifestEntry.empty(TASK_INPUT))).isFalse();
    }

    @Test
    public void test_structure_payload_dir_absent_is_missing() {
        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.STRUCTURE,
                ManifestEntry.paginated(TASK_INPUT, 2).withTerminalStatus())).isTrue();
    }

    @Test
    public void test_structure_payload_dir_holding_a_page_is_not_missing() throws Exception {
        Files.createDirectories(ArtifactPath.structureDir(docDir()));
        Files.writeString(ArtifactPath.structurePage(docDir(), 1, "md"), "# Title");

        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.STRUCTURE,
                ManifestEntry.paginated(TASK_INPUT, 2).withTerminalStatus())).isFalse();
    }
}
