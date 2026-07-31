package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.utils.AtomicDirectorySwap;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // The interrupt flag is the JDK's cancellation mechanism and the producer restores it on purpose, so
    // it survives the test that set it: cleared once here rather than in every case's finally.
    @After public void clearTheInterruptFlag() {
        Thread.interrupted();
    }

    static class CountingArtifact implements Artifact {
        final ArtifactType type; final Map<String, Object> taskInput; final AtomicInteger produced = new AtomicInteger();
        boolean fail = false; boolean producesEmpty = false;
        CountingArtifact(String type, int version) { this.type = ArtifactType.fromToken(type); this.taskInput = Map.of("type", type, "version", version); }
        public ArtifactType type() { return type; }
        public Map<String, Object> taskInput() { return taskInput; }
        public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
            if (fail) { throw new ArtifactException("boom", null); }
            produced.incrementAndGet();
            if (producesEmpty) { return ManifestEntry.empty(taskInput); }
            writePayload(ctx.docArtifactDir());
            return ManifestEntry.singleFile(taskInput, "text/plain", "a.txt");
        }
        // skip-if-current now checks the payload, so a fake that records without writing is never skipped.
        private void writePayload(Path docArtifactDir) throws ArtifactException {
            try {
                if (type == ArtifactType.RAW) {
                    Files.createDirectories(docArtifactDir);
                    Files.write(docArtifactDir.resolve(ArtifactPath.RAW_FILE), new byte[]{1});
                    Files.write(docArtifactDir.resolve(ArtifactPath.RAW_SIDECAR_FILE), "{}".getBytes());
                } else {
                    Files.createDirectories(ArtifactPath.payloadDir(docArtifactDir, ArtifactType.STRUCTURE));
                    Files.writeString(ArtifactPath.payloadPage(docArtifactDir, ArtifactType.STRUCTURE, 1, "md"), "page");
                }
            } catch (IOException cannotWrite) {
                throw new ArtifactException("cannot write the fake payload", cannotWrite);
            }
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

    @Test public void test_regenerates_when_the_recorded_structure_payload_is_gone() throws Exception {
        // A payload gone from under a complete entry must be repaired by a plain re-run, not skipped (#2300).
        CountingArtifact structure = new CountingArtifact("structure", 1);
        producer.run(List.of(structure), ctx(), false);
        AtomicDirectorySwap.discard(ArtifactPath.payloadDir(dir.getRoot().toPath(), ArtifactType.STRUCTURE));

        producer.run(List.of(structure), ctx(), false);

        assertThat(structure.produced.get()).isEqualTo(2);
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

    @Test public void test_unreadable_content_records_an_empty_entry_and_is_not_reprocessed() throws Exception {
        // A corpus always holds a few files no parser can read (a truncated docx, a zip member that is not
        // the OOXML it claims to be), and nothing will ever make them parse.
        CountingArtifact structure = unreadable();

        boolean allSucceeded = producer.run(List.of(structure), ctx(), false);

        assertThat(allSucceeded).isTrue();
        ManifestEntry recorded = repository.get(dir.getRoot().toPath(), "structure");
        assertThat(recorded.isTerminal()).isTrue();
        assertThat(recorded.isComplete()).isFalse();
        assertThat(recorded.isCurrentFor(structure.taskInput())).isTrue();
        producer.run(List.of(structure), ctx(), false);
        assertThat(structure.produced.get()).isEqualTo(0);
    }

    @Test public void test_unreadable_content_during_a_cancellation_records_nothing() throws Exception {
        // Tika reports a cancelled parse as a parse failure too, and recording "this document has no
        // structure" because the operator pressed cancel is a lie only --artifactsForce could undo.
        ArtifactProducer cancelledProducer = new ArtifactProducer(repository, () -> true);
        CountingArtifact structure = new CountingArtifact("structure", 1) {
            public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
                Thread.interrupted(); // cleared already, as Tika leaves it
                throw new UnreadableContentException("doc-id", new InterruptedException());
            }
        };

        boolean allSucceeded = cancelledProducer.run(List.of(structure), ctx(), false);

        assertThat(allSucceeded).isTrue();
        assertThat(repository.get(dir.getRoot().toPath(), "structure")).isNull();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test public void test_a_configuration_failure_ends_the_run_instead_of_failing_one_type() throws Exception {
        // It fails every document the same way, so counting it once per document works through the whole
        // queue writing the same ERROR. Unchecked, so this per-type catch cannot swallow it.
        CountingArtifact broken = new CountingArtifact("structure", 1) {
            public ManifestEntry produce(ArtifactContext ctx) {
                throw new ArtifactConfigurationException(new IllegalStateException("no parser for it"));
            }
        };
        CountingArtifact raw = new CountingArtifact("raw", 1);

        // assertThrows is unavailable here: junit-dep 4.9 on this module's classpath shadows org.junit.Assert.
        try {
            producer.run(List.of(broken, raw), ctx(), false);
            org.junit.Assert.fail("expected the configuration failure to end the run");
        } catch (ArtifactConfigurationException expected) {
            assertThat(repository.get(dir.getRoot().toPath(), "structure")).isNull();
        }
    }

    @Test public void test_deduplicates_by_type() throws Exception {
        CountingArtifact a = new CountingArtifact("raw", 1);
        CountingArtifact b = new CountingArtifact("raw", 1);
        producer.run(List.of(a, b), ctx(), false);
        assertThat(a.produced.get() + b.produced.get()).isEqualTo(1);
    }

    // The four cases below are a 2x2: a cancel was requested or was not, and the signal reaches the
    // producer as the interrupt flag or only in the exception's cause chain.

    @Test public void test_a_cancel_stops_the_remaining_types() throws Exception {
        // Flag still set when we catch, so the top-of-produce guard stops the next type before it tries.
        // It counts as a cancellation because the task reports one, not because of the flag.
        ArtifactProducer cancelledProducer = new ArtifactProducer(repository, () -> true);
        CountingArtifact structure = new CountingArtifact("structure", 1);

        boolean allSucceeded = cancelledProducer.run(List.of(interruptingArtifact(), structure), ctx(), false);

        assertThat(allSucceeded).isTrue(); // a cancelled type is skipped, not failed
        assertThat(structure.produced.get()).isEqualTo(0);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test public void test_an_interrupt_without_a_cancel_fails_the_document_and_lets_later_types_run() throws Exception {
        // A library that interrupts and re-sets the flag, or an HTTP client's timeout handling, sets it
        // with no cancel in sight. Believing it would end the whole remaining queue with nbFailed at 0.
        CountingArtifact structure = new CountingArtifact("structure", 1);

        boolean allSucceeded = producer.run(List.of(interruptingArtifact(), structure), ctx(), false);

        assertThat(allSucceeded).isFalse();
        assertThat(structure.produced.get()).isEqualTo(1);
    }

    @Test public void test_a_cancel_recognised_from_the_cause_chain_stops_the_remaining_types() throws Exception {
        ArtifactProducer cancelledProducer = new ArtifactProducer(repository, () -> true);
        CountingArtifact structure = new CountingArtifact("structure", 1);

        boolean allSucceeded = cancelledProducer.run(
                List.of(artifactThrowingAWrappedInterrupt(new InterruptedException()), structure), ctx(), false);

        assertThat(allSucceeded).isTrue();
        assertThat(structure.produced.get()).isEqualTo(0);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test public void test_a_wrapped_interrupt_without_a_cancel_fails_the_document_and_lets_later_types_run() throws Exception {
        // Tika's fork and external parsers wrap failures that have nothing to do with cancellation in an
        // InterruptedException.
        CountingArtifact structure = new CountingArtifact("structure", 1);

        boolean allSucceeded = producer.run(
                List.of(artifactThrowingAWrappedInterrupt(new InterruptedException()), structure), ctx(), false);

        assertThat(allSucceeded).isFalse();
        assertThat(structure.produced.get()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test public void test_a_cancel_recognised_from_an_interrupted_io_exception_stops_the_remaining_types() throws Exception {
        // extract-lib's cancellation path surfaces this one, and being an IOException rather than an
        // InterruptedException it would otherwise be counted as a failed document.
        ArtifactProducer cancelledProducer = new ArtifactProducer(repository, () -> true);
        CountingArtifact structure = new CountingArtifact("structure", 1);

        boolean allSucceeded = cancelledProducer.run(
                List.of(artifactThrowingAWrappedInterrupt(new InterruptedIOException()), structure), ctx(), false);

        assertThat(allSucceeded).isTrue();
        assertThat(structure.produced.get()).isEqualTo(0);
    }

    @Test(timeout = 5000) public void test_a_self_referential_cause_chain_does_not_spin_forever() throws Exception {
        // A custom or deserialised exception can return itself from getCause(), which hangs the worker
        // inside its own catch block until the task's one-day timeout, with nothing logged to say so.
        ArtifactProducer cancelledProducer = new ArtifactProducer(repository, () -> true);
        CountingArtifact selfCaused = new CountingArtifact("raw", 1) {
            public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
                throw new ArtifactException("boom", null) {
                    @Override public synchronized Throwable getCause() { return this; }
                };
            }
        };

        assertThat(cancelledProducer.run(List.of(selfCaused), ctx(), false)).isFalse();
    }

    private CountingArtifact unreadable() {
        return new CountingArtifact("structure", 1) {
            public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
                throw new UnreadableContentException("doc-id", new IOException("not a valid OOXML file"));
            }
        };
    }

    // Fails with the interrupt flag left set, as a library that interrupts and re-sets it does.
    private CountingArtifact interruptingArtifact() {
        return new CountingArtifact("raw", 1) {
            public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
                Thread.currentThread().interrupt();
                throw new ArtifactException("boom", null);
            }
        };
    }

    // A wrapped interrupt, as Tika delivers it: the flag is already cleared by the time the producer
    // catches (the inner blocking call consumed it throwing), so the cause chain is all that is left.
    private CountingArtifact artifactThrowingAWrappedInterrupt(Exception interrupt) {
        return new CountingArtifact("raw", 1) {
            public ManifestEntry produce(ArtifactContext ctx) throws ArtifactException {
                Thread.interrupted();
                throw new ArtifactException("boom", interrupt);
            }
        };
    }
}
