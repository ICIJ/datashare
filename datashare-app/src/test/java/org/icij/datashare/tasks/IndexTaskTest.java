package org.icij.datashare.tasks;

import org.apache.tika.parser.pdf.PDFParserConfig;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.test.LogbackAppenderWrapper;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.event.Level;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.function.BiFunction;
import java.time.Duration;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.PARSE_TIMEOUT_OPT;
import static org.icij.datashare.user.User.nullUser;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class IndexTaskTest {
    private final LogbackAppenderWrapper logWrapper = new LogbackAppenderWrapper();
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private final Indexer indexer = mock(Indexer.class);
    private final Extractor extractor = mock(Extractor.class);

    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();

    @Test
    public void test_options_include_ocr() throws Exception {
        IndexTask indexTask = indexTask(Map.of("queueName", "test:queue"));
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocr=");
    }

    @Test
    public void test_options_include_ocr_language() throws Exception {
        IndexTask indexTask = indexTask(Map.of("queueName", "test:queue"));
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocrLanguage=");
    }

    @Test
    public void test_options_include_ocr_retry_confidence() throws Exception {
        IndexTask indexTask = indexTask(Map.of("queueName", "test:queue"));
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocrRetryConfidence=");
    }

    @Test
    public void test_options_include_ocr_page_seg_mode() throws Exception {
        IndexTask indexTask = indexTask(Map.of("queueName", "test:queue"));
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocrPageSegMode=");
    }

    @Test
    public void test_options_include_progress_heartbeat_interval() throws Exception {
        IndexTask indexTask = indexTask(Map.of("queueName", "test:queue"));
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("progressHeartbeatInterval=");
    }

    @Test
    public void test_options_include_language() throws Exception {
        IndexTask indexTask = indexTask(Map.of("language", "FRENCH", "queueName", "test:queue"));

        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("language=");
    }
    
    @Test
    public void test_ocr_strategy_reaches_extractor() throws Exception {
        IndexTask indexTask = indexTask(Map.of("queueName", "test:queue"));

        Options<String> bound = indexTask.options().createFrom(Options.from(Map.of(
                "ocrStrategy", "AUTO", "queueName", "test:queue")));
        DocumentFactory documentFactory = new DocumentFactory().configure(bound);
        Extractor extractor = new Extractor(documentFactory, bound);

        assertThat(extractor.getOcrStrategy()).isEqualTo(PDFParserConfig.OCR_STRATEGY.AUTO);
        assertThat(extractor.isExtractInlineImages()).isFalse();
    }

    @Test
    public void test_max_embed_depth_reaches_extractor() throws Exception {
        IndexTask indexTask = indexTask(Map.of("queueName", "test:queue"));

        Options<String> bound = indexTask.options().createFrom(Options.from(Map.of(
                "maxEmbedDepth", "3", "queueName", "test:queue")));
        DocumentFactory documentFactory = new DocumentFactory().configure(bound);
        Extractor extractor = new Extractor(documentFactory, bound);

        assertThat(extractor.getMaxEmbedDepth()).isEqualTo(3);
    }

    @Test
    public void test_warns_when_parse_timeout_disabled() throws Exception {
        run(Map.of(PARSE_TIMEOUT_OPT, "0"));

        assertThat(logWrapper.logs(Level.WARN).stream()
                .anyMatch(l -> l.contains("parseTimeout") && l.contains("DISABLED"))).isTrue();
    }

    @Test
    public void test_does_not_warn_when_parse_timeout_enabled() throws Exception {
        run(Map.of(PARSE_TIMEOUT_OPT, "48h"));

        assertThat(logWrapper.logs(Level.WARN).stream()
                .anyMatch(l -> l.contains("parseTimeout"))).isFalse();
    }

    @Test
    public void test_artifacts_without_artifact_dir_does_not_throw_at_construction() {
        // Must not throw: --artifacts validation is deferred to call() so a bad config is a clean
        // task error, not a reflective-construction NackException that requeues forever.
        indexTask(Map.of("queueName", "test:queue", "artifacts", "true"));
    }

    @Test
    public void test_the_index_is_created_for_the_project_name_over_the_default_project() throws Exception {
        run(Map.of("defaultProject", "foo", "projectName", "bar"));

        verify(indexer).createIndex("bar");
    }

    @Test
    public void test_the_index_is_created_for_the_default_project_without_project_name() throws Exception {
        run(Map.of("defaultProject", "foo"));

        verify(indexer).createIndex("foo");
    }

    @Test
    public void test_the_embedded_payload_is_spooled_to_the_project_root_when_raw_artifacts_are_enabled() throws Exception {
        run(Map.of("artifacts", "raw", "artifactDir", artifactDir.getRoot().getPath(), "defaultProject", "foo"));

        verify(extractor).setEmbedOutputPath(ArtifactPath.projectRoot(artifactDir.getRoot().toPath(), "foo"));
    }

    @Test
    public void test_the_extractor_is_closed_when_the_run_ends() throws Exception {
        run(Map.of());

        verify(extractor).close();
    }

    @Test
    public void test_parse_timeout_value_reaches_extractor_options() throws Exception {
        AtomicReference<Options<String>> extractorOptions = new AtomicReference<>();

        run(Map.of(PARSE_TIMEOUT_OPT, "48h"), (documentFactory, options) -> {
            extractorOptions.set(options);
            return extractor;
        });

        assertThat(extractorOptions.get().ifPresent(PARSE_TIMEOUT_OPT, option -> option.parse().asDuration()))
                .isEqualTo(Optional.of(Duration.ofHours(48)));
    }

    /** The task, for what it declares: its queues are never read. */
    private IndexTask indexTask(Map<String, Object> args) {
        return new IndexTask(mock(DocumentCollectionFactory.class), mock(DocumentCollectionFactory.class),
                new StageRegistry().register(new IndexStage(indexer, text -> Language.ENGLISH)),
                new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), args), null);
    }

    private void run(Map<String, Object> args) throws Exception {
        run(args, (documentFactory, options) -> extractor);
    }

    /** Runs the task over one path: the extractor is the mock, so the path is never read. */
    private void run(Map<String, Object> args, BiFunction<DocumentFactory, Options<String>, Extractor> extractorFactory)
            throws Exception {
        Map<String, Object> taskArgs = new HashMap<>(args);
        taskArgs.put("queueName", "test:queue");
        MemoryDocumentCollectionFactory<Path> input = new MemoryDocumentCollectionFactory<>();
        input.createQueue(new PipelineHelper(new PropertiesProvider(taskArgs)).getQueueNameFor(Stage.INDEX), Path.class)
             .add(Paths.get("a-file.txt"));
        new IndexTask(input, new MemoryDocumentCollectionFactory<>(),
                new StageRegistry().register(new IndexStage(indexer, text -> Language.ENGLISH, extractorFactory)),
                new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), taskArgs),
                null).call();
    }

    @After
    public void tearDown() throws Exception {
        logWrapper.reset();
    }
}
