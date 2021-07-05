package org.icij.datashare.batch;

import org.junit.Test;

import java.nio.file.Paths;

import static org.icij.datashare.text.Project.project;

public class BatchDownloadTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_project() {
        new BatchDownload(null, Paths.get("test"), "query");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_path() {
        new BatchDownload(project("prj"), null, "query");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_query() {
        new BatchDownload(project("prj"), Paths.get("test"), null);
    }
}