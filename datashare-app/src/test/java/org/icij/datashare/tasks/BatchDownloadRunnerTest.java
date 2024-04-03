package org.icij.datashare.tasks;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.rest.RestStatus;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.stream.IntStream;
import java.util.zip.ZipFile;

import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadRunnerTest {
    @Rule public TemporaryFolder fs = new TemporaryFolder();
    @Mock TaskModifier updater;
    @Mock Indexer indexer;
    MockSearch<Indexer.QueryBuilderSearcher> mockSearch;

    @Test(expected = AssertionError.class)
    public void test_task_with_no_batch_download() {
        new BatchDownloadRunner(indexer, new PropertiesProvider(), new TaskView<Serializable>("name", User.local(), new HashMap<>()), null);
    }

    @Test
    public void test_max_default_results() throws Exception {
        Document[] documents = IntStream.range(0, 3).mapToObj(i -> createDoc("doc" + i).with(createFile(i)).build()).toArray(Document[]::new);
        mockSearch.willReturn(2, documents);
        BatchDownload batchDownload = new BatchDownload(singletonList(project("test-datashare")), User.local(), "query");
        UriResult result = new BatchDownloadRunner(indexer, new PropertiesProvider(new HashMap<>() {{
                    put(BATCH_DOWNLOAD_MAX_NB_FILES_OPT, "3");
                    put(SCROLL_SIZE_OPT, "3");
                }}), getTaskView(batchDownload), updater::progress).call();

        assertThat(new ZipFile(new File(result.uri)).size()).isEqualTo(3);
    }

    @Test
    public void test_max_zip_size() throws Exception {
        Document[] documents = IntStream.range(0, 3).mapToObj(i -> createDoc("doc" + i).with(createFile(i)).with("hello world " + i).build()).toArray(Document[]::new);
        mockSearch.willReturn(2, documents);
        UriResult result = new BatchDownloadRunner(indexer, new PropertiesProvider(new HashMap<>() {{
            put(BATCH_DOWNLOAD_MAX_SIZE_OPT, valueOf("hello world 1".getBytes(StandardCharsets.UTF_8).length * 3));
            put(SCROLL_SIZE_OPT, "3");
        }}), getTaskView(new BatchDownload(singletonList(project("test-datashare")), User.local(), "query")), updater::progress).call();

        assertThat(new ZipFile(new File(result.uri)).size()).isEqualTo(4);
    }

    @Test(expected = ElasticsearchException.class)
    public void test_elasticsearch_status_exception__should_be_sent() throws Exception {
        mockSearch.willThrow(new ElasticsearchException("error", RestStatus.BAD_REQUEST, new RuntimeException()));
        new BatchDownloadRunner(indexer, new PropertiesProvider(), getTaskView(new BatchDownload(singletonList(project("test-datashare")), User.local(), "query")), updater::progress).call();
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

    @NotNull
    private static TaskView<File> getTaskView(BatchDownload batchDownload) {
        return new TaskView<>(BatchDownloadRunner.class.toString(), batchDownload.user, new HashMap<>() {{
            put("batchDownload", batchDownload);
        }});
    }

    @Before
    public void setUp() { initMocks(this); mockSearch = new MockSearch<>(indexer, Indexer.QueryBuilderSearcher.class);}
}
