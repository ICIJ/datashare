package org.icij.datashare.batch;

import com.google.inject.AbstractModule;
import org.elasticsearch.client.RestHighLevelClient;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;

import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;

public class AppInjector extends AbstractModule {
    @Override
    protected void configure() {
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        RestHighLevelClient esClient = createESClient(propertiesProvider);
        bind(RestHighLevelClient.class).toInstance(esClient);
        bind(Indexer.class).to(ElasticsearchIndexer.class).asEagerSingleton();

        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        bind(BatchSearchRepository.class).toInstance(repositoryFactory.createBatchSearchRepository());
        repositoryFactory.initDatabase();
    }
}
