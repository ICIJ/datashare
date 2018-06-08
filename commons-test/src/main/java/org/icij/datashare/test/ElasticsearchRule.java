package org.icij.datashare.test;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.junit.rules.ExternalResource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.elasticsearch.common.xcontent.XContentType.JSON;
import static com.google.common.io.ByteStreams.toByteArray;

public class ElasticsearchRule extends ExternalResource {
    public static final String TEST_INDEX = "test-datashare";
    private static final String MAPPING_RESOURCE_NAME = "datashare_index_mappings.json";
    public final Client client;
    private final String indexName;

    public ElasticsearchRule() {
        this(TEST_INDEX);
    }
    public ElasticsearchRule(final String indexName) {
        this.indexName = indexName;
        System.setProperty("es.set.netty.runtime.available.processors", "false");
        Settings settings = Settings.builder().put("cluster.name", "datashare").build();
        try {
            client = new PreBuiltTransportClient(settings).addTransportAddress(
                    new TransportAddress(InetAddress.getByName("elasticsearch"), 9300));
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void before() throws Throwable {
        if (! client.admin().indices().prepareExists(indexName).execute().actionGet().isExists()) {
            client.admin().indices().create(new CreateIndexRequest(indexName)).actionGet();
            byte[] mapping = toByteArray(getClass().getClassLoader().getResourceAsStream(MAPPING_RESOURCE_NAME));
            client.admin().indices().preparePutMapping(indexName).setType("doc").setSource(new String(mapping), JSON).
                            execute().actionGet();
        }
    }

    @Override
    protected void after() {
        client.admin().indices().delete(new DeleteIndexRequest(indexName)).actionGet();
        client.close();
    }

    public void removeAll() {
        DeleteByQueryAction.INSTANCE.newRequestBuilder(client).source(indexName).filter(QueryBuilders.matchAllQuery()).refresh(true).get();
    }
}
