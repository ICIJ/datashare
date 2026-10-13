package org.icij.datashare.tasks;

import org.apache.tika.parser.pdf.PDFParserConfig;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.LogbackAppenderWrapper;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewerFactory;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueue;
import org.icij.task.Option;
import org.icij.task.Options;
import org.icij.task.StringOptionParser;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.event.Level;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_OCR_MIN_CONFIDENCE;
import static org.icij.datashare.cli.DatashareCliOptions.PARSE_TIMEOUT_OPT;
import static org.icij.datashare.user.User.nullUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class IndexTaskTest {
    private final LogbackAppenderWrapper logWrapper = new LogbackAppenderWrapper();
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();

    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();

    @Test
    public void test_options_include_ocr() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        IndexTask indexTask = new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("queueName", "test:queue");
        }}), null);
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocr=");
    }

    @Test
    public void test_options_include_ocr_language() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        IndexTask indexTask = new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("queueName", "test:queue");
        }}), null);
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocrLanguage=");
    }

    @Test
    public void test_options_include_ocr_retry_confidence() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        IndexTask indexTask = new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("queueName", "test:queue");
        }}), null);
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocrRetryConfidence=");
    }

    @Test
    public void test_options_include_ocr_page_seg_mode() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        IndexTask indexTask = new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("queueName", "test:queue");
        }}), null);
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocrPageSegMode=");
    }

    @Test
    public void test_options_include_progress_heartbeat_interval() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        IndexTask indexTask = new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("queueName", "test:queue");
        }}), null);
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("progressHeartbeatInterval=");
    }

    @Test
    public void test_options_include_language() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        IndexTask indexTask = new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("language", "FRENCH");
            put("queueName", "test:queue");
        }}), null);

        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("language=");
    }
    
    @Test
    public void test_ocr_strategy_reaches_extractor() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        IndexTask indexTask = new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository),
                new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
                    put("queueName", "test:queue");
                }}), null);

        Options<String> bound = indexTask.options().createFrom(Options.from(Map.of(
                "ocrStrategy", "AUTO", "queueName", "test:queue")));
        DocumentFactory documentFactory = new DocumentFactory().configure(bound);
        Extractor extractor = new Extractor(documentFactory, bound);

        assertThat(extractor.getOcrStrategy()).isEqualTo(PDFParserConfig.OCR_STRATEGY.AUTO);
        assertThat(extractor.isExtractInlineImages()).isFalse();
    }

    @Test
    public void test_max_embed_depth_reaches_extractor() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        IndexTask indexTask = new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository),
                new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
                    put("queueName", "test:queue");
                }}), null);

        Options<String> bound = indexTask.options().createFrom(Options.from(Map.of(
                "maxEmbedDepth", "3", "queueName", "test:queue")));
        DocumentFactory documentFactory = new DocumentFactory().configure(bound);
        Extractor extractor = new Extractor(documentFactory, bound);

        assertThat(extractor.getMaxEmbedDepth()).isEqualTo(3);
    }

    @Test
    public void test_warns_when_parse_timeout_disabled() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);

        new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository),
                new Task<>(IndexTask.class.getName(), nullUser(),
                        Map.of(PARSE_TIMEOUT_OPT, "0", "queueName", "test:queue")), null);

        assertThat(logWrapper.logs(Level.WARN).stream()
                .anyMatch(l -> l.contains("parseTimeout") && l.contains("DISABLED"))).isTrue();
    }

    @Test
    public void test_does_not_warn_when_parse_timeout_enabled() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);

        new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository),
                new Task<>(IndexTask.class.getName(), nullUser(),
                        Map.of(PARSE_TIMEOUT_OPT, "48h", "queueName", "test:queue")), null);

        assertThat(logWrapper.logs(Level.WARN).stream()
                .anyMatch(l -> l.contains("parseTimeout"))).isFalse();
    }

    @Test
    public void test_artifacts_without_artifact_dir_does_not_throw_at_construction() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        // Must not throw: --artifacts validation is deferred to call() so a bad config is a clean
        // task error, not a reflective-construction NackException that requeues forever.
        new IndexTask(spewerFactory, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository),
                new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>() {{
                    put("queueName", "test:queue");
                    put("artifacts", "true");
                }}), null);
    }

    @Test
    public void test_the_spewer_is_built_for_the_project_name_over_the_default_project() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = runIndexTask(Map.of("defaultProject", "foo", "projectName", "bar"));

        verify(spewerFactory).create(eq("bar"), eq(DEFAULT_OCR_MIN_CONFIDENCE), isNull());
    }

    @Test
    public void test_the_spewer_is_built_for_the_default_project_without_project_name() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = runIndexTask(Map.of("defaultProject", "foo"));

        verify(spewerFactory).create(eq("foo"), eq(DEFAULT_OCR_MIN_CONFIDENCE), isNull());
    }

    @Test
    public void test_a_manifest_recorder_is_passed_to_the_spewer_when_artifacts_are_enabled() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = runIndexTask(Map.of("artifacts", "raw",
                "artifactDir", artifactDir.getRoot().getPath(), "defaultProject", "foo"));

        verify(spewerFactory).create(eq("foo"), eq(DEFAULT_OCR_MIN_CONFIDENCE), notNull());
    }

    @Test
    public void test_ocr_min_confidence_is_passed_to_the_spewer() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = runIndexTask(Map.of("ocrMinConfidence", "70"));

        verify(spewerFactory).create(any(), eq(70), isNull());
    }

    @Test
    public void test_the_index_is_created_by_the_spewer() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewerFactory.create(any(), anyInt(), any())).thenReturn(spewer);

        runIndexTask(spewerFactory, Map.of());

        verify(spewer).createIndexIfNotExists();
    }

    @Test
    public void test_parse_timeout_value_reaches_extractor_options() throws Exception {
        AtomicReference<Options<String>> extractorOptions = new AtomicReference<>();
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        Mockito.when(spewerFactory.create(any(), anyInt(), any())).thenReturn(mock(ElasticsearchSpewer.class));
        new IndexTask(spewerFactory, new MemoryDocumentCollectionFactory<>(), new UpstreamGate.Factory(taskRepository),
                new Task<>(IndexTask.class.getName(), nullUser(),
                        Map.of(PARSE_TIMEOUT_OPT, "48h", "queueName", "test:queue")), null) {
            @Override
            protected Extractor createExtractor(DocumentFactory documentFactory, Options<String> options) {
                extractorOptions.set(options);
                return super.createExtractor(documentFactory, options);
            }
        }.call();

        Option<String> option = new Option<>(PARSE_TIMEOUT_OPT, StringOptionParser::new).update("48h");
        assertThat(extractorOptions.get()).contains(option);
    }

    private ElasticsearchSpewerFactory runIndexTask(Map<String, Object> args) throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        Mockito.when(spewerFactory.create(any(), anyInt(), any())).thenReturn(mock(ElasticsearchSpewer.class));
        runIndexTask(spewerFactory, args);
        return spewerFactory;
    }

    private void runIndexTask(ElasticsearchSpewerFactory spewerFactory, Map<String, Object> args) throws Exception {
        Map<String, Object> taskArgs = new HashMap<>(args);
        taskArgs.put("queueName", "test:queue");
        new IndexTask(spewerFactory, new MemoryDocumentCollectionFactory<>(), new UpstreamGate.Factory(taskRepository),
                new Task<>(IndexTask.class.getName(), nullUser(), taskArgs), null).call();
    }

    @Test
    public void test_cancel_stops_draining_the_queue() throws Exception {
        ElasticsearchSpewerFactory spewerFactory = mock(ElasticsearchSpewerFactory.class);
        Mockito.when(spewerFactory.create(any(), anyInt(), any())).thenReturn(mock(ElasticsearchSpewer.class));
        MemoryDocumentCollectionFactory<Path> factory = new MemoryDocumentCollectionFactory<>();
        DocumentQueue<Path> queue = factory.createQueue("extract:queue:index", Path.class);
        Path doc = Paths.get(ClassLoader.getSystemResource("docs/doc.txt").getPath());
        for (int i = 0; i < 3; i++) {
            queue.add(doc);
        }
        AtomicReference<IndexTask> indexTask = new AtomicReference<>();
        AtomicInteger progressCalls = new AtomicInteger();
        CountDownLatch queueDrained = new CountDownLatch(1);
        indexTask.set(new IndexTask(spewerFactory, factory, new UpstreamGate.Factory(taskRepository),
                new Task<>(IndexTask.class.getName(), nullUser(), Map.of("parallelism", "1")), rate -> {
                    if (progressCalls.incrementAndGet() == 1) {
                        indexTask.get().cancel(false);
                    }
                    if (queue.isEmpty()) {
                        queueDrained.countDown();
                    }
                    return null;
                }));

        try {
            indexTask.get().call();
            fail("expected an InterruptedException");
        } catch (InterruptedException expected) {
            assertThat(queueDrained.await(2, SECONDS)).isFalse();
            assertThat(queue.size()).isGreaterThan(0);
        } finally {
            Thread.interrupted();
        }
    }

    @After
    public void tearDown() throws Exception {
        logWrapper.reset();
    }
}
