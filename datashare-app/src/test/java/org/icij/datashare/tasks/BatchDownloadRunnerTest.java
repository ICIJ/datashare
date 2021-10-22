package org.icij.datashare.tasks;

import org.elasticsearch.ElasticsearchCorruptionException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.rest.RestStatus;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.zip.ZipFile;

import static java.lang.String.valueOf;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadRunnerTest {
    @Rule public TemporaryFolder fs = new TemporaryFolder();
    @Mock Function<TaskView<File>, Void> updater;
    @Mock Indexer indexer;
    MockSearch mockSearch;

    @Test
    public void test_max_default_results() throws Exception {
        Document[] documents = IntStream.range(0, 3).mapToObj(i -> createDoc("doc" + i).with(createFile(i)).build()).toArray(Document[]::new);
        mockSearch.willReturn(2, documents);
        File zip = new BatchDownloadRunner(indexer, new PropertiesProvider(new HashMap<String, String>() {{
            put(BATCH_DOWNLOAD_MAX_NB_FILES, "3");
            put(SCROLL_SIZE, "3");
        }}), new BatchDownload(project("test-datashare"), User.local(), "query"), updater).call();

        assertThat(new ZipFile(zip).size()).isEqualTo(3);
    }

    @Test
    public void test_max_zip_size() throws Exception {
        Document[] documents = IntStream.range(0, 3).mapToObj(i -> createDoc("doc" + i).with(createFile(i)).with("hello world " + i).build()).toArray(Document[]::new);
        mockSearch.willReturn(2, documents);
        File zip = new BatchDownloadRunner(indexer, new PropertiesProvider(new HashMap<String, String>() {{
            put(BATCH_DOWNLOAD_MAX_SIZE, valueOf("hello world 1".getBytes(StandardCharsets.UTF_8).length * 3));
            put(SCROLL_SIZE, "3");
        }}), new BatchDownload(project("test-datashare"), User.local(), "query"), updater).call();

        assertThat(new ZipFile(zip).size()).isEqualTo(3);
    }

    @Test(expected = ElasticsearchStatusException.class)
    public void test_elasticsearch_status_exception__should_be_sent() throws Exception {
        mockSearch.willThrow(new ElasticsearchStatusException("error", RestStatus.BAD_REQUEST, new RuntimeException()));
        new BatchDownloadRunner(indexer, new PropertiesProvider(), new BatchDownload(project("test-datashare"), User.local(), "query"), updater).call();
    }

    private Path createFile(int index) {
        File file;
        try {
            file = fs.newFile(String.format("src_file_%d.txt", index));
            Files.write(file.toPath(), ("hello world " + index).getBytes(StandardCharsets.UTF_8));
            return file.toPath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void setUp() { initMocks(this); mockSearch = new MockSearch(indexer);}
}
