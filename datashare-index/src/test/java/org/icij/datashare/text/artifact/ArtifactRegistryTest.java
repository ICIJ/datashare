package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

public class ArtifactRegistryTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private final ManifestStore store = new ManifestStore();

    /** Fake type recording how many times it produced. */
    static class CountingArtifact implements Artifact {
        final String type;
        final Map<String, Object> taskInput;
        final AtomicInteger produced = new AtomicInteger();
        boolean fail = false;
        CountingArtifact(String type, int version) { this.type = type; this.taskInput = Map.of("type", type, "version", version); }
        public String type() { return type; }
        public Map<String, Object> taskInput() { return taskInput; }
        public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
            if (fail) throw new ArtifactException("boom", null);
            produced.incrementAndGet();
            return ManifestEntry.singleFile(taskInput, "text/plain", "a.txt");
        }
    }

    private ArtifactContext ctx() {
        Document doc = createDoc("doc-id").build();
        return new ArtifactContext(Project.project("prj"), doc, dir.getRoot().toPath(), null);
    }

    @Test
    public void test_select_bare_returns_all_types() {
        ArtifactRegistry registry = new ArtifactRegistry(List.of(new CountingArtifact("raw", 1), new CountingArtifact("structure", 1)), store);
        assertThat(registry.select(null)).isEqualTo(Set.of("raw", "structure"));
        assertThat(registry.select("")).isEqualTo(Set.of("raw", "structure"));
    }

    @Test
    public void test_select_csv_returns_subset_case_insensitive() {
        ArtifactRegistry registry = new ArtifactRegistry(List.of(new CountingArtifact("raw", 1), new CountingArtifact("structure", 1)), store);
        assertThat(registry.select(" RAW ")).isEqualTo(Set.of("raw"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_select_unknown_token_throws() {
        new ArtifactRegistry(List.of(new CountingArtifact("raw", 1)), store).select("raw,bogus");
    }

    @Test
    public void test_run_produces_and_records_complete_entry() throws Exception {
        CountingArtifact raw = new CountingArtifact("raw", 1);
        ArtifactRegistry registry = new ArtifactRegistry(List.of(raw), store);
        registry.run(Set.of("raw"), ctx());
        assertThat(raw.produced.get()).isEqualTo(1);
        assertThat(store.get(dir.getRoot().toPath(), "raw").isComplete()).isTrue();
    }

    @Test
    public void test_run_skips_when_task_input_matches() throws Exception {
        CountingArtifact raw = new CountingArtifact("raw", 1);
        ArtifactRegistry registry = new ArtifactRegistry(List.of(raw), store);
        registry.run(Set.of("raw"), ctx());
        registry.run(Set.of("raw"), ctx());
        assertThat(raw.produced.get()).isEqualTo(1); // second run skipped
    }

    @Test
    public void test_run_regenerates_when_version_changes() throws Exception {
        new ArtifactRegistry(List.of(new CountingArtifact("raw", 1)), store).run(Set.of("raw"), ctx());
        CountingArtifact v2 = new CountingArtifact("raw", 2);
        new ArtifactRegistry(List.of(v2), store).run(Set.of("raw"), ctx());
        assertThat(v2.produced.get()).isEqualTo(1); // taskInput differs -> regenerated
    }

    @Test
    public void test_run_isolates_failing_type() throws Exception {
        CountingArtifact bad = new CountingArtifact("raw", 1); bad.fail = true;
        CountingArtifact good = new CountingArtifact("structure", 1);
        ArtifactRegistry registry = new ArtifactRegistry(List.of(bad, good), store);
        registry.run(Set.of("raw", "structure"), ctx());
        assertThat(store.get(dir.getRoot().toPath(), "raw")).isNull();       // failed -> no entry
        assertThat(store.get(dir.getRoot().toPath(), "structure")).isNotNull(); // sibling still ran
    }
}
