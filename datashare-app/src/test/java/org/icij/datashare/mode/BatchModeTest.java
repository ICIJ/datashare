package org.icij.datashare.mode;

import com.google.inject.Injector;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.RedisBlockingQueue;
import org.icij.datashare.tasks.BatchDownloadLoop;
import org.icij.datashare.tasks.BatchSearchLoop;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.HashMap;

import static com.google.inject.Guice.createInjector;

public class BatchModeTest {
    @Rule public TemporaryFolder dataDir = new TemporaryFolder();

    @Test
    public void test_batch_search() throws IOException {
        Injector injector = createInjector(new BatchMode(PropertiesProvider.fromMap(new HashMap<>() {{
            put("dataDir", dataDir.getRoot().toString());
            put("mode", "BATCH_SEARCH");
            put("batchQueueType", RedisBlockingQueue.class.getName());
        }})));

        BatchSearchLoop batchSearchLoop = injector.getInstance(TaskFactory.class).createBatchSearchLoop();
        batchSearchLoop.enqueuePoison();
        batchSearchLoop.run();
        batchSearchLoop.close();
        injector.getInstance(Indexer.class).close();
    }

    @Test
    public void test_batch_download() throws IOException {
        Injector injector = createInjector(new BatchMode(PropertiesProvider.fromMap(new HashMap<>() {{
            put("dataDir", dataDir.getRoot().toString());
            put("mode", "BATCH_DOWNLOAD");
            put("batchQueueType", RedisBlockingQueue.class.getName());
        }})));

        BatchDownloadLoop batchDownloadLoop = injector.getInstance(TaskFactory.class).createBatchDownloadLoop();
        batchDownloadLoop.enqueuePoison();
        batchDownloadLoop.run();
        batchDownloadLoop.close();
    }
}
