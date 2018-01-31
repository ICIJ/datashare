package org.icij.datashare.extract;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.redis.RedisDocumentQueue;
import org.icij.task.Options;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RedisInjectableDocumentQueue extends RedisDocumentQueue {
    @Inject
    public RedisInjectableDocumentQueue(PropertiesProvider propertiesProvider) {
        super(new DocumentFactory(Options.from(propertiesProvider.getProperties())),
                Options.from(propertiesProvider.getProperties()));
    }
}
