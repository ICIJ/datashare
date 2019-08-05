package org.icij.datashare.web;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.Response;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchSearchResourceTest implements FluentRestTest {
    @Mock BatchSearchRepository batchSearchRepository;
    private static WebServer server = new WebServer() {
            @Override
            protected Env createEnv() {
                return Env.prod();
            }
        }.startOnRandomPort();

    @Test
    public void test_upload_batch_search_csv_with_bad_parts_number() throws SQLException {
        when(batchSearchRepository.save(any(), any())).thenReturn(true);

        postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x", "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"name\"\r\n" +
                "\r\n" +
                "value\r\n" +
                "--AaB03x--").
                should().respond(400);
    }

    @Test
        public void test_upload_batch_search_csv_with_bad_names() throws SQLException {
        when(batchSearchRepository.save(any(), any())).thenReturn(true);

        postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x", "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"name\"\r\n" +
                "\r\n" +
                "my batch search\r\n" +
                "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"desc\"\r\n" +
                "\r\n" +
                "search description\r\n" +
                "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"files\"; filename=\"search.csv\"\r\n" +
                "\r\n" +
                "query\r\n" +
                "--AaB03x--").
                should().respond(400);
    }

    @Test
    public void test_upload_batch_search_csv() throws SQLException {
        when(batchSearchRepository.save(any(), any())).thenReturn(true);

        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x", "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"name\"\r\n" +
                "\r\n" +
                "my batch search\r\n" +
                "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"description\"\r\n" +
                "\r\n" +
                "search description\r\n" +
                "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"csvFile\"; filename=\"search.csv\"\r\n" +
                "Content-Type: text/csv\r\n" +
                "\r\n" +
                "query one\n" +
                "query two\r\n" +
                "query three\r\n" +
                "--AaB03x--").response();

        assertThat(response.code()).isEqualTo(200);
        verify(batchSearchRepository).save(any(), eq(new BatchSearch(response.content(),
                project("prj"), "my batch search", "search description",
                asList("query one", "query two", "query three"), new Date(), BatchSearch.State.RUNNING)));
    }

    @Test
    public void test_upload_batch_search_csv_less_that_2chars_queries_are_filtered() throws SQLException {
        when(batchSearchRepository.save(any(), any())).thenReturn(true);

        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x", "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"name\"\r\n" +
                "\r\n" +
                "my batch search\r\n" +
                "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"description\"\r\n" +
                "\r\n" +
                "search description\r\n" +
                "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"csvFile\"; filename=\"search.csv\"\r\n" +
                "Content-Type: text/csv\r\n" +
                "\r\n" +
                "1\n" +
                "\n" +
                "query\r\n" +
                "--AaB03x--").response();

        assertThat(response.code()).isEqualTo(200);
        verify(batchSearchRepository).save(any(), eq(new BatchSearch(response.content(),
                project("prj"), "my batch search", "search description",
                singletonList("query"), new Date(), BatchSearch.State.RUNNING)));
    }

    @Test
    public void test_get_batch_search() {
        BatchSearch search1 = new BatchSearch(project("prj"), "name1", "description1", asList("query 1", "query 2"));
        BatchSearch search2 = new BatchSearch(project("prj"), "name2", "description2", asList("query 3", "query 4"));
        when(batchSearchRepository.get(User.local(), search1.uuid)).thenReturn(search1);
        when(batchSearchRepository.get(User.local(), search2.uuid)).thenReturn(search2);

        get("/api/batch/search/" + search1.uuid).should().respond(200).haveType("application/json").contain("\"name\":\"name1\"");
        get("/api/batch/search/" + search2.uuid).should().respond(200).haveType("application/json").contain("\"name\":\"name2\"");
    }

    @Test
    public void test_get_batch_searches_json() {
        when(batchSearchRepository.get(User.local())).thenReturn(asList(
                new BatchSearch(project("prj"), "name1", "description1", asList("query 1", "query 2")),
                new BatchSearch(project("prj"), "name2", "description2", asList("query 3", "query 4"))
        ));

        get("/api/batch/search").should().respond(200).haveType("application/json").
                contain("\"name\":\"name1\"").
                contain("\"name\":\"name2\"");
    }

    @Test
    public void test_get_search_results_json() {
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", 0, 0)).thenReturn(asList(
                new SearchResult("q1", "docId1", "rootId1", Paths.get("/path/to/doc1"), new Date(), 1),
                new SearchResult("q2", "docId2", "rootId2", Paths.get("/path/to/doc2"), new Date(), 2)
        ));

        get("/api/batch/search/result/batchSearchId").
                should().respond(200).haveType("application/json").
                contain("\"documentId\":\"docId1\"").
                contain("\"documentId\":\"docId2\"");
    }

    @Test
    public void test_get_search_results_json_paginated() {
        List<SearchResult> results = IntStream.range(0, 10).
                mapToObj(i -> new SearchResult("q" + i, "docId" + i, "rootId" + i,
                        Paths.get("/path/to/doc" + i), new Date(), i)).collect(toList());
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", 5, 0)).thenReturn(results.subList(0, 5));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", 2, 9)).thenReturn(results.subList(8, 10));

        get("/api/batch/search/result/batchSearchId?size=5").should().
                contain("\"documentId\":\"docId0\"").
                contain("\"documentId\":\"docId4\"").
                should().not().contain("\"documentId\":\"docId5\"");
        get("/api/batch/search/result/batchSearchId?size=2&from=9").should().
                contain("\"documentId\":\"docId8\"").
                contain("\"documentId\":\"docId9\"").
                not().contain("\"documentId\":\"docId7\"");
    }

    @Test
    public void test_get_search_results_csv() {
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", 0, 0)).thenReturn(asList(
                new SearchResult("q1","docId1", "rootId1", Paths.get("/path/to/doc1"), new Date(), 1),
                new SearchResult("q2","docId2", "rootId2", Paths.get("/path/to/doc2"), new Date(), 2)
        ));

        get("/api/batch/search/result/csv/batchSearchId").
                should().respond(200).haveType("text/csv").
                haveHeader("Content-Disposition", "attachment;filename=\"batchSearchId.csv\"").
                contain("\"docId1\",\"rootId1\"").
                contain("\"docId2\",\"rootId2\"");
    }

    @Test
    public void test_get_search_results_unauthorized_user() {
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", 0, 0)).
                thenThrow(new JooqBatchSearchRepository.UnauthorizedUserException("batchSearchId", "owner", "actual"));

        get("/api/batch/search/result/csv/batchSearchId").should().respond(401);
        get("/api/batch/search/result/batchSearchId").should().respond(401);
    }

    @Test
    public void test_delete_batch_search() {
        when(batchSearchRepository.deleteBatchSearches(User.local())).thenReturn(true).thenReturn(false);

        delete("/api/batch/search").should().respond(204);
        delete("/api/batch/search").should().respond(404);
    }

    @Before
    public void setUp() {
        initMocks(this);
        server.configure(routes -> routes.add(new BatchSearchResource(batchSearchRepository)).
                filter(new LocalUserFilter(new PropertiesProvider())));
    }

    @Override public int port() { return server.port();}
}
