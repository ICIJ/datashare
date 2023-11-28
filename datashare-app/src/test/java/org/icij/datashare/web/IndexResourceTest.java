package org.icij.datashare.web;

import co.elastic.clients.elasticsearch._types.Refresh;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEXES;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class IndexResourceTest extends AbstractProdWebServerTest {
    @Mock JooqRepository jooqRepository;
    @ClassRule public static ElasticsearchRule esRule = new ElasticsearchRule(TEST_INDEXES);
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(esRule.client, new PropertiesProvider()).withRefresh(Refresh.True);
    private final PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("defaultUserName", "test");
    }});

    @Test
    public void test_no_auth_get_forward_request_to_elastic() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(propertiesProvider, jooqRepository)));
        get("/api/index/search/test-datashare/_search").should().respond(200).contain("\"successful\":1");
    }

    @Test
    public void test_no_auth_get_forward_request_to_elastic_if_granted_to_read_index() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(propertiesProvider, jooqRepository)));
        get("/api/index/search/unauthorized/_search").should().respond(401);
    }
    @Test
    public void test_no_auth_get_forward_request_to_elastic_with_empty_indice() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(propertiesProvider, jooqRepository)));
        get("/api/index/search/    /_search").should().respond(400);
        get("/api/index/search/!!/_search").should().respond(400);
    }
    @Test
    public void test_no_auth_get_unauthorized_on_unknown_index() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(propertiesProvider, jooqRepository)));
        get("/api/index/search/hacker/bar/baz").should().respond(401);
    }
    @Test
    public void test_put_create_local_index_in_local_mode() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(propertiesProvider, jooqRepository)));
        put("/api/index/index_name").should().respond(201);
        put("/api/index/ !!").should().respond(400);
        put("/api/index/  /").should().respond(404);
    }
    @Test
    public void test_no_auth_post_forward_request_to_elastic_with_body() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(propertiesProvider, jooqRepository)));
        post("/api/index/search/test-datashare/_search", "{}").should().respond(200).contain("\"successful\":1");
        post("/api/index/search/  \\").should().respond(400);
        post("/api/index/search/  /  ").should().respond(400);
        post("/api/index/search/unauthorized/_search").should().respond(401);
    }

    @Test
    public void test_no_auth_options_forward_request_to_elastic() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(propertiesProvider, jooqRepository)));
        options("/api/index/search/test-datashare").should().respond(200);
        options("/api/index/search/  /").should().respond(400);
        options("/api/index/search/  \\").should().respond(400);
    }

    @Test
    public void test_delete_should_return_method_not_allowed() {
        configure(routes -> routes.add(new IndexResource(indexer)).filter(new LocalUserFilter(propertiesProvider, jooqRepository)));
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
        post("/api/index/search/cecile-datashare/_doc/_search").withPreemptiveAuthentication("cecile", "").should().respond(200);
        get("/api/index/search/cecile-datashare/_doc/1234567890abcdef").withPreemptiveAuthentication("cecile", "").should().respond(200);
        post("/api/index/search/_search/scroll", "{\"scroll_id\":\"DXF1ZXJ5QW5kRmV0Y2gBAAAAAAAAAD4WYm9laVYtZndUQlNsdDcwakFMNjU1QQ\"}").withPreemptiveAuthentication("cecile", "").should().respond(500);
        post("/api/index/search/cecile-datashare/_count").withPreemptiveAuthentication("cecile", "").should().respond(200);

        post("/api/index/search/cecile-datashare/_delete_by_query").withPreemptiveAuthentication("cecile", "").should().respond(401);
    }

    @Test
    public void test_auth_forward_request_with_user_logged_on_allow_search_on_multiple_indices() throws IOException {
        configure(routes ->
                routes.add(new IndexResource(indexer)).
                        filter(new BasicAuthFilter("/", "icij", DatashareUser.singleUser(new User(new HashMap<String, Object>() {
                            {
                                this.put("uid", "cecile");
                                this.put("groups_by_applications", new HashMap<String, Object>() {
                                    {
                                        this.put("datashare", Arrays.asList(TEST_INDEXES[1], TEST_INDEXES[2]));
                                    }
                                });
                            }
                        })))));
        indexer.add(TEST_INDEXES[1], DocumentBuilder.createDoc("doc1").withRootId("rootId").build());
        indexer.add(TEST_INDEXES[2], DocumentBuilder.createDoc("doc2").withRootId("rootId").build());
        post("/api/index/search/test-index1,test-index2/_search").withPreemptiveAuthentication("cecile", "").should().respond(200);
        post("/api/index/search/test-index1,test-index2/_doc/_search").withPreemptiveAuthentication("cecile", "").should().respond(200);
        post("/api/index/search/test-index1,test-index2/_count").withPreemptiveAuthentication("cecile", "").should().respond(200);

        post("/api/index/search/test-index1,test-index2/_delete_by_query").withPreemptiveAuthentication("cecile", "").should().respond(401);
    }

    @Test
    public void test_auth_forward_request_with_user_logged_on_multiple_indices_with_bad_requests() throws IOException {
        configure(routes ->
                routes.add(new IndexResource(indexer)).
                        filter(new BasicAuthFilter("/", "icij", DatashareUser.singleUser(new User(new HashMap<String, Object>() {
                            {
                                this.put("uid", "cecile");
                                this.put("groups_by_applications", new HashMap<String, Object>() {
                                    {
                                        this.put("datashare", Arrays.asList(TEST_INDEXES[1], TEST_INDEXES[2]));
                                    }
                                });
                            }
                        })))));
        indexer.add(TEST_INDEXES[1], DocumentBuilder.createDoc("doc1").withRootId("rootId").build());
        indexer.add(TEST_INDEXES[2], DocumentBuilder.createDoc("doc2").withRootId("rootId").build());
        post("/api/index/search/test-index1,  /_search").withPreemptiveAuthentication("cecile", "").should().respond(400);
        post("/api/index/search/,test-index2/_doc/_search").withPreemptiveAuthentication("cecile", "").should().respond(400);
        post("/api/index/search/,test-index2,/_count").withPreemptiveAuthentication("cecile", "").should().respond(400);
        post("/api/index/search/ test-index1  /_delete_by_query").withPreemptiveAuthentication("cecile", "").should().respond(400);
        get("/api/index/search/test-index1, test-index1").withPreemptiveAuthentication("cecile", "").should().respond(400);
        get("/api/index/search/,test-index1").withPreemptiveAuthentication("cecile", "").should().respond(400);
        get("/api/index/search/test-index1test-index2,test-index2").withPreemptiveAuthentication("cecile", "").should().respond(400);
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
        put("/api/index/!!").withPreemptiveAuthentication("cecile", "pass").should().respond(400);
        put("/api/index/ cecile-datashare").withPreemptiveAuthentication("cecile", "pass").should().respond(400);
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        configure(routes -> {
            Users users =  DatashareUser.singleUser("cecile");
            routes
                    .add(new IndexResource(indexer))
                    .filter(new BasicAuthFilter("/", "icij", users));
        });
    }

    @After
    public void tearDown() throws Exception {
        esRule.delete("cecile-datashare", "index_name");
    }
}

