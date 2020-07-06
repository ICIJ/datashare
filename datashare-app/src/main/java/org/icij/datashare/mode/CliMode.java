package org.icij.datashare.mode;

import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.user.ApiKeyRepository;

import java.util.Properties;

public class CliMode extends CommonMode {
    CliMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();

        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        bind(ApiKeyRepository.class).toInstance(repositoryFactory.createApiKeyRepository());
        repositoryFactory.initDatabase();
    }
}
