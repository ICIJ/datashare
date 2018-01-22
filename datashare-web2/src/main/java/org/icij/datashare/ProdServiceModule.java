package org.icij.datashare;

import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import org.icij.datashare.extract.ElasticsearchSpewer;
import org.icij.extract.queue.ArrayDocumentQueue;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.Spewer;

public class ProdServiceModule extends AbstractModule{
    @Override
    protected void configure() {
        bind(Spewer.class).to(ElasticsearchSpewer.class);
        // bind(Client.class).toInstance(ElasticsearchSpewer.createESClient());

        bind(DocumentQueue.class).toInstance(new ArrayDocumentQueue(1024));
        // bind(DocumentQueue.class).to(RedisDocumentQueue.class);

        install(new FactoryModuleBuilder().build(TaskFactory.class));
    }
}
