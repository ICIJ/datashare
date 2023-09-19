package org.icij.datashare.web;

import net.codestory.http.Context;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.elasticsearch.client.RestHighLevelClient;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration;
import org.icij.datashare.web.IndexWaiterFilter;
import org.junit.After;
import org.junit.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class IndexWaiterFilterTest {
    private Payload next = Payload.ok();
    private PayloadSupplier nextFilter = () -> next;
    private Context context = mock(Context.class);
    private final RestHighLevelClient esClient = ElasticsearchConfiguration.createESClient(new PropertiesProvider());

    @After
    public void tearDown() throws Exception { esClient.close();}

    @Test
    public void test_wait_for_index() throws Exception {
        IndexWaiterFilter indexWaiterFilter = new IndexWaiterFilter(esClient).waitForIndexAsync();

        Payload payload = indexWaiterFilter.apply("/", context, nextFilter);

        assertThat(payload.code()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(payload.rawContentType()).isEqualTo("text/html");
        assertThat((String)payload.rawContent()).contains("waiting for Datashare to be up...");

        indexWaiterFilter.executor.awaitTermination(2, SECONDS);
        assertThat(indexWaiterFilter.apply("/", context, nextFilter)).isSameAs(next);
    }

    @Test
    public void test_wait_for_index_api_status_endpoint_should_return_status() throws Exception {
        IndexWaiterFilter indexWaiterFilter = new IndexWaiterFilter(esClient).waitForIndexAsync();

        Payload payload = indexWaiterFilter.apply("/api/status", context, nextFilter);

        assertThat(payload.code()).isEqualTo(HttpStatus.OK);
    }
}
