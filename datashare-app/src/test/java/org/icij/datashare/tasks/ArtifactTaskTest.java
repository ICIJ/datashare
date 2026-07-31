package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestEntry;
import org.icij.datashare.text.artifact.ManifestEntryStatus;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.MemoryDocumentQueue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.slf4j.event.Level;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DEFAULT_QUEUE_CAPACITY;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ArtifactTaskTest {
    private static final String EMBEDDED_DOC_SHA256 = "0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e";
    private static final String EMBEDDED_PDF_SHA256 = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    @Rule public LogbackCapturingRule logback = new LogbackCapturingRule();
    @Mock Indexer mockEs;
    MockIndexer mockIndexer;
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();

    @Test(expected = IllegalArgumentException.class)
    public void test_missing_artifact_dir() {
        new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(Map.of()), null);
    }

    @Test(timeout = 10000)
    public void test_configuration_comes_from_the_task_args() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        // args carry the whole configuration, exactly as IndexTask and every other stage expects.
        Long numberOfDocuments = new ArtifactTask(factory, mockEs,
                new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(Map.of(
                        "artifactDir", artifactDir.getRoot().toString(),
                        "defaultProject", "prj")), null)
                .call();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb").toFile()).isDirectory();
    }

    @Test(timeout = 10000)
    public void test_create_artifact_cache_one_file() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        Long numberOfDocuments = runArtifactTask();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb").toFile()).isDirectory();
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw").toFile()).isFile();
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw.json").toFile()).isFile();
    }

    @Test(timeout = 10000)
    public void test_skip_document_not_found_in_index_with_warning() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add("unknownId");
        queue.add(EMBEDDED_DOC_SHA256);

        Long numberOfDocuments = runArtifactTask();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(logback.logs(Level.WARN)).contains("document <unknownId> could not be retrieved from index prj (missing document or index fetch error), skipping");
        assertThat(logback.logs(Level.ERROR)).contains("1 document(s) could not be retrieved from index prj and got no artifact cache, re-run the ARTIFACT stage for them");
        assertThat(logback.logs(Level.ERROR)).excludes("error in ArtifactTask loop");
    }

    @Test(timeout = 10000)
    public void test_entry_with_routing_fetches_document_with_root_id() throws Exception {
        indexEmbeddedDoc("rootId");
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256 + "|rootId");

        // Pinned to raw: this fixture's root labelling is deliberately inconsistent (extractionLevel says
        // root, rootId says embed), so structure would attempt an embedded lookup it cannot support.
        Long numberOfDocuments = runArtifactTask(Map.of("artifacts", "raw"));

        verify(mockEs).get("prj", EMBEDDED_DOC_SHA256, "rootId", List.of("content", "content_translated"));
        // Raw asks isRootDocument() like the source path does, so on that disagreement the document is an
        // embedded node with no payload of its own on disk: it fails loudly and stays re-runnable instead
        // of being stamped "processed, source is the on-disk original" for a source getSource() can only
        // fail to find.
        assertThat(numberOfDocuments).isEqualTo(0);
        assertThat(logback.logs(Level.ERROR)).contains(
                "1 document(s) failed artifact production in project prj, re-run the ARTIFACT stage for them");
    }

    @Test(timeout = 10000)
    public void test_embedded_document_is_parsed_from_its_root_and_gets_its_own_manifest_entry() throws Exception {
        // The attachment is a two-page PDF with no text layer, so with OCR off both pages render blank. The
        // parser's output is served as it comes, so that is a complete two-page entry, not an absent one.
        indexEmbeddedPdfUnderItsRoot();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_PDF_SHA256 + "|" + EMBEDDED_DOC_SHA256);

        // the bare flag already selects raw and structure (see ArtifactRegistry#withDefaults).
        Long numberOfDocuments = runArtifactTask(Map.of("artifacts", "true"));

        assertThat(numberOfDocuments).isEqualTo(1);
        Path docArtifactDir = artifactDir.getRoot().toPath().resolve("prj/6a/bb/" + EMBEDDED_PDF_SHA256);
        assertThat(docArtifactDir.resolve("raw").toFile()).isFile();
        ManifestEntry structure = new FilesystemManifestRepository().get(docArtifactDir, "structure");
        assertThat(structure.status()).isEqualTo(ManifestEntryStatus.COMPLETE);
        assertThat(structure.pages().total()).isEqualTo(2);
        assertThat(docArtifactDir.resolve("structure").resolve("page-2.md").toFile()).isFile();
    }

    @Test(timeout = 10000)
    public void test_workers_run_concurrently() throws Exception {
        indexEmbeddedDoc();
        String secondId = "1111111111111111111111111111111111111111111111111111111111111111";
        mockIndexer.indexFile("prj", secondId,
                Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
                "message/rfc822");
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);
        queue.add(secondId);

        CountDownLatch bothInFlight = new CountDownLatch(2);
        Map<String, Object> config = Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "parallelism", "2");
        PropertiesProvider props = new PropertiesProvider(config);
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository), task, null) {
            @Override
            protected SourceExtractor createSourceExtractor() {
                return new SourceExtractor(props) {
                    @Override
                    public TikaDocument extractEmbeddedSources(Project project, Document document) {
                        bothInFlight.countDown();
                        try {
                            // both workers must arrive before either proceeds;
                            // with a single worker the second countDown never happens -> timeout
                            if (!bothInFlight.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("workers did not run concurrently");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    }
                };
            }
        };

        Long processed = artifactTask.call();
        assertThat(processed).isEqualTo(2);
    }

    @Test(timeout = 10000, expected = IllegalStateException.class)
    public void test_all_workers_dying_fails_the_task() throws Exception {
        indexEmbeddedDoc();
        String secondId = "1111111111111111111111111111111111111111111111111111111111111111";
        mockIndexer.indexFile("prj", secondId,
                Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
                "message/rfc822");
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);
        queue.add(secondId);

        Map<String, Object> config = Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "parallelism", "2");
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository), task, null) {
            @Override
            protected SourceExtractor createSourceExtractor() {
                throw new IllegalStateException("no extractor");
            }
        };

        artifactTask.call();
    }

    @Test(timeout = 10000, expected = IllegalStateException.class)
    public void test_a_single_worker_death_fails_the_task() throws Exception {
        indexEmbeddedDoc();
        String secondId = "1111111111111111111111111111111111111111111111111111111111111111";
        mockIndexer.indexFile("prj", secondId,
                Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
                "message/rfc822");
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);
        queue.add(secondId);

        Map<String, Object> config = Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "parallelism", "2");
        PropertiesProvider props = new PropertiesProvider(config);
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        // exactly one of the two workers fails to build its extractor and dies; the other survives.
        // the task must still fail, independently of the parallelism.
        AtomicInteger extractorCalls = new AtomicInteger(0);
        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository), task, null) {
            @Override
            protected SourceExtractor createSourceExtractor() {
                if (extractorCalls.getAndIncrement() == 0) {
                    throw new IllegalStateException("one worker has no extractor");
                }
                return new SourceExtractor(props) {
                    @Override
                    public TikaDocument extractEmbeddedSources(Project project, Document document) {
                        return null;
                    }
                };
            }
        };

        artifactTask.call();
    }

    @Test(timeout = 10000)
    public void test_skip_counted_under_parallelism() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add("unknownId");
        queue.add(EMBEDDED_DOC_SHA256);

        Long numberOfDocuments = runArtifactTask(2);

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(logback.logs(Level.WARN)).contains("document <unknownId> could not be retrieved from index prj (missing document or index fetch error), skipping");
        assertThat(logback.logs(Level.ERROR)).contains("1 document(s) could not be retrieved from index prj and got no artifact cache, re-run the ARTIFACT stage for them");
    }

    @Test(timeout = 10000)
    public void test_per_document_failure_is_non_fatal() throws Exception {
        indexEmbeddedDoc();
        String failingId = "2222222222222222222222222222222222222222222222222222222222222222";
        mockIndexer.indexFile("prj", failingId,
                Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
                "message/rfc822");
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(failingId);
        queue.add(EMBEDDED_DOC_SHA256);

        Map<String, Object> config = Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "parallelism", "2");
        PropertiesProvider props = new PropertiesProvider(config);
        ArtifactTask task = new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(config), null) {
            @Override
            protected SourceExtractor createSourceExtractor() {
                return new SourceExtractor(props) {
                    @Override
                    public TikaDocument extractEmbeddedSources(Project project, Document document) throws org.apache.tika.exception.TikaException {
                        if (document.getId().equals(failingId)) {
                            throw new org.apache.tika.exception.TikaException("boom");
                        }
                        return null;
                    }
                };
            }
        };

        Long numberOfDocuments = task.call();

        assertThat(numberOfDocuments).isEqualTo(1);
        // the producer isolates the per-artifact failure and keeps draining the queue,
        // so the run stays non-fatal and the sibling document is still counted.
        assertThat(logback.logs(Level.ERROR)).contains("failed to produce artifact 'raw' for document " + failingId);
        assertThat(logback.logs(Level.ERROR)).contains("1 document(s) failed artifact production in project prj, re-run the ARTIFACT stage for them");
    }

    @Test(timeout = 10000)
    public void test_an_error_fails_the_run_instead_of_draining_the_rest_of_the_queue() throws Exception {
        // An OutOfMemoryError leaves the worker on a heap it has already exhausted, so counting it as one
        // failed document and carrying on reports a finished run over documents that never had a chance.
        indexEmbeddedDoc();
        String secondId = "3333333333333333333333333333333333333333333333333333333333333333";
        mockIndexer.indexFile("prj", secondId,
                Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
                "message/rfc822");
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);
        queue.add(secondId);

        Map<String, Object> config = Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "parallelism", "1");
        PropertiesProvider props = new PropertiesProvider(config);
        ArtifactTask task = new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(config), null) {
            @Override
            protected SourceExtractor createSourceExtractor() {
                return new SourceExtractor(props) {
                    @Override
                    public TikaDocument extractEmbeddedSources(Project project, Document document) {
                        throw new OutOfMemoryError("Java heap space");
                    }
                };
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class, task::call);

        assertThat(failure.getMessage()).contains("terminated abnormally");
        // The worker's death is reported, not just signalled by the throw.
        assertThat(logback.logs(Level.ERROR)).contains("artifact worker terminated abnormally");
    }

    @Test(timeout = 10000)
    public void test_cancellation_throws_instead_of_returning_success() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        CountDownLatch started = new CountDownLatch(1);
        Map<String, Object> config = Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "parallelism", "1");
        PropertiesProvider props = new PropertiesProvider(config);
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository), task, null) {
            @Override
            protected SourceExtractor createSourceExtractor() {
                return new SourceExtractor(props) {
                    @Override
                    public TikaDocument extractEmbeddedSources(Project project, Document document) {
                        started.countDown();
                        try {
                            Thread.sleep(10_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    }
                };
            }
        };

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread callerThread = new Thread(() -> {
            try {
                artifactTask.call();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        callerThread.start();

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        callerThread.interrupt();
        callerThread.join(5000);

        assertThat(thrown.get()).isNotNull();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
    }

    @Test(timeout = 10000)
    public void test_cancel_stops_an_in_flight_run() throws Exception {
        indexEmbeddedDoc();
        String secondId = "1111111111111111111111111111111111111111111111111111111111111111";
        mockIndexer.indexFile("prj", secondId,
                Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
                "message/rfc822");
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);
        queue.add(secondId);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        Map<String, Object> config = Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "parallelism", "1");
        PropertiesProvider props = new PropertiesProvider(config);
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        // a single worker, so cancelling while the first document is in flight must stop the worker
        // before it ever polls the second entry off the queue
        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository), task, null) {
            @Override
            protected SourceExtractor createSourceExtractor() {
                return new SourceExtractor(props) {
                    @Override
                    public TikaDocument extractEmbeddedSources(Project project, Document document) {
                        if (document.getId().equals(secondId)) {
                            secondStarted.countDown();
                            return null;
                        }
                        started.countDown();
                        try {
                            Thread.sleep(10_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    }
                };
            }
        };

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread callerThread = new Thread(() -> {
            try {
                artifactTask.call();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        callerThread.start();

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        // cancel() must stop the worker pool and end the run, without interrupting this test thread
        artifactTask.cancel(false);
        callerThread.join(5000);

        assertThat(callerThread.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        assertThat(secondStarted.await(500, TimeUnit.MILLISECONDS)).isFalse();
    }

    // The two cases below merely look like a cancellation: Redisson and the Elasticsearch rest-client both
    // wrap an InterruptedException in a RuntimeException, and believing the cause chain ends the run green.

    @Test(timeout = 10000)
    public void test_a_queue_failure_wrapping_an_interrupt_fails_the_task_instead_of_ending_it_green() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = new MemoryDocumentQueue<>("extract:queue:artifact", DEFAULT_QUEUE_CAPACITY) {
            @Override
            public String poll() {
                throw new RuntimeException(new InterruptedException("wrapped by the redis client"));
            }
        };
        factory.queues.put("extract:queue:artifact", queue);

        // a broken queue is an infrastructure failure: the run must not be recorded as successful
        assertThrows(IllegalStateException.class, this::runArtifactTask);

        assertThat(logback.logs(Level.ERROR)).contains("artifact worker terminated abnormally");
    }

    @Test(timeout = 10000)
    public void test_a_document_failure_wrapping_an_interrupt_is_counted_and_the_drain_continues() throws Exception {
        indexEmbeddedDoc();
        String failingId = "3333333333333333333333333333333333333333333333333333333333333333";
        when(mockEs.get("prj", failingId, failingId, List.of("content", "content_translated")))
                .thenThrow(new RuntimeException(new InterruptedException("wrapped by the es client")));
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(failingId);
        queue.add(EMBEDDED_DOC_SHA256);

        Long numberOfDocuments = runArtifactTask();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(logback.logs(Level.ERROR)).contains("error in ArtifactTask loop");
        assertThat(logback.logs(Level.ERROR)).contains("1 document(s) failed artifact production in project prj, re-run the ARTIFACT stage for them");
    }

    @Test(timeout = 10000)
    public void test_default_is_single_threaded_and_still_drains() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        // no "parallelism" key -> ArtifactTask resolves .orElse(1)
        Long numberOfDocuments = new ArtifactTask(factory, mockEs,
                new UpstreamGate.Factory(taskRepository), ArtifactTaskFixture.taskWith(Map.of(
                        "artifactDir", artifactDir.getRoot().toString(),
                        "defaultProject", "prj")), null)
                .call();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw").toFile()).isFile();
    }

    @Test(timeout = 10000)
    public void test_two_sequential_runs_on_same_queue_both_process_docs() throws Exception {
        // the ARTIFACT input queue name is static (extract:queue:artifact) and is never deleted
        // between runs, so a re-run reuses it. Nothing is left behind by run 1 to terminate run 2's
        // workers early.
        indexEmbeddedDoc();
        String secondId = "1111111111111111111111111111111111111111111111111111111111111111";
        mockIndexer.indexFile("prj", secondId,
                Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI()),
                "message/rfc822");

        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        Long firstRun = runArtifactTask();
        assertThat(firstRun).isEqualTo(1);

        queue.add(secondId);

        Long secondRun = runArtifactTask();
        assertThat(secondRun).isEqualTo(1);
    }

    @Test(timeout = 10000)
    public void test_legacy_poison_entry_is_skipped_and_the_doc_behind_it_is_processed() throws Exception {
        // a sentinel written by a pre-21.16 run is skipped before it is resolved as a doc reference,
        // so it is not counted as an unretrievable document. The doc behind it must still be processed.
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add("POISON");
        queue.add(EMBEDDED_DOC_SHA256);

        Long numberOfDocuments = runArtifactTask();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(logback.logs(Level.WARN)).contains("skipping legacy POISON sentinel in queue extract:queue:artifact");
        // the sentinel must not be reported as a document the operator should re-run the stage for
        assertThat(logback.logs(Level.ERROR)).excludes("1 document(s) could not be retrieved from index prj and got no artifact cache, re-run the ARTIFACT stage for them");
    }

    @Test(timeout = 30000)
    public void test_waits_for_the_upstream_task_before_leaving_an_empty_queue() throws Exception {
        indexEmbeddedDoc();
        Task<Long> upstream = new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), Map.of());
        upstream.setState(Task.State.RUNNING);
        taskRepository.insert(upstream, null);
        CountDownLatch firstEmptyPoll = new CountDownLatch(1);
        DocumentQueue<String> queue = new MemoryDocumentQueue<>("extract:queue:artifact", DEFAULT_QUEUE_CAPACITY) {
            @Override
            public String poll() {
                String polled = super.poll();
                if (polled == null) {
                    firstEmptyPoll.countDown();
                }
                return polled;
            }
        };
        factory.queues.put("extract:queue:artifact", queue);
        // this task reads its options from the task args, so the upstream id set by the
        // launcher must live alongside the rest of the configuration in that same map
        Map<String, Object> config = new HashMap<>(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj"));
        config.put(UpstreamGate.UPSTREAM_TASK_ID, upstream.id);
        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs,
                new UpstreamGate.Factory(taskRepository), ArtifactTaskFixture.taskWith(config), null);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> produced = executor.submit((Callable<Long>) artifactTask::call);
            assertThat(firstEmptyPoll.await(20, TimeUnit.SECONDS)).isTrue();
            queue.add(EMBEDDED_DOC_SHA256);
            upstream.setResult(new TaskResult<>(0L));

            assertThat(produced.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private void indexEmbeddedDoc() throws URISyntaxException {
        indexEmbeddedDoc(EMBEDDED_DOC_SHA256);
    }

    private void indexEmbeddedDoc(String rootId) throws URISyntaxException {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI());
        mockIndexer.indexFile("prj", EMBEDDED_DOC_SHA256, path, "message/rfc822", rootId);
    }

    // The real embed of the .eml fixture, labelled consistently (unlike indexEmbeddedDoc): extraction
    // level 1 under the root the digest comes from, so raw and structure both resolve it.
    private void indexEmbeddedPdfUnderItsRoot() throws URISyntaxException {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI());
        Document root = DocumentBuilder.createDoc(EMBEDDED_DOC_SHA256).with(path)
                .with(Project.project("prj")).ofContentType("message/rfc822").build();
        Document embedded = DocumentBuilder.createDoc(EMBEDDED_PDF_SHA256).with(path)
                .with(Project.project("prj")).ofContentType("application/pdf")
                .withParentId(EMBEDDED_DOC_SHA256).withRootId(EMBEDDED_DOC_SHA256)
                .withExtractionLevel((short) 1).withContentLength(10).build();
        mockIndexer.indexFile("prj", root, embedded);
    }

    private Long runArtifactTask() throws Exception {
        return runArtifactTask(Map.of());
    }

    private Long runArtifactTask(Map<String, Object> extraArgs) throws Exception {
        Map<String, Object> args = new HashMap<>(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj"));
        args.putAll(extraArgs);
        return new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(args), null)
                .call();
    }

    private Long runArtifactTask(int parallelism) throws Exception {
        return runArtifactTask(Map.of("parallelism", String.valueOf(parallelism)));
    }

    @Test(timeout = 10000)
    public void test_root_caches_embedded_raw_and_writes_empty_root_manifest() throws Exception {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath());
        String rootSha = "0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e";
        mockIndexer.indexFile("prj", rootSha, path, "message/rfc822");

        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(rootSha);

        Long numberOfDocuments = new ArtifactTask(factory, mockEs,
                new UpstreamGate.Factory(taskRepository), ArtifactTaskFixture.taskWith(Map.of(
                        "artifactDir", artifactDir.getRoot().toString(),
                        "defaultProject", "prj")), null)
                .call();

        assertThat(numberOfDocuments).isEqualTo(1);

        // raw bytes for the embedded child are still produced (behavior preserved)
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw").toFile()).isFile();

        // G10: a root document now records an EMPTY raw entry (source is the on-disk original, no
        // payload copied here) so it is not reprocessed on the next run - the manifest IS written.
        Path rootManifest = artifactDir.getRoot().toPath().resolve("prj/0f/95/0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e/manifest.json");
        assertThat(rootManifest.toFile()).isFile();
        assertThat(new String(java.nio.file.Files.readAllBytes(rootManifest))).contains("\"status\" : \"empty\"");
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mockIndexer = new MockIndexer(mockEs);
    }
}
