package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

public class ArtifactProducerTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private final ManifestRepository repository = new FilesystemManifestRepository();
    // No cancellation asked for: the cases below that do simulate one say so explicitly.
    private final ArtifactProducer producer = new ArtifactProducer(repository, () -> false);

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
        assertThat(repository.get(dir.getRoot().toPath(), "raw").isComplete()).isTrue();
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
        assertThat(repository.get(dir.getRoot().toPath(), "raw")).isNull();
        assertThat(repository.get(dir.getRoot().toPath(), "structure")).isNotNull();
        assertThat(allSucceeded).isFalse();
    }

    @Test public void test_empty_produce_records_terminal_entry_and_is_not_reprocessed() throws Exception {
        CountingArtifact raw = new CountingArtifact("raw", 1); raw.producesEmpty = true;
        boolean first = producer.run(List.of(raw), ctx(), false);
        assertThat(first).isTrue();
        assertThat(repository.get(dir.getRoot().toPath(), "raw").isTerminal()).isTrue();
        assertThat(repository.get(dir.getRoot().toPath(), "raw").isComplete()).isFalse();
        producer.run(List.of(raw), ctx(), false);
        assertThat(raw.produced.get()).isEqualTo(1); // empty entry counts as done -> not reprocessed
    }

    @Test public void test_unparseable_content_records_an_empty_entry_and_is_not_reprocessed() throws Exception {
        // A corpus always holds a few files no parser can read (a truncated docx, a zip member that is not
        // the OOXML it claims to be), and nothing will ever make them parse.
        CountingArtifact structure = unparseable();

        boolean allSucceeded = producer.run(List.of(structure), ctx(), false);

        assertThat(allSucceeded).isTrue();
        ManifestEntry recorded = repository.get(dir.getRoot().toPath(), "structure");
        assertThat(recorded.isTerminal()).isTrue();
        assertThat(recorded.isComplete()).isFalse();
        assertThat(recorded.isCurrentFor(structure.taskInput())).isTrue();
        producer.run(List.of(structure), ctx(), false);
        assertThat(structure.produced.get()).isEqualTo(0);
    }

    @Test public void test_unparseable_content_during_a_cancellation_records_nothing() throws Exception {
        // Tika reports a cancelled parse as a parse failure too, and recording "this document has no
        // structure" because the operator pressed cancel is a lie only --artifactsForce could undo.
        ArtifactProducer cancelledProducer = new ArtifactProducer(repository, () -> true);
        CountingArtifact structure = new CountingArtifact("structure", 1) {
            public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
                Thread.interrupted(); // cleared already, as Tika leaves it
                throw new UnparseableContentException("doc-id", new InterruptedException());
            }
        };
        try {
            boolean allSucceeded = cancelledProducer.run(List.of(structure), ctx(), false);

            assertThat(allSucceeded).isTrue();
            assertThat(repository.get(dir.getRoot().toPath(), "structure")).isNull();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear so it does not leak into the next test
        }
    }

    @Test public void test_deduplicates_by_type() throws Exception {
        CountingArtifact a = new CountingArtifact("raw", 1);
        CountingArtifact b = new CountingArtifact("raw", 1);
        producer.run(List.of(a, b), ctx(), false);
        assertThat(a.produced.get() + b.produced.get()).isEqualTo(1);
    }

    @Test public void test_cancellation_stops_the_remaining_types_without_logging_an_error() throws Exception {
        // The flag is still set when we catch here (nothing cleared it), so the top-of-produce guard
        // catches the next type before it even tries. An interrupted thread is a signal in its own
        // right, so this holds without the task reporting a cancel (the producer here says false).
        CountingArtifact cancelled = new CountingArtifact("raw", 1) {
            public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
                Thread.currentThread().interrupt();
                throw new ArtifactException("boom", null);
            }
        };
        CountingArtifact structure = new CountingArtifact("structure", 1);
        try {
            boolean allSucceeded = producer.run(List.of(cancelled, structure), ctx(), false);
            assertThat(allSucceeded).isTrue(); // a cancelled type is skipped, not failed
            assertThat(structure.produced.get()).isEqualTo(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear so it does not leak into the next test
        }
    }

    @Test public void test_cancellation_detected_via_cause_chain_stops_the_remaining_types() throws Exception {
        // Tika's real shape: the flag is already cleared by the time we catch (the inner blocking call
        // consumed it throwing), so the cause chain is the only thing left to recognise the cancel by.
        ArtifactProducer cancelledProducer = new ArtifactProducer(repository, () -> true);
        CountingArtifact structure = new CountingArtifact("structure", 1);
        try {
            boolean allSucceeded = cancelledProducer.run(List.of(interruptInCauseChain(), structure), ctx(), false);
            assertThat(allSucceeded).isTrue();
            assertThat(structure.produced.get()).isEqualTo(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear so it does not leak into the next test
        }
    }

    @Test public void test_interrupt_in_the_cause_chain_without_a_cancel_is_a_failure_and_lets_later_types_run() throws Exception {
        // Tika's fork and external parsers wrap failures that have nothing to do with cancellation in an
        // InterruptedException.
        CountingArtifact structure = new CountingArtifact("structure", 1);

        boolean allSucceeded = producer.run(List.of(interruptInCauseChain(), structure), ctx(), false);

        assertThat(allSucceeded).isFalse();
        assertThat(structure.produced.get()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private CountingArtifact unparseable() {
        return new CountingArtifact("structure", 1) {
            public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
                throw new UnparseableContentException("doc-id", new IOException("not a valid OOXML file"));
            }
        };
    }

    private CountingArtifact interruptInCauseChain() {
        return new CountingArtifact("raw", 1) {
            public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
                Thread.interrupted(); // clear, simulating what the inner blocking call already did
                throw new ArtifactException("boom", new InterruptedException());
            }
        };
    }
}
