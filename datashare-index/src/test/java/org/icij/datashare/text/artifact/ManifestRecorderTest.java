package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

public class ManifestRecorderTest {
    private static final String EMBEDDED_ID = "eeee1111222233334444555566667777";
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final ManifestRepository repository = new FilesystemManifestRepository();
    private final RawArtifact raw = new RawArtifact();

    private Path projectRoot() {
        return tmp.getRoot().toPath().resolve("prj");
    }

    private ManifestRecorder recorder(boolean force) {
        return new ManifestRecorder(repository, projectRoot(), List.of(raw), force);
    }

    private Document embedded(String id) {
        return createDoc(id).with(Path.of("/tmp/image2.jpg"))
                .ofContentType("image/jpeg").withExtractionLevel((short) 1).build();
    }

    private void writePayload(String docId) throws Exception {
        Files.createDirectories(ArtifactPath.dir(projectRoot(), docId));
        Files.write(ArtifactPath.dir(projectRoot(), docId).resolve("raw"), new byte[]{1, 2, 3});
    }

    @Test
    public void test_records_single_file_entry_for_embedded() throws Exception {
        Document doc = embedded(EMBEDDED_ID);
        writePayload(doc.getId());

        recorder(false).record(doc);

        ManifestEntry entry = repository.get(ArtifactPath.dir(projectRoot(), doc.getId()), "raw");
        assertThat(entry).isNotNull();
        assertThat(entry.isComplete()).isTrue();
        assertThat(entry.contentType()).isEqualTo("image/jpeg");
        assertThat(entry.filename()).isEqualTo("image2.jpg");
    }

    @Test
    public void test_skips_embedded_when_payload_missing() throws Exception {
        Document doc = embedded(EMBEDDED_ID);

        recorder(false).record(doc);

        assertThat(repository.get(ArtifactPath.dir(projectRoot(), doc.getId()), "raw")).isNull();
    }

    @Test
    public void test_null_task_input_entry_is_not_current() throws Exception {
        Document doc = embedded(EMBEDDED_ID);
        Path dir = ArtifactPath.dir(projectRoot(), doc.getId());
        repository.put(dir, "raw", new ManifestEntry(ManifestEntryStatus.COMPLETE, null, null, null, null, null, null));
        writePayload(doc.getId());

        recorder(false).record(doc);

        ManifestEntry entry = repository.get(dir, "raw");
        assertThat(entry).isNotNull();
        assertThat(entry.filename()).isEqualTo("image2.jpg");
    }

    @Test
    public void test_records_empty_entry_for_root() throws Exception {
        Document root = createDoc("rootrootrootroot").with(Path.of("/tmp/root.pdf"))
                .ofContentType("application/pdf").withExtractionLevel((short) 0).build();

        recorder(false).record(root);

        ManifestEntry entry = repository.get(ArtifactPath.dir(projectRoot(), root.getId()), "raw");
        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
    }

    @Test
    public void test_skip_if_current_leaves_existing_entry() throws Exception {
        Document doc = embedded(EMBEDDED_ID);
        Path dir = ArtifactPath.dir(projectRoot(), doc.getId());
        // A terminal entry with the same taskInput already exists -> record(force=false) must skip.
        repository.put(dir, "raw", ManifestEntry.empty(raw.taskInput()));
        writePayload(doc.getId());

        recorder(false).record(doc);

        assertThat(repository.get(dir, "raw").status()).isEqualTo(ManifestEntryStatus.EMPTY);
    }

    @Test
    public void test_force_overwrites_current_entry() throws Exception {
        Document doc = embedded(EMBEDDED_ID);
        Path dir = ArtifactPath.dir(projectRoot(), doc.getId());
        repository.put(dir, "raw", ManifestEntry.empty(raw.taskInput()));
        writePayload(doc.getId());

        recorder(true).record(doc);

        assertThat(repository.get(dir, "raw").filename()).isEqualTo("image2.jpg");
    }

    @Test
    public void test_no_op_when_raw_not_selected() throws Exception {
        Document doc = embedded(EMBEDDED_ID);
        ManifestRecorder recorder = new ManifestRecorder(repository, projectRoot(), List.of(), false);

        recorder.record(doc);

        assertThat(repository.get(ArtifactPath.dir(projectRoot(), doc.getId()), "raw")).isNull();
    }
}
