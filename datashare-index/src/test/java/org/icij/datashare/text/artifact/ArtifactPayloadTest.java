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

    private ManifestEntry completeSingleFile() {
        return ManifestEntry.singleFile(TASK_INPUT, "image/jpeg", "image2.jpg").withTerminalStatus();
    }

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
    public void test_raw_payload_without_its_sidecar_is_missing() throws Exception {
        // SourceExtractor.hasCachedEmbeddedSource needs both, so a pair a JVM death left half written is
        // unservable and the document needs re-producing.
        Files.createFile(docDir().resolve(ArtifactPath.RAW_FILE));

        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.RAW, completeSingleFile())).isTrue();
    }

    @Test
    public void test_an_unstamped_entry_is_checked_too() {
        // The write-side callers ask before withTerminalStatus() stamps the entry.
        ManifestEntry unstamped = ManifestEntry.singleFile(TASK_INPUT, "image/jpeg", "image2.jpg");

        assertThat(unstamped.status()).isNull();
        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.RAW, unstamped)).isTrue();
    }

    @Test
    public void test_an_empty_entry_advertises_no_payload() {
        // A root's raw source is the on-disk original: absence here is not damage to repair, forever.
        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.RAW, ManifestEntry.empty(TASK_INPUT))).isFalse();
        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.STRUCTURE, ManifestEntry.empty(TASK_INPUT))).isFalse();
    }

    @Test
    public void test_structure_payload_dir_absent_is_missing() {
        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.STRUCTURE,
                ManifestEntry.paginated(TASK_INPUT, 2).withTerminalStatus())).isTrue();
    }

    @Test
    public void test_structure_payload_missing_the_last_page_it_advertises_is_missing() throws Exception {
        // AtomicDirectorySwap.discard deletes page by page, so the directory can survive holding a subset.
        Files.createDirectories(ArtifactPath.structureDir(docDir()));
        Files.writeString(ArtifactPath.structurePage(docDir(), 1, "md"), "# Title");

        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.STRUCTURE,
                ManifestEntry.paginated(TASK_INPUT, 2).withTerminalStatus())).isTrue();
    }

    @Test
    public void test_a_structure_entry_advertising_no_page_count_falls_back_to_its_directory() throws Exception {
        // A Python producer writes manifest.json too, so the count can be absent or nonsensical.
        Files.createDirectories(ArtifactPath.structureDir(docDir()));

        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.STRUCTURE,
                ManifestEntry.singleFile(TASK_INPUT, "text/markdown", "page-1.md").withTerminalStatus())).isFalse();
    }

    @Test
    public void test_structure_payload_dir_holding_the_pages_it_advertises_is_not_missing() throws Exception {
        Files.createDirectories(ArtifactPath.structureDir(docDir()));
        Files.writeString(ArtifactPath.structurePage(docDir(), 1, "md"), "# Title");
        Files.writeString(ArtifactPath.structurePage(docDir(), 2, "md"), "# Second");

        assertThat(ArtifactPayload.isMissing(docDir(), ArtifactType.STRUCTURE,
                ManifestEntry.paginated(TASK_INPUT, 2).withTerminalStatus())).isFalse();
    }
}
