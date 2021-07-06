package org.icij.datashare.batch;

import org.junit.Test;

import java.nio.file.Paths;

import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;

public class BatchDownloadTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_project() {
        new BatchDownload(null, Paths.get("test"), local(), "query");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_path() {
        new BatchDownload(project("prj"), null, local(), "query");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_user() {
        new BatchDownload(project("prj"), Paths.get("test"), null, "query");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_query() {
        new BatchDownload(project("prj"), Paths.get("test"), local(), null);
    }
}