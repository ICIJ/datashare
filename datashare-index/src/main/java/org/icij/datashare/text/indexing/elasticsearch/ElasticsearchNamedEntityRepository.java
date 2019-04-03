package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.client.RestHighLevelClient;
import org.icij.datashare.NamedEntityRepository;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;

import java.io.IOException;
import java.util.List;

public class ElasticsearchNamedEntityRepository implements NamedEntityRepository {
    private final ElasticsearchIndexer es;

    ElasticsearchNamedEntityRepository(RestHighLevelClient client) {
        es = new ElasticsearchIndexer(client, new PropertiesProvider());
    }

    @Override
    public NamedEntity get(String id) {
        return es.get("local-datashare", id);
    }

    @Override
    public void create(List<NamedEntity> neList) throws IOException {
        es.bulkAdd("local-datashare", neList);
    }

    @Override
    public void create(Document document) throws IOException {
        es.add("local-datashare", document);
    }

    @Override
    public void update(NamedEntity ne) {

    }

    @Override
    public NamedEntity delete(String id) {
        return null;
    }
}
