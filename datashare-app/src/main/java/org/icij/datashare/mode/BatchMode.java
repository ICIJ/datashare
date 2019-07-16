package org.icij.datashare.mode;

import org.elasticsearch.client.RestHighLevelClient;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;

import java.util.Properties;

import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;

public class BatchMode extends CommonMode {
    BatchMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        RestHighLevelClient esClient = createESClient(propertiesProvider);
        bind(RestHighLevelClient.class).toInstance(esClient);
        bind(Indexer.class).to(ElasticsearchIndexer.class).asEagerSingleton();

        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        repositoryFactory.initDatabase();
    }
}
