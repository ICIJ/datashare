package org.icij.datashare;

import com.google.inject.AbstractModule;
import org.icij.datashare.text.indexing.Indexer;

public class ServiceModule extends AbstractModule{
    @Override
    protected void configure() {
        bind(Indexer.class).to(InjectableIndexer.class);

    }
}
