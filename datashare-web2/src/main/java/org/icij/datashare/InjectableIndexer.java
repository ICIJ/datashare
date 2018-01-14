package org.icij.datashare;

import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;

import javax.inject.Inject;
import java.io.IOException;

public class InjectableIndexer extends ElasticsearchIndexer {
    @Inject
    public InjectableIndexer(final PropertiesProvider propertiesProvider) throws IOException {
        super(propertiesProvider.getProperties());
    }
}
