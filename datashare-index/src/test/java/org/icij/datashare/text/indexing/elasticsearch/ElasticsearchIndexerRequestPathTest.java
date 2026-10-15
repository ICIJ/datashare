package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.StatusLine;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.icij.datashare.PropertiesProvider;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every low level request the indexer builds targets an absolute path. AWS OpenSearch answers an
 * HTML 400 from its front end when the request line carries no leading slash, before the request
 * ever reaches the search engine, so a relative path fails there and nowhere else.
 */
public class ElasticsearchIndexerRequestPathTest {
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final RestClient restClient = mock(RestClient.class);
    private final ElasticsearchIndexer indexer =
            new ElasticsearchIndexer(client, new PropertiesProvider(new HashMap<>()));

    @Before
    public void setUp() throws Exception {
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(indices.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));
        when(client.indices()).thenReturn(indices);
        RestClientTransport transport = mock(RestClientTransport.class);
        when(transport.restClient()).thenReturn(restClient);
        when(client._transport()).thenReturn(transport);
        Response okResponse = okResponse();
        when(restClient.performRequest(any(Request.class))).thenReturn(okResponse);
    }

    @Test
    public void test_delete_all_targets_an_absolute_path() throws Exception {
        indexer.deleteAll("my-project");

        assertThat(capturedRequest().getEndpoint()).isEqualTo("/my-project/_delete_by_query?refresh");
    }

    @Test
    public void test_delete_index_targets_an_absolute_path() throws Exception {
        indexer.deleteIndex("my-project");

        assertThat(capturedRequest().getEndpoint()).isEqualTo("/my-project");
    }

    private Request capturedRequest() throws Exception {
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(restClient).performRequest(request.capture());
        return request.getValue();
    }

    private Response okResponse() {
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(200);
        Response response = mock(Response.class);
        when(response.getStatusLine()).thenReturn(statusLine);
        return response;
    }
}
