package org.icij.datashare.web;

import net.codestory.rest.Response;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.batch.WebQueryBuilder;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.function.Pair;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.ProjectProxy.proxy;
import static org.mockito.Mockito.mock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchSearchResourceTest extends AbstractProdWebServerTest {
    @Mock BatchSearchRepository batchSearchRepository;
    @Mock JooqRepository jooqRepository;
    @Mock
    DatashareTaskFactory factory;
    TaskManagerMemory taskManager;

    @Test
    public void test_upload_batch_search_csv_without_name_should_send_bad_request() {
        when(batchSearchRepository.save(any())).thenReturn(true);
        postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x")
                        .addFile(new FileUpload("csvFile").withContent("value\r\n")).build()).should().respond(400);
    }

    @Test
    public void test_upload_batch_search_csv_without_csvFile_should_send_bad_request() {
        when(batchSearchRepository.save(any())).thenReturn(true);
        postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x")
                        .addField("name","name").build()).should().respond(400);
    }

    @Test
    public void test_upload_batch_search_csv_with_csvFile_with_60K_queries_should_send_request_too_large() throws IOException {
        when(batchSearchRepository.save(any())).thenReturn(true);
        StringBuilder content = new StringBuilder();
        IntStream.range(0,60000).boxed().collect(Collectors.toList()).forEach(i -> content.append("Test ").append(i).append("\r\n"));
        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x")
                        .addField("name","nameValue")
                        .addFile(new FileUpload("csvFile").withContent(content.toString())).build()).response();
        assertThat(response.code()).isEqualTo(413);
    }

    @Test
    public void test_upload_batch_search_csv_with_name_and_csvfile_should_send_OK() throws InterruptedException {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
            new MultipartContentBuilder("AaB03x")
                    .addField("name","nameValue")
                    .addFile(new FileUpload("csvFile").withContent("query\r\néèàç\r\n")).build()).response();
        assertThat(response.code()).isEqualTo(200);
        BatchSearch expected = new BatchSearch(response.content(),
                singletonList(project("prj")), "nameValue", null,
                asSet("query", "éèàç"), new Date(), BatchSearch.State.QUEUED, User.local());
        verify(batchSearchRepository).save(eq(expected));
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).name).isEqualTo(BatchSearchRunner.class.getName());
    }

    @Test
    public void test_upload_batch_search_csv_triple_double_quote_match_phrases_false() {
        testTripleQuote(false, "\"\"\"query one\"\"\"\n","\"query one\"");
    }

    @Test
    public void test_upload_batch_search_csv_triple_double_quote_match_phrases_true(){
        testTripleQuote(true, "\"\"\"query one\"\"\"\n","\"\"\"query one\"\"\"");
    }

    @Test
    public void test_upload_batch_search_csv_double_quote_inside_query_match_phrases_false() {
        testTripleQuote(false, "\"\"\"term one\"\" AND term two\"\n","\"term one\" AND term two");
    }

    @Test
    public void test_upload_batch_search_csv_double_quote_inside_query_match_phrases_true() {
        testTripleQuote(true, "\"\"\"term one\"\" AND term two\"\n","\"\"\"term one\"\" AND term two\"");
    }

    @Test
    public void test_upload_batch_search_csv_one_double_quote_match_phrases_false() {
        testTripleQuote(false, "\"term one\" AND \"term two\"\n","\"term one\" AND \"term two\"");
    }

    @Test
    public void test_upload_batch_search_csv_with_all_parameters()  {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
            new MultipartContentBuilder("AaB03x")
                .addField("name","my batch search")
                .addField("description","search description")
                .addFile(
                        new FileUpload("csvFile").withFilename("search.csv").withContentType("text/csv").withContent("query one\n" +
                                "query two\r\n" +
                                "query three\r\n"))
                .addField("published",String.valueOf(true))
                .addField("fileTypes","application/pdf")
                .addField("fileTypes","image/jpeg")
                .addField("query_template","{\"test\":42}")
                .addField("tags","tag_02")
                .addField("paths","/path/to/document")
                .addField("paths","/other/path/")
                .addField("fuzziness",String.valueOf(4))
                .addField("phrase_matches",String.valueOf(true)).build()).response();

        assertThat(response.code()).isEqualTo(200);
        ArgumentCaptor<BatchSearch> argument = ArgumentCaptor.forClass(BatchSearch.class);
        verify(batchSearchRepository).save(argument.capture());
        assertThat(argument.getValue().published).isTrue();
        assertThat(argument.getValue().fileTypes).containsExactly("application/pdf", "image/jpeg");
        assertThat(argument.getValue().queryTemplate.toString()).isEqualTo("{\"test\":42}");
        assertThat(argument.getValue().paths).containsExactly("/path/to/document", "/other/path/");
        assertThat(argument.getValue().fuzziness).isEqualTo(4);
        assertThat(argument.getValue().phraseMatches).isTrue();
        assertThat(argument.getValue().user).isEqualTo(User.local());
        assertThat(argument.getValue().description).isEqualTo("search description");
        assertThat(argument.getValue().hasQueryTemplate()).isTrue();
        Iterator<String> iterator = argument.getValue().queries.keySet().iterator();
        assertThat(iterator.next()).isEqualTo("query one");
        assertThat(iterator.next()).isEqualTo("query two");
        assertThat(iterator.next()).isEqualTo("query three");
        assertThat(iterator.hasNext()).isFalse();
    }

    @Test
    public void test_rerun_batch_search_not_found() {
        post("/api/batch/search/copy/bad_uuid", "{}").should().respond(404);
    }

    @Test
    public void test_rerun_batch_search() throws InterruptedException {
        BatchSearch sourceSearch = new BatchSearch(asList(project("prj1"), project("prj2")), "name", "description1", asSet("query 1", "query 2"), User.local());
        when(batchSearchRepository.get(User.local(), sourceSearch.uuid)).thenReturn(sourceSearch);
        when(batchSearchRepository.save(any())).thenReturn(true);

        post("/api/batch/search/copy/" + sourceSearch.uuid,
                "{\"name\": \"test\", \"description\": \"test description\"}").
                should().respond(200);

        ArgumentCaptor<BatchSearch> argument = ArgumentCaptor.forClass(BatchSearch.class);
        verify(batchSearchRepository).save(argument.capture());
        assertThat(argument.getValue().name).isEqualTo("test");
        assertThat(argument.getValue().description).isEqualTo("test description");
        assertThat(argument.getValue().projects).isEqualTo(sourceSearch.projects);
        assertThat(argument.getValue().queries).isEqualTo(sourceSearch.queries);
        assertThat(argument.getValue().user).isEqualTo(sourceSearch.user);
        assertThat(argument.getValue().queryTemplate).isEqualTo(sourceSearch.queryTemplate);

        assertThat(argument.getValue().state).isEqualTo(BatchSearchRecord.State.QUEUED);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).name).isEqualTo(BatchSearchRunner.class.getName());
    }

    @Test
    public void test_upload_batch_search_csv_less_that_2chars_queries_are_filtered() {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x").
                        addField("name","my batch search").
                        addField("description", "search description").
                        addFile(new FileUpload("csvFile").withFilename("search.csv").withContentType("text/csv")
                                .withContent("1\n" + "\n" + "query\r\n")).build()).response();

        assertThat(response.code()).isEqualTo(200);
        verify(batchSearchRepository).save(eq(new BatchSearch(response.content(),
                singletonList(project("prj")), "my batch search", "search description",
                asSet("query"), new Date(), BatchSearch.State.RUNNING, User.local())));
    }

    @Test
    public void test_get_batch_search() {
        BatchSearch search1 = new BatchSearch(singletonList(project("prj")), "name1", "description1", asSet("query 1", "query 2"), User.local());
        BatchSearch search2 = new BatchSearch(asList(project("prj1"), project("prj2")), "name2", "description2", asSet("query 3", "query 4"), User.local());
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
        List<BatchSearchRecord> batchSearches = IntStream.range(0, 10).mapToObj(i -> new BatchSearchRecord(singletonList(proxy("local-datashare")), "name" + i, "description" + i, 2, new Date())).collect(toList());
        when(batchSearchRepository.getRecords(User.local(), singletonList("local-datashare"), WebQueryBuilder.createWebQuery().queryAll().withRange(0,2).build())).thenReturn(batchSearches.subList(0, 2));
        when(batchSearchRepository.getTotal(User.local(), singletonList("local-datashare"), WebQueryBuilder.createWebQuery().queryAll().withRange(0,2).build())).thenReturn(batchSearches.size());
        when(batchSearchRepository.getRecords(User.local(), singletonList("local-datashare"),WebQueryBuilder.createWebQuery().queryAll().withRange(4,3).build())).thenReturn(batchSearches.subList(5, 8));
        when(batchSearchRepository.getTotal(User.local(), singletonList("local-datashare"), WebQueryBuilder.createWebQuery().queryAll().withRange(4,3).build())).thenReturn(batchSearches.size());

        get("/api/batch/search?from=0&size=2&query=*&field=all").should().respond(200).haveType("application/json").
                contain("\"name\":\"name0\"").
                contain("\"name\":\"name1\"").
                contain("\"pagination\":{\"count\":2,\"from\":0,\"size\":2,\"total\":10}").
                should().not().contain("name2");

        get("/api/batch/search?from=4&size=3&query=*&field=all").should().respond(200).haveType("application/json").
                contain("\"name\":\"name5\"").
                contain("\"name\":\"name6\"").
                contain("\"name\":\"name7\"").
                contain("\"pagination\":{\"count\":3,\"from\":4,\"size\":3,\"total\":10}").
                should().not().contain("name8").
                should().not().contain("name4");
    }

    @Test
    public void test_get_batch_searches_records_json_with_filters() {
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(singletonList(project("local-datashare")), "name", "description", 10, new Date());
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
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(singletonList(project("prj")), "name", "desc", asSet("q1", "q2"),User.local()));
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
            routes.add(new BatchSearchResource(propertiesProvider, taskManager, batchSearchRepository)).
                    filter(new LocalUserFilter(propertiesProvider, jooqRepository));
        });
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(singletonList(project("prj")), "name", "desc", asSet("q"), User.local()));
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
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 0,null,null, -1)).
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
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId",0,0,null,null, -1)).
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
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 0,null,null, 200)).
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
        BatchSearch search = new BatchSearch("uuid", singletonList(project("prj")), "name", "desc", 2, new Date(), BatchSearchRecord.State.SUCCESS, User.local(),
                                        3, true, singletonList("application/pdf"), null, List.of("/path"), 0, false, null, null);
        when(batchSearchRepository.get(User.local(), search.uuid, false)).thenReturn(search);

        get("/api/batch/search/uuid?withQueries=false").should().
                respond(200).haveType("application/json").
                contain("\"nbQueries\":2").contain("\"queries\":{}");
    }

    @Test
    public void test_get_batch_search_queries_with_window_from_size() {
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 2,null,null, -1)).thenReturn(new HashMap<String, Integer>() {{put("q1", 1);put("q2", 2);}});
        get("/api/batch/search/batchSearchId/queries?from=0&size=2").should().
                respond(200).
                haveType("application/json").
                contain("{\"q1\":1,\"q2\":2}");
    }

    @Test
    public void test_get_batch_search_queries_with_window_from_size_with_filter_orderby() {
        when(batchSearchRepository.getQueries(User.local(), "batchSearchId", 0, 2,"foo","bar", -1)).thenReturn(new HashMap<String, Integer>() {{put("query", 1);}});
        get("/api/batch/search/batchSearchId/queries?from=0&size=2&search=foo&orderBy=bar").should().
                respond(200).
                haveType("application/json").
                contain("{\"query\":1");
    }

    private void testTripleQuote(Boolean phraseMatch, String query, String tripleQuoteResult) {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x").
                        addField("name", "my batch search").
                        addFile(
                                new FileUpload("csvFile").withFilename("search.csv").withContentType("text/csv").withContent(query +
                                        "\"query two\"\r\n" +
                                        "query three\r\n" +
                                        "query\" four\r\n")).
                        addField("phrase_matches", String.valueOf(phraseMatch)).
                        build()).response();


        assertThat(response.code()).isEqualTo(200);
        ArgumentCaptor<BatchSearch> argument = ArgumentCaptor.forClass(BatchSearch.class);
        verify(batchSearchRepository).save(argument.capture());
        assertThat(argument.getValue().queries.keySet()).containsOnly(tripleQuoteResult, "\"query two\"", "query three", "query\" four");
    }

    @Before
    public void setUp() {
        initMocks(this);
        taskManager = new TaskManagerMemory(factory, new PropertiesProvider());
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(mock(BatchSearchRunner.class));
        configure(routes -> routes.add(new BatchSearchResource(new PropertiesProvider(), taskManager, batchSearchRepository)).
                filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }

    private static class MultipartContentBuilder {
        private final String boundary;
        private final List<Pair<String, String>> nameValuePairs = new LinkedList<>();
        private final List<FileUpload> files = new LinkedList<>();

        public MultipartContentBuilder(String boundary) {
            this.boundary = boundary;
        }

        public String build() {
            String fields = nameValuePairs.stream().map(nv -> format(
                    "--%s\r\n" +
                        "Content-Disposition: form-data; name=\"%s\"\r\n" +
                        "\r\n" +
                        "%s\r\n", boundary,
                    nv._1(), nv._2())).collect(Collectors.joining());
            String fileUploads = files.stream().map(fu -> format("--%s\r\n%s", boundary, fu.build())).collect(Collectors.joining());
            return fields + fileUploads + format("--%s--", boundary);
        }

        public MultipartContentBuilder addField(String name, String value) {
            nameValuePairs.add(new Pair<>(name, value));
            return this;
        }

        public MultipartContentBuilder addFile(FileUpload file) {
            files.add(file);
            return this;
        }
    }

    private static class FileUpload {
        private final String name;
        private String contentType;
        private String content;
        private String filename;

        FileUpload(String name) {
            this.name = name;
        }

        public FileUpload withContentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public FileUpload withContent(String content) {
            this.content = content;
            return this;
        }

        public String build() {
            return format("Content-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n" +
                                    "Content-Type: %s\r\n" +
                                    "\r\n" +
                                    "%s", name, filename, contentType, content);
        }

        public FileUpload withFilename(String filename) {
            this.filename = filename;
            return this;
        }
    }
}
