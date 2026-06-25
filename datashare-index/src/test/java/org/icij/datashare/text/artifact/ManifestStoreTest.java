package org.icij.datashare.text.artifact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.fest.assertions.Assertions.assertThat;

public class ManifestStoreTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private final ManifestStore store = new ManifestStore();

    private ManifestEntry rawEntry() {
        return ManifestEntry.singleFile(Map.of("type", "raw", "version", 1), "application/pdf", "a.pdf").withStatus("complete");
    }

    @Test
    public void test_get_returns_null_when_no_manifest() throws Exception {
        assertThat(store.get(dir.getRoot().toPath(), "raw")).isNull();
    }

    @Test
    public void test_put_then_get_round_trips() throws Exception {
        Path node = dir.getRoot().toPath();
        store.put(node, "raw", rawEntry());
        ManifestEntry read = store.get(node, "raw");
        assertThat(read.isComplete()).isTrue();
        assertThat(read.contentType()).isEqualTo("application/pdf");
        assertThat(read.taskInput()).isEqualTo(Map.of("type", "raw", "version", 1));
    }

    @Test
    public void test_put_two_types_does_not_clobber() throws Exception {
        Path node = dir.getRoot().toPath();
        store.put(node, "raw", rawEntry());
        store.put(node, "structure", ManifestEntry.paginated(Map.of("type", "structure", "version", 1), 3, Map.of("type", "filesystem")).withStatus("complete"));
        assertThat(store.get(node, "raw")).isNotNull();
        assertThat(store.get(node, "structure").total()).isEqualTo(3);
    }

    @Test
    public void test_concurrent_put_of_two_types_keeps_both() throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(node);
        CountDownLatch start = new CountDownLatch(1);
        Runnable a = () -> { try { start.await(); store.put(node, "raw", rawEntry()); } catch (Exception e) { throw new RuntimeException(e); } };
        Runnable b = () -> { try { start.await(); store.put(node, "structure", ManifestEntry.paginated(Map.of("type", "structure", "version", 1), 9, Map.of("type", "filesystem")).withStatus("complete")); } catch (Exception e) { throw new RuntimeException(e); } };
        Thread ta = new Thread(a), tb = new Thread(b);
        ta.start(); tb.start();
        start.countDown();
        ta.join(); tb.join();
        assertThat(store.get(node, "raw")).isNotNull();
        assertThat(store.get(node, "structure")).isNotNull();
    }

    @Test
    public void test_manifest_is_valid_json_after_write() throws Exception {
        Path node = dir.getRoot().toPath();
        store.put(node, "raw", rawEntry());
        Path manifest = node.resolve("manifest.json");
        assertThat(manifest.toFile()).isFile();
        assertThat(new String(Files.readAllBytes(manifest))).startsWith("{");
    }
}
