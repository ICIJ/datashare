package org.icij.datashare;

import org.icij.datashare.tasks.BatchDownloadCleaner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
        ScheduledExecutorService scheduler = BatchDownloadApp.scheduleCleanup(callCount::incrementAndGet, 60);
        scheduler.shutdown();
        scheduler.awaitTermination(500, TimeUnit.MILLISECONDS);
        assertThat(callCount.get()).isGreaterThan(0);
    }

    @Test
    public void test_tick_period_is_half_ttl_in_seconds() {
        BatchDownloadCleaner cleaner1h = new BatchDownloadCleaner(new PropertiesProvider(new HashMap<>() {{
            put(BATCH_DOWNLOAD_ZIP_TTL_OPT, "1");
        }}));
        assertThat(cleaner1h.tickPeriodSeconds()).isEqualTo(30 * 60L);

        BatchDownloadCleaner cleaner2h = new BatchDownloadCleaner(new PropertiesProvider(new HashMap<>() {{
            put(BATCH_DOWNLOAD_ZIP_TTL_OPT, "2");
        }}));
        assertThat(cleaner2h.tickPeriodSeconds()).isEqualTo(60 * 60L);
    }

    @Test
    public void test_cleaner_is_not_scheduled_when_ttl_is_zero() throws Exception {
        File zip = downloadDir.newFile("archive_local_0000-00-00T00_00_00Z[GMT].zip");
        zip.setLastModified(0);

        BatchDownloadCleaner cleaner = new BatchDownloadCleaner(new PropertiesProvider(new HashMap<>() {{
            put(BATCH_DOWNLOAD_DIR_OPT, downloadDir.getRoot().toPath().toString());
            put(BATCH_DOWNLOAD_ZIP_TTL_OPT, "0");
        }}));
        ScheduledExecutorService scheduler = BatchDownloadApp.scheduleCleanup(cleaner);
        scheduler.awaitTermination(200, TimeUnit.MILLISECONDS);

        assertThat(zip).exists();
    }

    @Test
    public void test_cleaner_deletes_expired_zips_when_scheduled() throws Exception {
        File expiredZip = downloadDir.newFile("archive_local_0000-00-00T00_00_00Z[GMT].zip");
        expiredZip.setLastModified(0);

        BatchDownloadCleaner cleaner = new BatchDownloadCleaner(new PropertiesProvider(new HashMap<>() {{
            put(BATCH_DOWNLOAD_DIR_OPT, downloadDir.getRoot().toPath().toString());
            put(BATCH_DOWNLOAD_ZIP_TTL_OPT, "1");
        }}));
        ScheduledExecutorService scheduler = BatchDownloadApp.scheduleCleanup(cleaner);
        scheduler.shutdown();
        scheduler.awaitTermination(500, TimeUnit.MILLISECONDS);

        assertThat(expiredZip).doesNotExist();
    }
}
