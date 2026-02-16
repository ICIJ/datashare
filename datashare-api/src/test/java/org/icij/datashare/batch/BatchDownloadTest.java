package org.icij.datashare.batch;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;

public class BatchDownloadTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_user() {
        new BatchDownload(singletonList(project("prj")),null, "query");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_query() {
        new BatchDownload(singletonList(project("prj")), local(), null);
    }

    @Test
    public void test_batch_download_constructor() {
        DatashareTime.getInstance().setMockDate("2021-07-07T14:53:47Z");

        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), local(), "foo");

        assertThat(batchDownload.projects).isEqualTo(singletonList(project("prj")));
        assertThat(batchDownload.query).isEqualTo(new SearchQuery("foo"));
        assertThat(batchDownload.filename.toString()).isEqualTo(Paths.get(System.getProperty("java.io.tmpdir")).resolve("archive_local_2021-07-07T14_53_47Z[GMT].zip").toString());
        assertThat(batchDownload.getExists()).isFalse();
    }

    @Test(expected = IllegalStateException.class)
    public void test_constructor_with_json() {
        assertThat(new BatchDownload(asList(project("prj1"),project("prj2")), User.local(), "{test}").isJsonQuery()).isTrue();
    }

    @Test
    public void test_batch_download_constructor_with_downloadDir() {
        DatashareTime.getInstance().setMockDate("2021-07-07T14:15:16Z");

        assertThat(new BatchDownload(singletonList(project("prj")), local(), "foo", null,
                Paths.get("/bar"), false).filename.toString()).
                isEqualTo("/bar/archive_local_2021-07-07T14_15_16Z[GMT].zip");
    }

    @Test
    public void test_batch_download_constructor_with_uri() {
        DatashareTime.getInstance().setMockDate("2021-07-07T14:15:16Z");
        BatchDownload bd = new BatchDownload(singletonList(project("prj")), local(), "foo", "#/uri");
        assertThat(bd.uri).isEqualTo("#/uri");
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();
    @Test
    public void test_if_batch_download_archive_exists() throws Exception {
        File tempFile = folder.newFile("archive_local_2021-07-07T14_53_47Z[GMT].zip");
        assertThat(Files.exists(tempFile.toPath())).isTrue();
        Path tmpPath  = folder.getRoot().toPath();

        DatashareTime.getInstance().setMockDate("2021-07-07T14:53:47Z");
        BatchDownload bd = new BatchDownload(asList(project("prj1"),
                project("prj2")),  local(), "query", null ,tmpPath ,false);
        String json = JsonObjectMapper.writeValueAsString(bd);
        assertThat(json).contains("\"filename\":\"file://"+ tmpPath + "/archive_local_2021-07-07T14_53_47Z%5BGMT%5D.zip\"");
        assertThat(json).contains("\"exists\":true");
        assertThat(bd.getExists()).isTrue();

        if(tempFile.delete()){
            String jsonWithFileDeleted = JsonObjectMapper.writeValueAsString(bd);
            assertThat(jsonWithFileDeleted).contains("\"exists\":false");
            assertThat(bd.getExists()).isFalse();
        }
    }
    @Test
    public void test_is_json() {
        assertThat(new BatchDownload(singletonList(project("prj")), User.local(), "test")
                .isJsonQuery()).isFalse();
        assertThat(new BatchDownload(singletonList(project("prj")), User.local(), "{\"test\": 42}")
                .isJsonQuery()).isTrue();
    }

    @Test
    public void test_as_json() {
        String query = "{\"foo\":\"bar\"}";
        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), local(), query);
        assertThat(batchDownload.queryAsJson().toString()).isEqualTo(query);
    }

    @Test(expected = IllegalStateException.class)
    public void test_as_json_with_not_string_query() {
        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), local(), "query");
        batchDownload.queryAsJson();
    }

    @Test
    public void test_serialize_deserialize() throws Exception {
        DatashareTime.getInstance().setMockDate("2021-07-07T14:53:47Z");

        String json = JsonObjectMapper.writeValueAsString(new BatchDownload(asList(project("prj1"),
                project("prj2")), local(), "query"));
        assertThat(json).contains("\"filename\":\"file://" + Paths.get(System.getProperty("java.io.tmpdir")).resolve("archive_local_2021-07-07T14_53_47Z%5BGMT%5D.zip\""));
        assertThat(json).contains("\"exists\":false");

        BatchDownload batchDownload = JsonObjectMapper.readValue(json, BatchDownload.class);
        assertThat(batchDownload).isNotNull();
    }

    @Before public void setUp() { DatashareTime.setMockTime(true); }
    @After public void tearDown() { DatashareTime.setMockTime(false); }}