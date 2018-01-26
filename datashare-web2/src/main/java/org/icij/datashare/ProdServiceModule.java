package org.icij.datashare;

import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.extract.ElasticsearchSpewer;
import org.icij.datashare.extract.RedisInjectableDocumentQueue;
import org.icij.datashare.language.LanguageGuesser;
import org.icij.datashare.language.OptimaizeLanguageGuesser;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.Spewer;

public class ProdServiceModule extends AbstractModule{
    @Override
    protected void configure() {
        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);
        bind(Publisher.class).to(RedisPublisher.class);
        bind(Spewer.class).to(ElasticsearchSpewer.class);
        bind(DocumentQueue.class).to(RedisInjectableDocumentQueue.class);
        install(new FactoryModuleBuilder().build(TaskFactory.class));
    }
}
