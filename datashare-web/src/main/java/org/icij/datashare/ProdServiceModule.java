package org.icij.datashare;

import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;
import org.elasticsearch.client.Client;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.redis.RedisPublisher;
import org.icij.datashare.extract.RedisInjectableDocumentQueue;
import org.icij.datashare.session.RedisSessionIdStore;
import org.icij.datashare.session.RedisUsers;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.Spewer;

import java.util.Properties;

import static java.lang.Boolean.parseBoolean;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.*;

public class ProdServiceModule extends AbstractModule{
    private final Properties properties;

    public ProdServiceModule(Properties properties) {
        this.properties = properties;
    }

    @Override
    protected void configure() {
        PropertiesProvider propertiesProvider = properties == null ? new PropertiesProvider() : new PropertiesProvider().mergeWith(properties);
        bind(PropertiesProvider.class).toInstance(propertiesProvider);

        String authProp = propertiesProvider.get("auth").orElse("false");
        if (parseBoolean(authProp)) {
            bind(Users.class).to(RedisUsers.class);
            bind(SessionIdStore.class).to(RedisSessionIdStore.class);
        }
        bind(Client.class).toInstance(createESClient(propertiesProvider));

        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);
        bind(Publisher.class).to(RedisPublisher.class);
        bind(Spewer.class).to(ElasticsearchSpewer.class);
        bind(Indexer.class).to(ElasticsearchIndexer.class);
        bind(DocumentQueue.class).to(RedisInjectableDocumentQueue.class);
        install(new FactoryModuleBuilder().build(TaskFactory.class));
    }
}
