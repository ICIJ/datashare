package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;

public class IndexResourceTest extends AbstractProdWebServerTest {
    @ClassRule public static ElasticsearchRule esRule = new ElasticsearchRule(TEST_INDEX);
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(esRule.client, new PropertiesProvider()).withRefresh(IMMEDIATE);

    @Test
    public void test_no_auth_get_forward_request_to_elastic() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(new PropertiesProvider(new HashMap<String, String>() {{
            put("defaultUserName", "test");
        }}))));
        get("/api/index/search/test-datashare/_search").should().respond(200).contain("\"successful\":1");
    }

    @Test
    public void test_no_auth_get_forward_request_to_elastic_if_granted_to_read_index() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(new PropertiesProvider(new HashMap<String, String>() {{
            put("defaultUserName", "test");
        }}))));
        get("/api/index/search/unauthorized/_search").should().respond(401);
    }
    @Test
    public void test_no_auth_get_unauthorized_on_unknown_index() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(LocalUserFilter.class));
        get("/api/index/search/hacker/bar/baz").should().respond(401);
    }
    @Test
    public void test_put_create_local_index_in_local_mode() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(LocalUserFilter.class));
        put("/api/index/index_name").should().respond(201);
    }
    @Test
    public void test_no_auth_post_forward_request_to_elastic_with_body() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(new PropertiesProvider(new HashMap<String, String>() {{
            put("defaultUserName", "test");
        }}))));
        post("/api/index/search/test-datashare/_search", "{}").should().respond(200).contain("\"successful\":1");
    }

    @Test
    public void test_no_auth_options_forward_request_to_elastic() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(new PropertiesProvider(new HashMap<String, String>() {{
            put("defaultUserName", "test");
        }}))));
        options("/api/index/search/test-datashare").should().respond(200);
    }

    @Test
    public void test_delete_should_return_method_not_allowed() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(LocalUserFilter.class));
        delete("/api/index/search/foo/bar").should().respond(405);
    }

    @Test
    public void test_auth_forward_request_with_user_logged_on() throws IOException {
        indexer.add("cecile-datashare", DocumentBuilder.createDoc("1234567890abcdef").withRootId("rootId").build());
        get("/api/index/search/cecile-datashare/_search?routing=rootId").withPreemptiveAuthentication("cecile", "").should().
                respond(200).contain("1234567890abcdef");

        get("/api/index/search/hacker/foo/bar?routing=baz").withPreemptiveAuthentication("cecile", "").should().respond(401);
        post("/api/index/search/hacker/foo/bar").withPreemptiveAuthentication("cecile", "").should().respond(401);
    }

    @Test
    public void test_auth_forward_request_with_user_logged_on_only_allow_search_and_count_on_post() throws IOException {
        indexer.add("cecile-datashare", DocumentBuilder.createDoc("1234567890abcdef").build());
        post("/api/index/search/cecile-datashare/_search").withPreemptiveAuthentication("cecile", "").should().respond(200);
        post("/api/index/search/cecile-datashare/doc/_search").withPreemptiveAuthentication("cecile", "").should().respond(200);
        get("/api/index/search/cecile-datashare/doc/1234567890abcdef").withPreemptiveAuthentication("cecile", "").should().respond(200);
        post("/api/index/search/_search/scroll", "{\"scroll_id\":\"DXF1ZXJ5QW5kRmV0Y2gBAAAAAAAAAD4WYm9laVYtZndUQlNsdDcwakFMNjU1QQ\"}").withPreemptiveAuthentication("cecile", "").should().respond(500);
        post("/api/index/search/cecile-datashare/_count").withPreemptiveAuthentication("cecile", "").should().respond(200);

        post("/api/index/search/cecile-datashare/_delete_by_query").withPreemptiveAuthentication("cecile", "").should().respond(401);
    }

    @Test
    public void test_auth_forward_request_for_scroll_requests() {
        post("/api/index/search/_search/scroll?scroll_id=DXF1ZXJ5QW5kRmV0Y2gBAAAAAAAAAD4WYm9laVYtZndUQlNsdDcwakFMNjU1QQ").withPreemptiveAuthentication("cecile", "").should().respond(500);
    }

    @Test
    public void test_put_should_return_method_not_allowed() {
        put("/api/index/search/cecile-datashare/_search").withPreemptiveAuthentication("cecile", "pass").should().respond(405);
    }

    @Test
    public void test_put_createIndex() {
        put("/api/index/cecile-datashare").withPreemptiveAuthentication("cecile", "pass").should().respond(201);
    }

    @Before
    public void setUp() {
        configure(routes ->
                routes.add(new IndexResource(indexer)).
                filter(new BasicAuthFilter("/", "icij", HashMapUser.singleUser("cecile"))));
    }

    @After
    public void tearDown() throws Exception {
        esRule.delete("cecile-datashare", "index_name");
    }
}

