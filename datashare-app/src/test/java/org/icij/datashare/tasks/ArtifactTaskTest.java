package org.icij.datashare.tasks;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
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
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DEFAULT_QUEUE_CAPACITY;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.MemoryDocumentQueue;
import static org.junit.Assert.assertThrows;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;
import org.slf4j.event.Level;

public class ArtifactTaskTest {
    private static final String EMBEDDED_DOC_SHA256 = "0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e";
    private static final String EMBEDDED_PDF_SHA256 = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    @Rule public LogbackCapturingRule logback = new LogbackCapturingRule();
    StageRegistry stageRegistry = new StageRegistry();
    @Mock Indexer mockEs;
    MockIndexer mockIndexer;
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();

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
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        ArtifactTask artifactTask = artifactTask(task, options -> {
                return new SourceExtractor(new PropertiesProvider(options)) {
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
        });

        Long processed = artifactTask.call();
        assertThat(processed).isEqualTo(2);
    }

    @Test(timeout = 10000, expected = WorkersFailed.class)
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

        ArtifactTask artifactTask = artifactTask(task, options -> {
                throw new IllegalStateException("no extractor");
        });

        artifactTask.call();
    }

    @Test(timeout = 10000, expected = WorkersFailed.class)
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
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        // exactly one of the two workers fails to build its extractor and dies; the other survives.
        // the task must still fail, independently of the parallelism.
        AtomicInteger extractorCalls = new AtomicInteger(0);
        ArtifactTask artifactTask = artifactTask(task, options -> {
                if (extractorCalls.getAndIncrement() == 0) {
                    throw new IllegalStateException("one worker has no extractor");
                }
                return new SourceExtractor(new PropertiesProvider(options)) {
                    @Override
                    public TikaDocument extractEmbeddedSources(Project project, Document document) {
                        return null;
                    }
                };
        });

        artifactTask.call();
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
        ArtifactTask task = artifactTask(ArtifactTaskFixture.taskWith(config), options -> new SourceExtractor(new PropertiesProvider(options)) {
            @Override
            public TikaDocument extractEmbeddedSources(Project project, Document document) {
                throw new OutOfMemoryError("Java heap space");
            }
        });

        WorkersFailed failure = assertThrows(WorkersFailed.class, task::call);

        assertThat(failure.getMessage()).contains("terminated abnormally");
        // The worker's death is reported, not just signalled by the throw.
        assertThat(logback.logs(Level.ERROR)).contains("ARTIFACT worker terminated abnormally");
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
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        ArtifactTask artifactTask = artifactTask(task, options -> {
                return new SourceExtractor(new PropertiesProvider(options)) {
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
        });

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
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        // a single worker, so cancelling while the first document is in flight must stop the worker
        // before it ever polls the second entry off the queue
        ArtifactTask artifactTask = artifactTask(task, options -> {
                return new SourceExtractor(new PropertiesProvider(options)) {
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
        });

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
        assertThrows(WorkersFailed.class, this::runArtifactTask);

        assertThat(logback.logs(Level.ERROR)).contains("ARTIFACT worker terminated abnormally");
    }

    @Test(timeout = 10000)
    public void test_default_is_single_threaded_and_still_drains() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        // no "parallelism" key -> ArtifactTask resolves .orElse(1)
        Long numberOfDocuments = new ArtifactTask(factory, stageRegistry,
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
        ArtifactTask artifactTask = new ArtifactTask(factory, stageRegistry,
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

    private ArtifactTask artifactTask(Task<Long> task, Function<Map<String, Object>, SourceExtractor> extractorFactory) {
        StageRegistry registry = new StageRegistry().register(new ArtifactStage(mockEs, extractorFactory));
        return new ArtifactTask(factory, registry, new UpstreamGate.Factory(taskRepository), task, null);
    }

    private Long runArtifactTask() throws Exception {
        return runArtifactTask(Map.of());
    }

    private Long runArtifactTask(Map<String, Object> extraArgs) throws Exception {
        Map<String, Object> args = new HashMap<>(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj"));
        args.putAll(extraArgs);
        return new ArtifactTask(factory, stageRegistry, new UpstreamGate.Factory(taskRepository),
                                ArtifactTaskFixture.taskWith(args), null)
                .call();
    }

    private Long runArtifactTask(int parallelism) throws Exception {
        return runArtifactTask(Map.of("parallelism", String.valueOf(parallelism)));
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mockIndexer = new MockIndexer(mockEs);
        stageRegistry.register(new ArtifactStage(mockEs));
    }
}
