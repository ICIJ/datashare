package org.icij.datashare.test;

import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.client.*;
import org.junit.rules.ExternalResource;

import java.io.IOException;

import static com.google.common.io.ByteStreams.toByteArray;
import static jdk.nashorn.internal.runtime.Context.printStackTrace;
import static org.apache.http.HttpHost.create;
import static org.elasticsearch.common.xcontent.XContentType.JSON;

public class ElasticsearchRule extends ExternalResource {
    public static final String TEST_INDEX = "test-datashare";
    private static final String MAPPING_RESOURCE_NAME = "datashare_index_mappings.json";
    private static final String SETTINGS_RESOURCE_NAME = "datashare_index_settings.json";
    public final RestHighLevelClient client;
    private final String indexName;

    public ElasticsearchRule() {
        this(TEST_INDEX);
    }
    public ElasticsearchRule(final String indexName) {
        this.indexName = indexName;
        System.setProperty("es.set.netty.runtime.available.processors", "false");
        client = new RestHighLevelClient(RestClient.builder(create("http://elasticsearch:9200")));
    }

    @Override
    protected void before() throws Throwable {
        GetIndexRequest request = new GetIndexRequest();
        request.indices(indexName);
        if (! client.indices().exists(request, RequestOptions.DEFAULT)) {
            CreateIndexRequest createReq = new CreateIndexRequest(indexName);
            byte[] settings = toByteArray(getClass().getClassLoader().getResourceAsStream(SETTINGS_RESOURCE_NAME));
            createReq.settings(new String(settings), JSON);
            byte[] mapping = toByteArray(getClass().getClassLoader().getResourceAsStream(MAPPING_RESOURCE_NAME));
            createReq.mapping("_doc", new String(mapping), JSON);
            client.indices().create(createReq, RequestOptions.DEFAULT);
        }
    }

    @Override
    protected void after() {
        try {
            client.indices().delete(new DeleteIndexRequest(indexName), RequestOptions.DEFAULT);
            client.close();
        } catch (IOException e) {
            printStackTrace(e);
        }
    }

    public void delete(String... indices) throws IOException {
        for (String index: indices) {
            Request request = new Request("DELETE", index);
            request.addParameter("ignore_unavailable", "true");
            client.getLowLevelClient().performRequest(request);
        }
    }

    public void removeAll() throws IOException {
        Request post = new Request("POST", indexName + "/_delete_by_query");
        post.addParameter("refresh", "true");
        post.setEntity(new NStringEntity("{\"query\": {\"match_all\": {}}}", ContentType.APPLICATION_JSON));
        Response response = client.getLowLevelClient().performRequest(post);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new RuntimeException("error while executing delete by query status : " + response.getStatusLine().getStatusCode());
        }
    }
}
