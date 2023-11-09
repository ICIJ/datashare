package org.icij.datashare.mode;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.tasks.BatchDownloadLoop;
import org.icij.datashare.tasks.BatchSearchLoop;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.HashMap;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_BATCH_DOWNLOAD_DIR;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_BATCH_DOWNLOAD_ZIP_TTL;


public class CliModeTest {
    @Rule public TemporaryFolder dataDir = new TemporaryFolder();

    @Test
    public void test_batch_search() throws IOException {
        CommonMode mode = CommonMode.create(PropertiesProvider.fromMap(new HashMap<>() {{
            put("dataDir", dataDir.getRoot().toString());
            put("mode", "BATCH_SEARCH");
            put("batchQueueType", QueueType.REDIS.name());
        }}));

        BatchSearchLoop batchSearchLoop = mode.get(TaskFactory.class).createBatchSearchLoop();
        batchSearchLoop.enqueuePoison();
        batchSearchLoop.call();
        batchSearchLoop.close();
        mode.get(Indexer.class).close();
    }

    @Test
    public void test_batch_download() throws IOException {
        CommonMode mode = CommonMode.create(PropertiesProvider.fromMap(new HashMap<>() {{
            put("dataDir", dataDir.getRoot().toString());
            put("mode", "BATCH_DOWNLOAD");
            put("batchQueueType", QueueType.MEMORY.name());
            put(DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL, String.valueOf(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL));
            put(DatashareCliOptions.BATCH_DOWNLOAD_DIR, DEFAULT_BATCH_DOWNLOAD_DIR);
        }}));

        BatchDownloadLoop batchDownloadLoop = mode.get(TaskFactory.class).createBatchDownloadLoop();
        batchDownloadLoop.enqueuePoison();
        batchDownloadLoop.run();
        batchDownloadLoop.close();
    }
}
