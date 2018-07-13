package org.icij.datashare.mode;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import org.elasticsearch.client.Client;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.SearchResource;
import org.icij.datashare.TaskFactory;
import org.icij.datashare.TaskResource;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.redis.RedisPublisher;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;

import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createIndex;
import static org.icij.datashare.user.User.local;

public class ModeLocal extends AbstractMode {
    public ModeLocal(Properties properties) { super(properties);}
    public ModeLocal(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        PropertiesProvider propertiesProvider = properties == null ? new PropertiesProvider() : new PropertiesProvider().mergeWith(properties);
        bind(PropertiesProvider.class).toInstance(propertiesProvider);

        bind(Filter.class).to(LocalUserFilter.class).asEagerSingleton();

        Client esClient = createESClient(propertiesProvider);
        createIndex(esClient, local().indexName());
        bind(Client.class).toInstance(esClient);
        bind(Indexer.class).to(ElasticsearchIndexer.class);

        install(new FactoryModuleBuilder().build(TaskFactory.class));
        bind(Publisher.class).to(RedisPublisher.class);
        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.add(TaskResource.class).add(SearchResource.class);
    }
}
