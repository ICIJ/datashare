package org.icij.datashare.mode;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;
import org.elasticsearch.client.RestHighLevelClient;
import org.icij.datashare.*;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.redis.RedisPublisher;
import org.icij.datashare.session.OAuth2CookieFilter;
import org.icij.datashare.session.RedisSessionIdStore;
import org.icij.datashare.session.RedisUsers;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;

import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createESClient;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.createIndex;
import static org.icij.datashare.user.User.local;

public class ServerMode extends CommonMode {
    public ServerMode(Properties properties) { super(properties);}

    public ServerMode(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        bind(Users.class).to(RedisUsers.class);
        bind(SessionIdStore.class).to(RedisSessionIdStore.class);
        bind(Filter.class).to(OAuth2CookieFilter.class).asEagerSingleton();

        RestHighLevelClient esClient = createESClient(propertiesProvider);
        createIndex(esClient, local().indexName(), propertiesProvider);
        bind(RestHighLevelClient.class).toInstance(esClient);
        bind(Indexer.class).to(ElasticsearchIndexer.class).asEagerSingleton();

        bind(TaskManager.class).toInstance(new TaskManager(propertiesProvider));
        install(new FactoryModuleBuilder().build(TaskFactory.class));
        bind(Publisher.class).to(RedisPublisher.class);
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.add(TaskResource.class).add(SearchResource.class).add(NamedEntityResource.class);
    }
}
