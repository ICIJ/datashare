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

        bind(TaskManager.class).toInstance(new TaskManagerRedis(propertiesProvider));
        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        bind(ApiKeyRepository.class).toInstance(repositoryFactory.createApiKeyRepository());
        repositoryFactory.initDatabase();
    }
}
