package org.icij.datashare.mode;

import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;

import java.util.Properties;

public class BatchMode extends CommonMode {
    BatchMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();

        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        repositoryFactory.initDatabase();
    }
}
