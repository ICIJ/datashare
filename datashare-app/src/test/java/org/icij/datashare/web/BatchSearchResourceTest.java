package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.batch.WebQueryBuilder;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.jooq.exception.DataAccessException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.ProjectProxy.proxy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchSearchResourceTest extends AbstractProdWebServerTest {
    @Mock BatchSearchRepository batchSearchRepository;
    @Mock JooqRepository jooqRepository;

    @Test
    public void test_get_batch_search() {
        BatchSearch search1 = new BatchSearch(singletonList(project("prj")), "name1", "description1", asSet("query 1", "query 2"), null, User.local());
        BatchSearch search2 = new BatchSearch(asList(project("prj1"), project("prj2")), "name2", "description2", asSet("query 3", "query 4"), null, User.local());
        when(batchSearchRepository.get(User.local(), search1.uuid, false)).thenReturn(search1);
        when(batchSearchRepository.get(User.local(), search2.uuid, false)).thenReturn(search2);

        get("/api/batch/search/" + search1.uuid).should().respond(200).haveType("application/json").contain("\"name\":\"name1\"");
        get("/api/batch/search/" + search2.uuid).should().respond(200).haveType("application/json").contain("\"name\":\"name2\"");
    }

    @Test
    public void test_get_unknown_batch_search_returns_404() {
        get("/api/batch/search/unknown-id").should()
                .respond(404)
                .haveType("application/json")
                .contain("Batch search not found.");
    }

    @Test
    public void test_get_batch_searches_records_json_paginated() {
        List<BatchSearchRecord> batchSearches = IntStream.range(0, 10).mapToObj(i -> new BatchSearchRecord(List.of(proxy("local-datashare"),proxy("project-2")), "name" + i, "description" + i, 2, new Date(), null)).collect(toList());
        when(batchSearchRepository.getRecords(User.local(), singletonList("local-datashare"), WebQueryBuilder.createWebQuery().queryAll().withRange(0,2).build())).thenReturn(batchSearches.subList(0, 2));
        when(batchSearchRepository.getTotal(User.local(), singletonList("local-datashare"), WebQueryBuilder.createWebQuery().queryAll().withRange(0,2).build())).thenReturn(batchSearches.size());
        when(batchSearchRepository.getRecords(User.local(), List.of("local-datashare"),WebQueryBuilder.createWebQuery().queryAll().withRange(4,3).build())).thenReturn(batchSearches.subList(5, 8));
        when(batchSearchRepository.getTotal(User.local(), List.of("local-datashare"), WebQueryBuilder.createWebQuery().queryAll().withRange(4,3).build())).thenReturn(batchSearches.size());

        get("/api/batch/search?from=0&size=2&query=*&field=all").should().respond(200).haveType("application/json").
                contain("\"name\":\"name0\"").
                contain("\"name\":\"name1\"").
                contain("\"projects\":[\"local-datashare\",\"project-2\"]").
                contain("\"pagination\":{\"count\":2,\"from\":0,\"size\":2,\"total\":10}").
                should().not().contain("name2");

        get("/api/batch/search?from=4&size=3&query=*&field=all").should().respond(200).haveType("application/json").
                contain("\"name\":\"name5\"").
                contain("\"name\":\"name6\"").
                contain("\"name\":\"name7\"").
                contain("\"projects\":[\"local-datashare\",\"project-2\"]").
                contain("\"pagination\":{\"count\":3,\"from\":4,\"size\":3,\"total\":10}").
                should().not().contain("name8").
                should().not().contain("name4");
    }

    @Test
    public void test_get_batch_searches_records_json_with_filters() {
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=local-datashare&field=all&tab=extracted-text&f[batchDate]=1656432540000&f[batchDate]=1656518940000";
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(singletonList(project("local-datashare")), "name", "description", 10, new Date(), uri);
        when(batchSearchRepository.getRecords(User.local(), singletonList("local-datashare"),
                WebQueryBuilder.createWebQuery().queryAll().withProjects(singletonList("local-datashare")).withBatchDate(asList("1656432540000", "1656518940000"))
                        .withState(singletonList(BatchSearchRecord.State.QUEUED.toString())).withPublishState("0").build())).thenReturn(singletonList(batchSearchRecord));
        when(batchSearchRepository.getTotal(User.local(), singletonList("local-datashare"),
                WebQueryBuilder.createWebQuery().queryAll().withProjects(singletonList("local-datashare")).withBatchDate(asList("1656432540000", "1656518940000"))
                        .withState(singletonList(BatchSearchRecord.State.QUEUED.toString())).withPublishState("0").build())).thenReturn(1);

        post("/api/batch/search", "{\"from\":0, \"size\":0, \"query\":\"*\", \"field\":\"all\", \"project\":[\"local-datashare\"], " +
                "\"batchDate\":[\"1656432540000\",\"1656518940000\"], \"state\":[\"QUEUED\"], \"publishState\":\"0\"}").should().respond(200)
                .haveType("application/json")
                .contain("\"name\":\"name\"")
                .contain("\"total\":1");
    }

    @Test
    public void test_get_search_results_json() {
        when(batchSearchRepository.getResults(eq(User.local()), eq("batchSearchId"), any())).thenReturn(asList(
                new SearchResult("q1", "docId1", "rootId1", Paths.get("/path/to/doc1"), new Date(), "content/type", 123L, 1),
                new SearchResult("q2", "docId2", "rootId2", Paths.get("/path/to/doc2"), new Date(), "content/type", 123L, 2)
        ));

        when(batchSearchRepository.getResultsTotal(eq(User.local()), eq("batchSearchId"), any())).thenReturn(2);

        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":0, \"query\":\"*\", \"field\":\"all\"}").
                should().respond(200).haveType("application/json").
                contain("\"documentId\":\"docId1\"").
                contain("\"documentId\":\"docId2\"").
                contain("\"pagination\":{\"count\":2,\"from\":0,\"size\":0,\"total\":2}");
    }

    @Test
    public void test_get_search_results_json_paginated() {
        List<SearchResult> results = IntStream.range(0, 10).
                mapToObj(i -> new SearchResult("q" + i, "docId" + i, "rootId" + i,
                        Paths.get("/path/to/doc" + i), new Date(), "content/type", 123L, i)).collect(toList());
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", WebQueryBuilder.createWebQuery().queryAll().withRange(0,5).build())).thenReturn(results.subList(0, 5));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", WebQueryBuilder.createWebQuery().queryAll().withRange(9,2).build())).thenReturn(results.subList(8, 10));

        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":5, \"query\":\"*\", \"field\":\"all\"}").should().
                contain("\"documentId\":\"docId0\"").
                contain("\"documentId\":\"docId4\"").
                should().not().contain("\"documentId\":\"docId5\"");
        post("/api/batch/search/result/batchSearchId", "{\"from\":9, \"size\":2, \"query\":\"*\", \"field\":\"all\"}").should().
                contain("\"documentId\":\"docId8\"").
                contain("\"documentId\":\"docId9\"").
                not().contain("\"documentId\":\"docId7\"");
    }
    @Test
    public void test_get_search_results_filtered_by_content_types() {
        SearchResult searchResult1 = new SearchResult("q1", "docId1", "rootId1",
                Paths.get("/path/to/doc1"), new Date(), "content/type", 123L, 1);
        SearchResult searchResult2 = new SearchResult("q2", "docId2", "rootId2",
                Paths.get("/path/to/doc2"), new Date(), "content/type", 123L, 2);
        SearchResult searchResult3 = new SearchResult("q3", "docId3", "rootId3",
                Paths.get("/path/to/doc3"), new Date(), "content/other", 123L, 3);

        List<SearchResult> searchResultsContentType = asList(searchResult1, searchResult2);
        List<SearchResult> searchResultsAllContentType = asList(searchResult1, searchResult2,searchResult3);
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", WebQueryBuilder.createWebQuery().queryAll().withContentTypes(List.of("content/type")).build()))
                .thenReturn(searchResultsContentType);
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", WebQueryBuilder.createWebQuery().queryAll().withContentTypes(List.of()).build()))
                .thenReturn(searchResultsAllContentType);
        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":0, \"query\":\"*\", \"field\":\"all\",\"contentTypes\":[\"content/type\"]}").
                should().respond(200).haveType("application/json").
                contain("\"documentId\":\"docId1\"").
                contain("\"documentId\":\"docId2\"");
        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":0, \"query\":\"*\", \"field\":\"all\",\"contentTypes\":[]}").
                should().respond(200).haveType("application/json").
                contain("\"documentId\":\"docId1\"").
                contain("\"documentId\":\"docId2\"").
                contain("\"documentId\":\"docId3\"");
    }

    @Test
    public void test_get_search_results_csv() {
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(singletonList(project("prj")), "name", "desc", asSet("q1", "q2"), null, User.local()));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId",WebQueryBuilder.createWebQuery().queryAll().build())).thenReturn(asList(
                new SearchResult("q1", "docId1", "rootId1", Paths.get("/path/to/doc1"), new Date(), "content/type", 123L, 1),
                new SearchResult("q2", "docId2", "rootId2", Paths.get("/path/to/doc2"), new Date(), "content/type", 123L, 2)
        ));

        get("/api/batch/search/result/csv/batchSearchId").
                should().respond(200).haveType("text/csv").
                haveHeader("Content-Disposition", "attachment;filename=\"batchSearchId.csv\"").
                contain(format("\"localhost:%d/#/d/prj/docId1/rootId1\",\"docId1\",\"rootId1\"", port())).
                contain(format("\"localhost:%d/#/d/prj/docId2/rootId2\",\"docId2\",\"rootId2\"", port())).
                contain("\"/path/to/doc1\"").
                contain("\"/path/to\"");
    }

    @Test
    public void test_get_search_results_csv_with_url_prefix_parameter() {
        server.configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
                put("rootHost", "http://foo.com:12345");
            }});
            routes.add(new BatchSearchResource(propertiesProvider, batchSearchRepository)).
                    filter(new LocalUserFilter(propertiesProvider, jooqRepository));
        });
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(singletonList(project("prj")), "name", "desc", asSet("q"), null, User.local()));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId",WebQueryBuilder.createWebQuery().queryAll().build())).thenReturn(singletonList(
                new SearchResult("q", "docId", "rootId", Paths.get("/path/to/doc"), new Date(), "content/type", 123L, 1)
        ));

        get("/api/batch/search/result/csv/batchSearchId").should().respond(200).haveType("text/csv").
                haveHeader("Content-Disposition", "attachment;filename=\"batchSearchId.csv\"").
                contain("\"http://foo.com:12345/#/d/prj/docId/rootId\",\"docId\",\"rootId\"");
    }

    @Test
    public void test_get_search_results_unauthorized_user() {
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", WebQueryBuilder.createWebQuery().queryAll().build())).
                thenThrow(new JooqBatchSearchRepository.UnauthorizedUserException("batchSearchId", "owner", "actual"));

        get("/api/batch/search/result/csv/batchSearchId").should().respond(401);
        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":0, \"query\":\"*\", \"field\":\"all\"}").should().respond(401);
    }

    @Test
    public void test_delete_batch_search() {
        when(batchSearchRepository.deleteAll(User.local())).thenReturn(true).thenReturn(false);

        delete("/api/batch/search").should().respond(204);
        delete("/api/batch/search").should().respond(204);
    }

    @Test
    public void test_update_batch_search() {
        when(batchSearchRepository.publish(User.local(), "batchId", true)).thenReturn(true).thenReturn(false);

        patch("/api/batch/search/batchId", "{\"data\": {\"published\": true}}").should().respond(200);
        patch("/api/batch/search/batchId", "{\"data\": {\"published\": true}}").should().respond(404);
    }

    @Test
    public void test_delete_batch_search_by_id() {
        when(batchSearchRepository.delete(User.local(), "myid")).thenReturn(true).thenReturn(false);

        delete("/api/batch/search/unknownid").should().respond(204);
        delete("/api/batch/search/myid").should().respond(204);
        delete("/api/batch/search/myid").should().respond(204);
    }

    @Test
    public void test_get_queries_json() {
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 0,null,null, null, -1)).
                thenReturn(new HashMap<>() {{
                    put("q1", 1);
                    put("q2", 2);
                }});
        get("/api/batch/search/batchSearchId/queries").should().
                respond(200).
                haveType("application/json;charset=UTF-8").
                contain("{\"q1\":1,\"q2\":2}");
    }

    @Test
    public void test_get_queries_csv() {
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId",0,0,null,null, null, -1)).
                thenReturn(new HashMap<>() {{
                    put("q1", 1);
                    put("q2", 2);
                }});
        get("/api/batch/search/batchSearchId/queries?format=csv").should().
                respond(200).
                haveType("text/csv;charset=UTF-8").
                contain("q1\nq2");
    }

    @Test
    public void test_get_queries_filtered_to_max_results() {
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 0,null,null, null, 200)).
                thenReturn(new HashMap<>() {{
                    put("q1", 100);
                    put("q2", 200);
                }});
        get("/api/batch/search/batchSearchId/queries?maxResults=200").should().
                respond(200).
                haveType("application/json;charset=UTF-8").
                contain("\"q1\":100").
                contain("\"q2\":200").
                not().contain("\"q3\":300");
    }

    @Test
    public void test_get_batch_search_without_queries() {
        BatchSearch search = new BatchSearch("uuid", singletonList(project("prj")), "name", "desc", 2, 2, new Date(), BatchSearchRecord.State.SUCCESS,
                null, User.local(), 3, true, singletonList("application/pdf"), null, List.of("/path"), 0, false, null, null);
        when(batchSearchRepository.get(User.local(), search.uuid, false)).thenReturn(search);

        get("/api/batch/search/uuid?withQueries=false").should().
                respond(200).haveType("application/json").
                contain("\"nbQueries\":2").contain("\"nbQueriesWithoutResults\":2").contain("\"queries\":{}");
    }

    @Test
    public void test_get_batch_search_queries_with_window_from_size() {
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 2,null,null, null, -1)).thenReturn(new HashMap<>() {{put("q1", 1);put("q2", 2);}});
        get("/api/batch/search/batchSearchId/queries?from=0&size=2").should().
                respond(200).
                haveType("application/json").
                contain("{\"q1\":1,\"q2\":2}");
    }

    @Test
    public void test_get_batch_search_queries_with_window_from_size_with_filter_sort_order() {
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 2,"foo","bar", "desc", -1)).thenReturn(new HashMap<>() {{put("query", 1);}});
        get("/api/batch/search/batchSearchId/queries?from=0&size=2&search=foo&sort=bar&order=desc").should().
                respond(200).
                haveType("application/json").
                contain("{\"query\":1");
    }

    @Test
    public void test_get_batch_search_queries_with_window_from_size_with_unknown_filter_sort() {
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 2,"foo","unknown", "desc", -1)).thenThrow(DataAccessException.class);
        get("/api/batch/search/batchSearchId/queries?from=0&size=2&search=foo&sort=unknown&order=desc").should().respond(500);
    }

    @Test
    public void test_get_batch_search_queries_with_window_from_size_with_unknown_filter_order() {
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 2,"foo","bar", "unknown", -1)).thenThrow(IllegalArgumentException.class);
        get("/api/batch/search/batchSearchId/queries?from=0&size=2&search=foo&sort=bar&order=unknown").should().respond(500);
    }

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new BatchSearchResource(new PropertiesProvider(), batchSearchRepository)).
                filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }
}
