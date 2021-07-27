package org.icij.datashare.tasks;

import org.fest.assertions.Assertions;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.icij.datashare.batch.BatchDownload.createFilename;
import static org.icij.datashare.text.Project.project;

public class BatchDownloadCleanerTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule();
    @Rule public TemporaryFolder downloadDir = new TemporaryFolder();

    @Test
    public void test_no_not_remove_zip_with_other_filename_patterns() throws IOException {
        downloadDir.newFile("test.zip");
        new BatchDownloadCleaner(downloadDir.getRoot().toPath(), 0).run();
        Assertions.assertThat(downloadDir.getRoot().listFiles()).hasSize(1);
    }

    @Test
    public void test_remove_zip_file_with_correct_patterns() throws IOException {
        File file = downloadDir.newFile(createFilename(project("prj"), User.local()).toString());
        // we must advance the delay between time fixed by the time rule and file creation date ~60ms
        DatashareTime.getInstance().addMilliseconds(100);

        new BatchDownloadCleaner(downloadDir.getRoot().toPath(), 0).run();

        Assertions.assertThat(file).doesNotExist();
    }

    @Test
    public void test_remove_zip_file_with_correct_patterns_after_a_given_delay() throws IOException {
        File file = downloadDir.newFile(createFilename(project("prj"), User.local()).toString());
        BatchDownloadCleaner batchDownloadCleaner = new BatchDownloadCleaner(downloadDir.getRoot().toPath(), 10);
        DatashareTime.getInstance().addMilliseconds(100); // same reason as previous test

        batchDownloadCleaner.run();
        Assertions.assertThat(file).exists();

        DatashareTime.getInstance().addMilliseconds(10_000);
        batchDownloadCleaner.run();
        Assertions.assertThat(file).doesNotExist();
    }
}