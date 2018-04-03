package org.icij.datashare;

import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.redis.RedisPublisher;
import org.icij.datashare.extract.RedisInjectableDocumentQueue;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.Spewer;

import java.util.Properties;

public class ProdServiceModule extends AbstractModule{
    private final Properties properties;

    public ProdServiceModule(Properties properties) {
        this.properties = properties;
    }

    @Override
    protected void configure() {
        if (properties != null) {
            bind(PropertiesProvider.class).toInstance(new PropertiesProvider(properties));
        }
        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);
        bind(Publisher.class).to(RedisPublisher.class);
        bind(Spewer.class).to(ElasticsearchSpewer.class).asEagerSingleton();
        bind(Indexer.class).to(ElasticsearchIndexer.class).asEagerSingleton();
        bind(DocumentQueue.class).to(RedisInjectableDocumentQueue.class).asEagerSingleton();
        install(new FactoryModuleBuilder().build(TaskFactory.class));
    }
}
