package org.icij.datashare.tasks;

import org.fest.assertions.Assertions;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import static org.icij.datashare.batch.BatchDownload.createFilename;

public class BatchDownloadCleanerTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule();
    @Rule public TemporaryFolder downloadDir = new TemporaryFolder();

    @Test
    public void test_no_not_remove_zip_with_other_filename_patterns() throws IOException {
        downloadDir.newFile("test.zip");

        new BatchDownloadCleaner(new PropertiesProvider(new HashMap<>() {{
            put("batchDownloadDir", downloadDir.getRoot().toPath().toString());
            put("batchDownloadTimeToLive", "0");
        }})).run();
        Assertions.assertThat(downloadDir.getRoot().listFiles()).hasSize(1);
    }

    @Test
    public void test_remove_zip_file_with_correct_patterns() throws IOException {
        File file = downloadDir.newFile(createFilename(User.local()).toString());
        File fileWithDoubleDots = downloadDir.newFile("archive_local_0000-00-00T00:00:00Z[GMT].zip");

        // we must advance the delay between time fixed by the time rule and file creation date ~60ms
        DatashareTime.getInstance().addMilliseconds(100);

        new BatchDownloadCleaner(new PropertiesProvider(new HashMap<>() {{
            put("batchDownloadDir", downloadDir.getRoot().toPath().toString());
            put("batchDownloadTimeToLive", "0");
        }})).run();

        Assertions.assertThat(file).doesNotExist();
        Assertions.assertThat(fileWithDoubleDots).doesNotExist();
    }

    @Test
    public void test_remove_zip_file_with_correct_patterns_after_a_given_delay() throws IOException {
        File file = downloadDir.newFile(createFilename(User.local()).toString());
        BatchDownloadCleaner batchDownloadCleaner = new BatchDownloadCleaner(new PropertiesProvider(new HashMap<>() {{
            put("batchDownloadDir", downloadDir.getRoot().toPath().toString());
            put("batchDownloadTimeToLive", "1");
        }}));
        DatashareTime.getInstance().addMilliseconds(100); // same reason as previous test

        batchDownloadCleaner.run();
        Assertions.assertThat(file).exists();

        DatashareTime.getInstance().addMilliseconds(1000 * 60 * 60 + 1000);
        batchDownloadCleaner.run();
        Assertions.assertThat(file).doesNotExist();
    }
}