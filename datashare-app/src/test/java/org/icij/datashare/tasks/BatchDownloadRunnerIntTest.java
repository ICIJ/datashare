package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
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
import java.util.function.Function;
import java.util.zip.ZipFile;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEXES;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadRunnerIntTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule(TEST_INDEXES);
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");
    @Rule public TemporaryFolder fs = new TemporaryFolder();
    @Mock Function<TaskView<File>, Void> updateCallback;
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);

    @Test
    public void test_empty_response() throws Exception {
        BatchDownload bd = createBatchDownload("query");
        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();
        assertThat(bd.filename.toFile()).doesNotExist();
        verify(updateCallback, never()).apply(any());
    }

    @Test
    public void test_one_result() throws Exception {
        String content = "The quick brown fox jumps over the lazy dog";
        File file = new IndexerHelper(es.client).indexFile("mydoc.txt", content, fs);
        BatchDownload bd = createBatchDownload("fox");

        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(1);
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(file.toString().substring(1))).isNotNull();
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(file.toString().substring(1)).getSize()).isEqualTo(content.length());
        verify(updateCallback).apply(any());
    }

    @Test
    public void test_one_result_with_json_query() throws Exception {
        String content = "The quick brown fox jumps over the lazy dog";
        new IndexerHelper(es.client).indexFile("mydoc.txt", content, fs);
        BatchDownload bd = createBatchDownload("{\"match_all\":{}}");

        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(1);
    }

    @Test
    public void test_two_results() throws Exception {
        new IndexerHelper(es.client).indexFile("doc1.txt", "The quick brown fox jumps over the lazy dog", fs);
        new IndexerHelper(es.client).indexFile("doc2.txt", "Portez ce vieux whisky au juge blond qui fume", fs);

        BatchDownload bd = createBatchDownload("*");
        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(2);
        verify(updateCallback, times(2)).apply(any());
    }

    @Test
    public void test_update_batch_download_zip_size() throws Exception {
        new IndexerHelper(es.client).indexFile("doc1.txt", "The quick brown fox jumps over the lazy dog", fs);
        new IndexerHelper(es.client).indexFile("doc2.txt", "Portez ce vieux whisky au juge blond qui fume", fs);

        BatchDownload bd = createBatchDownload("*");
        long sizeAtCreation = bd.zipSize;
        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.zipSize).isGreaterThan(sizeAtCreation);
    }

    @Test
    public void test_two_files_one_result() throws Exception {
        new IndexerHelper(es.client).indexFile("doc1.txt", "The quick brown fox jumps over the lazy dog", fs);
        new IndexerHelper(es.client).indexFile("doc2.txt", "Portez ce vieux whisky au juge blond qui fume", fs);

        BatchDownload bd = createBatchDownload("juge");
        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(1);
    }

    @Test
    public void test_two_results_two_dirs() throws Exception {
        File doc1 = new IndexerHelper(es.client).indexFile("dir1/doc1.txt", "The quick brown fox jumps over the lazy dog", fs);
        File doc2 = new IndexerHelper(es.client).indexFile("dir2/doc2.txt", "Portez ce vieux whisky au juge blond qui fume", fs);

        BatchDownload bd = createBatchDownload("*");
        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

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
        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(2);
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(doc1.toString().substring(1))).isNotNull();
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(doc2.toString().substring(1))).isNotNull();
    }

    @Test
    public void test_progress_rate() throws Exception {
        new IndexerHelper(es.client).indexFile("mydoc.txt", "content", fs);
        BatchDownloadRunner batchDownloadRunner = new BatchDownloadRunner(indexer, createProvider(), createBatchDownload("*"), updateCallback);
        assertThat(batchDownloadRunner.getProgressRate()).isEqualTo(0);
        batchDownloadRunner.call();
        assertThat(batchDownloadRunner.getProgressRate()).isEqualTo(1);
    }

    @Test
    public void test_embedded_doc_should_not_interrupt_zip_creation() throws Exception {
        File file = new IndexerHelper(es.client).indexEmbeddedFile(TEST_INDEX, "/docs/embedded_doc.eml");

        BatchDownload batchDownload = createBatchDownload("*");
        new BatchDownloadRunner(indexer, createProvider(), batchDownload, updateCallback).call();

        assertThat(new ZipFile(batchDownload.filename.toFile()).size()).isEqualTo(2);
        assertThat(new ZipFile(batchDownload.filename.toFile()).getEntry(file.toString().substring(1))).isNotNull();
    }

    @Test
    public void test_embedded_doc_with_not_found_embedded_should_not_interrupt_zip_creation() throws Exception {
        new IndexerHelper(es.client).indexEmbeddedFile("bad_project_name", "/docs/embedded_doc.eml");

        BatchDownload batchDownload = createBatchDownload("*");
        new BatchDownloadRunner(indexer, createProvider(), batchDownload, updateCallback).call();

        assertThat(new ZipFile(batchDownload.filename.toFile()).size()).isEqualTo(1);
    }

    @Test
    public void test_to_string_contains_batch_download_uuid() {
        BatchDownload batchDownload = createBatchDownload("*");
        BatchDownloadRunner batchDownloadRunner = new BatchDownloadRunner(indexer, createProvider(), batchDownload, updateCallback);

        assertThat(batchDownloadRunner.toString()).startsWith("org.icij.datashare.tasks.BatchDownloadRunner@");
        assertThat(batchDownloadRunner.toString()).contains(batchDownload.uuid);
    }

    private BatchDownload createBatchDownload(String query) {
        return new BatchDownload(asList(project(TEST_INDEX)), local(), query, fs.getRoot().toPath(), false);
    }

    @NotNull
    private BatchDownload createBatchDownload(List<Project> projectList, String query) {
        return new BatchDownload(projectList, local(), query, fs.getRoot().toPath(), false);
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
        return new PropertiesProvider(new HashMap<String, String>() {{
            put("downloadFolder", fs.getRoot().toString());
        }});
    }
}
