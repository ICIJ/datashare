package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.zip.ZipFile;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;

public class BatchDownloadRunnerIntTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");
    @Rule public TemporaryFolder fs = new TemporaryFolder();
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);

    @Test
    public void test_empty_response() throws Exception {
        assertThat(new BatchDownloadRunner(indexer, createProvider(), createBatchDownload("archive.zip", "query")).call()).isEqualTo(0);
        assertThat(archiveFile("archive.zip")).doesNotExist();
    }

    @Test
    public void test_one_result() throws Exception {
        File file = fs.newFile("mydoc.txt");
        String content = "The quick brown fox jumps over the lazy dog";
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        Document mydoc = createDoc("mydoc").with(content).with(file.toPath()).build();
        indexer.add(TEST_INDEX, mydoc);

        assertThat(new BatchDownloadRunner(indexer, createProvider(), createBatchDownload("archive.zip", "fox")).call()).isEqualTo(1);

        assertThat(archiveFile("archive.zip")).isFile();
        assertThat(new ZipFile(archiveFile("archive.zip")).entries().hasMoreElements()).isTrue();
        assertThat(new ZipFile(archiveFile("archive.zip")).getEntry("mydoc.txt")).isNotNull();
        assertThat(new ZipFile(archiveFile("archive.zip")).getEntry("mydoc.txt").getSize()).isEqualTo(content.length());
    }

    @NotNull
    private BatchDownload createBatchDownload(String filename, String query) {
        return new BatchDownload(project(TEST_INDEX), fs.getRoot().toPath().resolve(filename), local(), query);
    }

    @NotNull
    private File archiveFile(String archive) {
        return fs.getRoot().toPath().resolve(archive).toFile();
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
