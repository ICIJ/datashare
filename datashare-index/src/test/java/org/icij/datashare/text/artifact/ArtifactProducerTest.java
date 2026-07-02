package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

public class ArtifactProducerTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private final ManifestStore store = new FilesystemManifestStore();
    private final ArtifactProducer producer = new ArtifactProducer(store);

    static class CountingArtifact implements Artifact {
        final ArtifactType type; final Map<String, Object> taskInput; final AtomicInteger produced = new AtomicInteger();
        boolean fail = false; boolean producesEmpty = false;
        CountingArtifact(String type, int version) { this.type = ArtifactType.fromToken(type); this.taskInput = Map.of("type", type, "version", version); }
        public ArtifactType type() { return type; }
        public Map<String, Object> taskInput() { return taskInput; }
        public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
            if (fail) { throw new ArtifactException("boom", null); }
            produced.incrementAndGet();
            return producesEmpty ? ManifestEntry.empty(taskInput) : ManifestEntry.singleFile(taskInput, "text/plain", "a.txt");
        }
    }

    private ArtifactContext ctx() {
        Document doc = createDoc("doc-id").build();
        return new ArtifactContext(Project.project("prj"), doc, dir.getRoot().toPath(), null);
    }

    @Test public void test_produces_and_records_complete_entry() throws Exception {
        CountingArtifact raw = new CountingArtifact("raw", 1);
        producer.run(List.of(raw), ctx(), false);
        assertThat(raw.produced.get()).isEqualTo(1);
        assertThat(store.get(dir.getRoot().toPath(), "raw").isComplete()).isTrue();
    }

    @Test public void test_skips_when_task_input_matches() throws Exception {
        CountingArtifact raw = new CountingArtifact("raw", 1);
        producer.run(List.of(raw), ctx(), false);
        producer.run(List.of(raw), ctx(), false);
        assertThat(raw.produced.get()).isEqualTo(1);
    }

    @Test public void test_force_bypasses_skip_if_current() throws Exception {
        CountingArtifact raw = new CountingArtifact("raw", 1);
        producer.run(List.of(raw), ctx(), false);
        producer.run(List.of(raw), ctx(), true);
        assertThat(raw.produced.get()).isEqualTo(2);
    }

    @Test public void test_regenerates_when_version_changes() throws Exception {
        producer.run(List.of(new CountingArtifact("raw", 1)), ctx(), false);
        CountingArtifact v2 = new CountingArtifact("raw", 2);
        producer.run(List.of(v2), ctx(), false);
        assertThat(v2.produced.get()).isEqualTo(1);
    }

    @Test public void test_isolates_failing_type() throws Exception {
        CountingArtifact bad = new CountingArtifact("raw", 1); bad.fail = true;
        CountingArtifact good = new CountingArtifact("structure", 1);
        boolean allSucceeded = producer.run(List.of(bad, good), ctx(), false);
        assertThat(store.get(dir.getRoot().toPath(), "raw")).isNull();
        assertThat(store.get(dir.getRoot().toPath(), "structure")).isNotNull();
        assertThat(allSucceeded).isFalse();
    }

    @Test public void test_empty_produce_records_terminal_entry_and_is_not_reprocessed() throws Exception {
        CountingArtifact raw = new CountingArtifact("raw", 1); raw.producesEmpty = true;
        boolean first = producer.run(List.of(raw), ctx(), false);
        assertThat(first).isTrue();
        assertThat(store.get(dir.getRoot().toPath(), "raw").isTerminal()).isTrue();
        assertThat(store.get(dir.getRoot().toPath(), "raw").isComplete()).isFalse();
        producer.run(List.of(raw), ctx(), false);
        assertThat(raw.produced.get()).isEqualTo(1); // empty entry counts as done -> not reprocessed
    }

    @Test public void test_deduplicates_by_type() throws Exception {
        CountingArtifact a = new CountingArtifact("raw", 1);
        CountingArtifact b = new CountingArtifact("raw", 1);
        producer.run(List.of(a, b), ctx(), false);
        assertThat(a.produced.get() + b.produced.get()).isEqualTo(1);
    }
}
