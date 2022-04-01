package org.icij.datashare.test;

import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.*;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.junit.rules.ExternalResource;

import java.io.IOException;
import java.util.Objects;

import static com.google.common.io.ByteStreams.toByteArray;
import static org.apache.http.HttpHost.create;
import static org.elasticsearch.common.xcontent.XContentType.JSON;

public class ElasticsearchRule extends ExternalResource {
    public static final String[] TEST_INDEXES = {"test-datashare", "test-index1", "test-index2"};
    public static final String TEST_INDEX = "test-datashare";
    private static final String MAPPING_RESOURCE_NAME = "datashare_index_mappings.json";
    private static final String SETTINGS_RESOURCE_NAME = "datashare_index_settings.json";
    public final RestHighLevelClient client;
    private final String[] indexesNames;

    public ElasticsearchRule() {
        this(new String[]{TEST_INDEX});
    }
    public ElasticsearchRule(final String[] indexesName) { this(indexesName, create("http://elasticsearch:9200"));}
    public ElasticsearchRule(final HttpHost esHost) { this(TEST_INDEXES, esHost);}
    public ElasticsearchRule(final String[] indexesName, HttpHost elasticHost) {
        this.indexesNames = indexesName;
        System.setProperty("es.set.netty.runtime.available.processors", "false");
        client = new RestHighLevelClient(RestClient.builder(elasticHost));
    }

    @Override
    protected void before() throws Throwable {
        GetIndexRequest request = new GetIndexRequest(indexesNames);
        if (!client.indices().exists(request, RequestOptions.DEFAULT)) {
            for (String index : indexesNames) {
                CreateIndexRequest createReq = new CreateIndexRequest(index);
                byte[] settings = toByteArray(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(SETTINGS_RESOURCE_NAME)));
                createReq.settings(new String(settings), JSON);
                byte[] mapping = toByteArray(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(MAPPING_RESOURCE_NAME)));
                createReq.mapping(new String(mapping), JSON);
                client.indices().create(createReq, RequestOptions.DEFAULT);
            }
        }
    }

    @Override
    protected void after() {
        try {
            client.indices().delete(new DeleteIndexRequest(indexesNames), RequestOptions.DEFAULT);
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
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
        for (String index : indexesNames) {
            Request post = new Request("POST", index + "/_delete_by_query");
            post.addParameter("refresh", "true");
            post.setEntity(new NStringEntity("{\"query\": {\"match_all\": {}}}", ContentType.APPLICATION_JSON));
            Response response = client.getLowLevelClient().performRequest(post);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException("error while executing delete by query status : " + response.getStatusLine().getStatusCode());
            }
        }
    }
}
