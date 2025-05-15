package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskModifier;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEXES;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadRunnerIntTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule(TEST_INDEXES);
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");
    @Rule public TemporaryFolder fs = new TemporaryFolder();

    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    @Mock TaskModifier taskModifier;

    @Test
    public void test_empty_response() throws Exception {
        BatchDownload bd = createBatchDownload("query");
        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call();
        assertThat(bd.filename.toFile()).doesNotExist();
        verify(taskModifier, never()).progress(any(), anyDouble());
    }

    @Test
    public void test_one_result() throws Exception {
        String content = "The quick brown fox jumps over the lazy dog";
        File file = new IndexerHelper(es.client).indexFile("mydoc.txt", content, fs);
        BatchDownload bd = createBatchDownload("fox");

        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(1);
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(file.toString().substring(1))).isNotNull();
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(file.toString().substring(1)).getSize()).isEqualTo(content.length());
        verify(taskModifier).progress(any(), anyDouble());
    }

    @Test
    public void test_one_result_with_json_query() throws Exception {
        String content = "The quick brown fox jumps over the lazy dog";
        new IndexerHelper(es.client).indexFile("mydoc.txt", content, fs);
        BatchDownload bd = createBatchDownload("{\"match_all\":{}}");

        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(1);
    }

    @Test
    public void test_two_results() throws Exception {
        new IndexerHelper(es.client).indexFile("doc1.txt", "The quick brown fox jumps over the lazy dog", fs);
        new IndexerHelper(es.client).indexFile("doc2.txt", "Portez ce vieux whisky au juge blond qui fume", fs);

        BatchDownload bd = createBatchDownload("*");
        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(2);
        verify(taskModifier, times(2)).progress(any(), anyDouble());
    }

    @Test
    public void test_update_batch_download_zip_size() throws Exception {
        new IndexerHelper(es.client).indexFile("doc1.txt", "The quick brown fox jumps over the lazy dog", fs);
        new IndexerHelper(es.client).indexFile("doc2.txt", "Portez ce vieux whisky au juge blond qui fume", fs);

        BatchDownload bd = createBatchDownload("*");
        Task taskView = createTaskView(bd);
        UriResult result = new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call().value();

        assertThat(result.size()).isGreaterThan(0);
    }

    @Test
    public void test_two_files_one_result() throws Exception {
        new IndexerHelper(es.client).indexFile("doc1.txt", "The quick brown fox jumps over the lazy dog", fs);
        new IndexerHelper(es.client).indexFile("doc2.txt", "Portez ce vieux whisky au juge blond qui fume", fs);

        BatchDownload bd = createBatchDownload("juge");
        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(1);
    }

    @Test
    public void test_two_results_two_dirs() throws Exception {
        File doc1 = new IndexerHelper(es.client).indexFile("dir1/doc1.txt", "The quick brown fox jumps over the lazy dog", fs);
        File doc2 = new IndexerHelper(es.client).indexFile("dir2/doc2.txt", "Portez ce vieux whisky au juge blond qui fume", fs);

        BatchDownload bd = createBatchDownload("*");
        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(2);
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(doc1.toString().substring(1))).isNotNull();
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(doc2.toString().substring(1))).isNotNull();
    }

    @Test
    public void test_two_results_two_indexes() throws Exception {
        File doc1 = new IndexerHelper(es.client).indexFile("dir1/doc1.txt", "The quick brown fox jumps over the lazy dog", fs, TEST_INDEXES[1]);
        File doc2 = new IndexerHelper(es.client).indexFile("dir2/doc2.txt", "Portez ce vieux whisky au juge blond qui fume", fs, TEST_INDEXES[2]);

        BatchDownload bd = createBatchDownload(asList(project(TEST_INDEXES[1]), project(TEST_INDEXES[2])),"*");
        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(2);
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(doc1.toString().substring(1))).isNotNull();
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(doc2.toString().substring(1))).isNotNull();
    }

    @Test
    public void test_progress_rate() throws Exception {
        new IndexerHelper(es.client).indexFile("mydoc.txt", "content", fs);
        Task taskView = createTaskView(createBatchDownload("*"));
        BatchDownloadRunner batchDownloadRunner = new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress));
        assertThat(batchDownloadRunner.getProgressRate()).isEqualTo(0);
        batchDownloadRunner.call();
        assertThat(batchDownloadRunner.getProgressRate()).isEqualTo(1);
    }

    @Test
    public void test_embedded_doc_should_not_interrupt_zip_creation() throws Exception {
        File file = new IndexerHelper(es.client).indexEmbeddedFile(TEST_INDEX, "/docs/embedded_doc.eml");

        BatchDownload bd = createBatchDownload("*");
        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call();

        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(2);
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(file.toString().substring(1))).isNotNull();
    }

    @Test
    public void test_embedded_doc_with_not_found_embedded_should_not_interrupt_zip_creation() throws Exception {
        new IndexerHelper(es.client).indexEmbeddedFile("bad_project_name", "/docs/embedded_doc.eml");

        BatchDownload bd = createBatchDownload("*");
        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress)).call();

        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(1);
    }

    @Test
    public void test_to_string_contains_batch_download_uuid() {
        BatchDownload bd = createBatchDownload("*");
        Task taskView = createTaskView(bd);
        BatchDownloadRunner batchDownloadRunner = new BatchDownloadRunner(indexer, createProvider(), taskView, taskView.progress(taskModifier::progress));

        assertThat(batchDownloadRunner.toString()).startsWith("BatchDownloadRunner@");
        assertThat(batchDownloadRunner.toString()).contains(bd.uuid);
    }

    @Test
    public void test_cancel_current_batch_download() throws Exception {
        new IndexerHelper(es.client).indexFile("mydoc.txt", "content", fs);
        ExecutorService executor = Executors.newFixedThreadPool(1);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Task taskView = createTaskView(createBatchDownload("*"));
        BatchDownloadRunner batchDownloadRunner = new BatchDownloadRunner(indexer, createProvider(), taskView.progress(taskModifier::progress), taskView, null, countDownLatch);

        Future<DatashareTaskResult<UriResult>> result = executor.submit(batchDownloadRunner);
        executor.shutdown();
        countDownLatch.await();
        batchDownloadRunner.cancel(false);

        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(assertThrows(ExecutionException.class, result::get).getCause()).isInstanceOf(CancelException.class);
    }

    @Test(expected = ElasticSearchAdapterException.class)
    public void test_use_batch_download_scroll_size_value_over_scroll_size_value() throws Exception {
        BatchDownload bd = createBatchDownload("*");
        Task taskView = createTaskView(bd);
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("downloadFolder", fs.getRoot().toString());
            put(SCROLL_SIZE_OPT, "100");
            put(BATCH_DOWNLOAD_SCROLL_SIZE_OPT, "0");
        }});
        new BatchDownloadRunner(indexer, propertiesProvider, taskView, taskView.progress(taskModifier::progress)).call();
    }

    @Test(expected = ElasticSearchAdapterException.class)
    public void test_use_scroll_size_value() throws Exception {
        BatchDownload bd = createBatchDownload("*");
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("downloadFolder", fs.getRoot().toString());
            put(SCROLL_SIZE_OPT, "0");
        }});
        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, propertiesProvider, taskView, taskView.progress(taskModifier::progress)).call();
    }

    @Test(expected = ElasticSearchAdapterException.class)
    public void test_elasticsearch_exception() throws Exception {
        BatchDownload bd = createBatchDownload("*");
        PropertiesProvider propertiesProvider = new PropertiesProvider(
                Map.of("downloadFolder", "/unused",
                BATCH_DOWNLOAD_SCROLL_DURATION_OPT, "10foo"));
        Task taskView = createTaskView(bd);
        new BatchDownloadRunner(indexer, propertiesProvider, taskView, taskView.progress(taskModifier::progress)).call();
    }


    private BatchDownload createBatchDownload(String query) {
        return new BatchDownload(asList(project(TEST_INDEX)), local(), query, null, fs.getRoot().toPath(), false);
    }
    private Task createTaskView(BatchDownload bd) {
        return new Task(BatchDownloadRunner.class.getName(), bd.user, new HashMap<>() {{
            put("batchDownload", bd);
        }});
    }

    @NotNull
    private BatchDownload createBatchDownload(List<Project> projectList, String query) {
        return new BatchDownload(projectList, local(), query, null,fs.getRoot().toPath(), false);
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
    }

    @After
    public void tearDown() throws IOException {
        es.removeAll();
    }

    @NotNull
    private PropertiesProvider createProvider() {
        return new PropertiesProvider(new HashMap<>() {{
            put("downloadFolder", fs.getRoot().toString());
        }});
    }
}
