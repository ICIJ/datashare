package org.icij.datashare.mode;

import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskManagerRedis;
import org.icij.datashare.user.ApiKeyRepository;

import java.util.Properties;

public class BatchDownloadMode extends CommonMode {
    BatchDownloadMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();

        String batchQueueType = propertiesProvider.get("batchQueueType").orElse("org.icij.datashare.extract.MemoryBlockingQueue");
        bind(TaskManager.class).toInstance(new TaskManagerRedis(propertiesProvider, getBlockingQueue(propertiesProvider, batchQueueType, "ds:batchdownload:queue")));
        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        bind(ApiKeyRepository.class).toInstance(repositoryFactory.createApiKeyRepository());
        repositoryFactory.initDatabase();
    }
}
