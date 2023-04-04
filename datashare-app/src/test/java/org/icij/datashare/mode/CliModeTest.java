package org.icij.datashare.mode;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.tasks.BatchDownloadLoop;
import org.icij.datashare.tasks.BatchSearchLoop;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.HashMap;


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
        batchSearchLoop.run();
        batchSearchLoop.close();
        mode.get(Indexer.class).close();
    }
    @Ignore
    @Test
    public void test_batch_download() throws IOException {
        CommonMode mode = CommonMode.create(PropertiesProvider.fromMap(new HashMap<>() {{
            put("dataDir", dataDir.getRoot().toString());
            put("mode", "BATCH_DOWNLOAD");
            put("batchQueueType", QueueType.MEMORY.name());
        }}));

        BatchDownloadLoop batchDownloadLoop = mode.get(TaskFactory.class).createBatchDownloadLoop();
        batchDownloadLoop.enqueuePoison();
        batchDownloadLoop.run();
        batchDownloadLoop.close();
    }
}
