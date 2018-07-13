package org.icij.datashare.mode;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;
import org.elasticsearch.client.Client;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.SearchResource;
import org.icij.datashare.TaskFactory;
import org.icij.datashare.TaskResource;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.redis.RedisPublisher;
import org.icij.datashare.session.OAuth2CookieFilter;
import org.icij.datashare.session.RedisSessionIdStore;
import org.icij.datashare.session.RedisUsers;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;

import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createIndex;
import static org.icij.datashare.user.User.local;

public class ModeProduction extends AbstractMode {
    public ModeProduction(Properties properties) { super(properties);}

    public ModeProduction(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        PropertiesProvider propertiesProvider = properties == null ? new PropertiesProvider() : new PropertiesProvider().mergeWith(properties);
        bind(PropertiesProvider.class).toInstance(propertiesProvider);

        bind(Users.class).to(RedisUsers.class);
        bind(SessionIdStore.class).to(RedisSessionIdStore.class);
        bind(Filter.class).to(OAuth2CookieFilter.class).asEagerSingleton();

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
