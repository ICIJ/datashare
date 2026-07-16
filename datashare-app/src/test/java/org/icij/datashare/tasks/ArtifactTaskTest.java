package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.slf4j.event.Level;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class ArtifactTaskTest {
    private static final String EMBEDDED_DOC_SHA256 = "0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e";
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    @Rule public LogbackCapturingRule logback = new LogbackCapturingRule();
    @Mock Indexer mockEs;
    MockIndexer mockIndexer;
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();

    @Test(expected = IllegalArgumentException.class)
    public void test_missing_artifact_dir() {
        new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of()), new Task<>(ArtifactTask.class.getName(), User.local(), Map.of()), null);
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

        Long numberOfDocuments = runArtifactTask();

        verify(mockEs).get("prj", EMBEDDED_DOC_SHA256, "rootId", List.of("content", "content_translated"));
        assertThat(numberOfDocuments).isEqualTo(1);
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
        PropertiesProvider props = new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1",
                "parallelism", "2"));
        Task<Long> task = new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>());

        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, props, task, null) {
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

        PropertiesProvider props = new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1",
                "parallelism", "2"));
        Task<Long> task = new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>());

        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, props, task, null) {
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

        PropertiesProvider props = new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1",
                "parallelism", "2"));
        Task<Long> task = new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>());

        // exactly one of the two workers fails to build its extractor and dies; the other survives.
        // the task must still fail, independently of the parallelism.
        AtomicInteger extractorCalls = new AtomicInteger(0);
        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, props, task, null) {
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

        PropertiesProvider props = new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1",
                "parallelism", "2"));
        ArtifactTask task = new ArtifactTask(factory, mockEs, props,
                new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null) {
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
        assertThat(logback.logs(Level.ERROR)).contains("1 document(s) failed artifact production in project prj, re-run the ARTIFACT stage with --artifactsForce for them");
    }

    @Test(timeout = 10000)
    public void test_cancellation_throws_instead_of_returning_success() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        CountDownLatch started = new CountDownLatch(1);
        PropertiesProvider props = new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1",
                "parallelism", "1"));
        Task<Long> task = new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>());

        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, props, task, null) {
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
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        CountDownLatch started = new CountDownLatch(1);
        PropertiesProvider props = new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1",
                "parallelism", "1"));
        Task<Long> task = new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>());

        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, props, task, null) {
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
        // cancel() must stop the worker pool and end the run, without interrupting this test thread
        artifactTask.cancel(false);
        callerThread.join(5000);

        assertThat(callerThread.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
    }

    @Test(timeout = 10000)
    public void test_default_is_single_threaded_and_still_drains() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        // no "parallelism" key -> ArtifactTask resolves .orElse(1)
        Long numberOfDocuments = new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1")),
                new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null)
                .call();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw").toFile()).isFile();
    }

    @Test(timeout = 10000)
    public void test_terminates_on_poison_instead_of_polling_timeout() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);
        queue.add(PipelineTask.STRING_POISON);

        // pollingInterval is set far beyond the test timeout: if termination still depended on an
        // empty poll timing out, this call would block until JUnit kills the test. Only the
        // poison pill can make it return within the 10s budget.
        Long numberOfDocuments = new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "3600")),
                new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null)
                .call();

        assertThat(numberOfDocuments).isEqualTo(1);
    }

    @Test(timeout = 10000)
    public void test_poison_is_not_processed_as_a_document() throws Exception {
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(PipelineTask.STRING_POISON);

        Long numberOfDocuments = runArtifactTask();

        assertThat(numberOfDocuments).isEqualTo(0);
        assertThat(logback.logs(Level.WARN)).excludes("document <POISON> could not be retrieved from index prj (missing document or index fetch error), skipping");
        assertThat(logback.logs(Level.ERROR)).excludes("1 document(s) could not be retrieved from index prj and got no artifact cache, re-run the ARTIFACT stage for them");
    }

    @Test(timeout = 10000)
    public void test_poison_propagates_to_every_worker() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);
        // a single poison entry for two workers: whichever worker reads it must re-offer it so
        // the other worker (blocked on a 3600s poll, well past the test timeout) also sees it and
        // terminates, instead of being the only one released.
        queue.add(PipelineTask.STRING_POISON);

        PropertiesProvider props = new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "3600",
                "parallelism", "2"));
        Task<Long> task = new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>());

        Long numberOfDocuments = new ArtifactTask(factory, mockEs, props, task, null).call();

        assertThat(numberOfDocuments).isEqualTo(1);
    }

    private void indexEmbeddedDoc() throws URISyntaxException {
        indexEmbeddedDoc(EMBEDDED_DOC_SHA256);
    }

    private void indexEmbeddedDoc(String rootId) throws URISyntaxException {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI());
        mockIndexer.indexFile("prj", EMBEDDED_DOC_SHA256, path, "message/rfc822", rootId);
    }

    private Long runArtifactTask() throws Exception {
        return new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1"
                )),
                new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null)
                .call();
    }

    private Long runArtifactTask(int parallelism) throws Exception {
        return new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1",
                "parallelism", String.valueOf(parallelism))),
                new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null)
                .call();
    }

    @Test(timeout = 10000)
    public void test_root_caches_embedded_raw_and_writes_empty_root_manifest() throws Exception {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath());
        String rootSha = "0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e";
        mockIndexer.indexFile("prj", rootSha, path, "message/rfc822");

        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(rootSha);

        Long numberOfDocuments = new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1")),
                new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null)
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
