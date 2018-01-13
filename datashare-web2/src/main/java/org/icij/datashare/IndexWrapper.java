package org.icij.datashare;

import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;

import javax.inject.Inject;
import java.util.Properties;

public class IndexWrapper extends ElasticsearchIndexer{
    @Inject
    public IndexWrapper(Properties properties) {
        super(properties);
    }
}
