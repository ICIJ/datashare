package org.icij.datashare;

import org.icij.datashare.tasks.BatchDownloadCleaner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_DOWNLOAD_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL_OPT;

public class BatchDownloadAppTest {
    @Rule
    public TemporaryFolder downloadDir = new TemporaryFolder();

    @Test
    public void test_cleaner_runs_immediately_when_scheduled() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        ScheduledExecutorService scheduler = BatchDownloadApp.scheduleCleanup(callCount::incrementAndGet);
        Thread.sleep(100);
        scheduler.shutdown();
        assertThat(callCount.get()).isGreaterThan(0);
    }

    @Test
    public void test_expired_batch_download_zip_is_deleted_by_scheduled_cleaner() throws Exception {
        File expiredZip = downloadDir.newFile("archive_local_0000-00-00T00_00_00Z[GMT].zip");
        expiredZip.setLastModified(0);

        BatchDownloadCleaner cleaner = new BatchDownloadCleaner(new PropertiesProvider(new HashMap<>() {{
            put(BATCH_DOWNLOAD_DIR_OPT, downloadDir.getRoot().toPath().toString());
            put(BATCH_DOWNLOAD_ZIP_TTL_OPT, "1");
        }}));
        ScheduledExecutorService scheduler = BatchDownloadApp.scheduleCleanup(cleaner);
        Thread.sleep(100);
        scheduler.shutdown();

        assertThat(expiredZip).doesNotExist();
    }
}
