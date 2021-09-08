package org.icij.datashare.tasks;

import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.extract.document.DigestIdentifier;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.icij.spewer.FieldNames;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static java.nio.file.Paths.get;
import static org.apache.commons.io.FilenameUtils.getName;
import static org.apache.commons.io.FilenameUtils.removeExtension;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadRunnerIntTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");
    @Rule public TemporaryFolder fs = new TemporaryFolder();
    @Mock Function<TaskView<File>, Void> updateCallback;
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);

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
        File file = indexFile("mydoc.txt", content);
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
        indexFile("mydoc.txt", content);
        BatchDownload bd = createBatchDownload("{\"match_all\":{}}");

        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(1);
    }

    @Test
    public void test_two_results() throws Exception {
        indexFile("doc1.txt", "The quick brown fox jumps over the lazy dog");
        indexFile("doc2.txt", "Portez ce vieux whisky au juge blond qui fume");

        BatchDownload bd = createBatchDownload("*");
        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(2);
        verify(updateCallback, times(2)).apply(any());
    }

    @Test
    public void test_two_files_one_result() throws Exception {
        indexFile("doc1.txt", "The quick brown fox jumps over the lazy dog");
        indexFile("doc2.txt", "Portez ce vieux whisky au juge blond qui fume");

        BatchDownload bd = createBatchDownload("juge");
        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(1);
    }

    @Test
    public void test_two_results_two_dirs() throws Exception {
        File doc1 = indexFile("dir1/doc1.txt", "The quick brown fox jumps over the lazy dog");
        File doc2 = indexFile("dir2/doc2.txt", "Portez ce vieux whisky au juge blond qui fume");

        BatchDownload bd = createBatchDownload("*");
        new BatchDownloadRunner(indexer, createProvider(), bd, updateCallback).call();

        assertThat(bd.filename.toFile()).isFile();
        assertThat(new ZipFile(bd.filename.toFile()).size()).isEqualTo(2);
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(doc1.toString().substring(1))).isNotNull();
        assertThat(new ZipFile(bd.filename.toFile()).getEntry(doc2.toString().substring(1))).isNotNull();
    }

    @Test
    public void test_progress_rate() throws Exception {
        indexFile("mydoc.txt", "content");
        BatchDownloadRunner batchDownloadRunner = new BatchDownloadRunner(indexer, createProvider(), createBatchDownload("*"), updateCallback);
        assertThat(batchDownloadRunner.getProgressRate()).isEqualTo(0);
        batchDownloadRunner.call();
        assertThat(batchDownloadRunner.getProgressRate()).isEqualTo(1);
    }

    @Test
    public void test_embedded_doc_should_not_interrupt_zip_creation() throws Exception {
        File file = indexEmbeddedFile(TEST_INDEX);

        BatchDownload batchDownload = createBatchDownload("*");
        new BatchDownloadRunner(indexer, createProvider(), batchDownload, updateCallback).call();

        assertThat(new ZipFile(batchDownload.filename.toFile()).size()).isEqualTo(1);
        assertThat(new ZipFile(batchDownload.filename.toFile()).getEntry(file.toString().substring(1))).isNotNull();
    }

    @Test
    public void test_embedded_doc_with_not_found_embedded_should_not_interrupt_zip_creation() throws Exception {
        indexEmbeddedFile("bad_project_name");

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

    private File indexEmbeddedFile(String project) throws IOException {
        Path path = get(getClass().getResource("/docs/embedded_doc.eml").getPath());
        Extractor extractor = new Extractor(new DocumentFactory().withIdentifier(new DigestIdentifier("SHA-384", Charset.defaultCharset())));
        extractor.setDigester(new UpdatableDigester(project, Entity.HASHER.toString()));
        TikaDocument document = extractor.extract(path);
        ElasticsearchSpewer elasticsearchSpewer = new ElasticsearchSpewer(es.client, l -> ENGLISH,
                new FieldNames(), mock(Publisher.class), new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex("test-datashare");
        elasticsearchSpewer.write(document);
        return path.toFile();
    }

    private File indexFile(String fileName, String content) throws IOException {
        String[] pathItems = fileName.split("/");
        File folder = pathItems.length > 1 ? fs.newFolder(Arrays.copyOf(pathItems, pathItems.length - 1)): fs.getRoot();
        File file = folder.toPath().resolve(pathItems[pathItems.length - 1]).toFile();
        file.createNewFile();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        String docname = removeExtension(getName(fileName));
        Document my_doc = createDoc(docname).with(content).with(file.toPath()).build();
        indexer.add(TEST_INDEX, my_doc);
        return file;
    }

    @NotNull
    private BatchDownload createBatchDownload(String query) {
        return new BatchDownload(project(TEST_INDEX), local(), query, fs.getRoot().toPath());
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
    }

    @After
    public void tearDown() throws IOException { es.removeAll();}

    @NotNull
    private PropertiesProvider createProvider() {
        return new PropertiesProvider(new HashMap<String, String>() {{
            put("downloadFolder", fs.getRoot().toString());
        }});
    }
}
