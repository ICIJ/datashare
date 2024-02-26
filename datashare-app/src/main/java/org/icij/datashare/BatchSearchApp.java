package org.icij.datashare;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchSearchLoop;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.text.indexing.Indexer;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.util.Properties;


public class BatchSearchApp {
    public static void start(Properties properties) throws Exception {
        CommonMode mode = CommonMode.create(properties);
        BatchSearchLoop batchSearchLoop = mode.get(TaskFactory.class).createBatchSearchLoop();
        requeueDatabaseBatches(mode.get(BatchSearchRepository.class), mode.get(TaskManager.class));
        batchSearchLoop.call();
        batchSearchLoop.close();
        mode.get(Indexer.class).close();// to avoid being blocked
        mode.get(RedissonClient.class).shutdown();
    }

    private static void requeueDatabaseBatches(BatchSearchRepository repository, TaskManager taskManager) throws IOException {
        for (String batchSearchUuid: repository.getQueued()) {
            BatchSearch batchSearch = repository.get(batchSearchUuid);
            taskManager.startTask(batchSearchUuid, BatchSearchRunner.class.getName(), batchSearch.user);
        }
    }
}
