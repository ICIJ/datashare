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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchSearchResourceTest implements FluentRestTest {
    @Mock
    BatchSearchRepository batchSearchRepository;
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();

    @Test
    public void test_upload_batch_search_csv_without_name_should_send_bad_request() {
        when(batchSearchRepository.save(any())).thenReturn(true);

        postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                "--AaB03x\r\n" +
                        "Content-Disposition: form-data;name=\"csvFile\"\r\n" +
                        "\r\n" +
                        "value\r\n" +
                        "--AaB03x--").
                should().respond(400);
    }

    @Test
    public void test_upload_batch_search_csv_without_csvFile_should_send_bad_request() {
        when(batchSearchRepository.save(any())).thenReturn(true);

        postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"name\"\r\n" +
                        "\r\n" +
                        "value\r\n" +
                        "--AaB03x--").
                should().respond(400);
    }

    @Test
    public void test_upload_batch_search_csv_with_name_and_csvfile_should_send_OK() {
        when(batchSearchRepository.save(any())).thenReturn(true);

        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"name\"\r\n" +
                        "\r\n" +
                        "nameValue\r\n" +
                        "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"csvFile\"\r\n" +
                        "\r\n" +
                        "query\r\n" +
                        "--AaB03x--").response();
        assertThat(response.code()).isEqualTo(200);
        verify(batchSearchRepository).save(eq(new BatchSearch(response.content(),
                project("prj"), "nameValue", null,
                asSet("query"), new Date(), BatchSearch.State.QUEUED, User.local())));
    }

    @Test
    public void test_upload_batch_search_csv_with_all_parameters()  {
        when(batchSearchRepository.save(any())).thenReturn(true);

        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                "--AaB03x\r\n" +
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
                        "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"published\"\r\n" +
                        "\r\n" +
                        "True\r\n" +
                        "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"fileTypes\"\r\n" +
                        "\r\n" +
                        "application/pdf\r\n" +
                        "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"fileTypes\"\r\n" +
                        "\r\n" +
                        "image/jpeg\r\n" +
                        "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"paths\"\r\n" +
                        "\r\n" +
                        "/path/to/document\r\n" +
                        "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"paths\"\r\n" +
                        "\r\n" +
                        "/other/path/\r\n" +
                        "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"fuzziness\"\r\n" +
                        "\r\n" +
                        "4\r\n" +
                        "--AaB03x\r\n" +
                        "Content-Disposition: form-data; name=\"phrase_matches\"\r\n" +
                        "\r\n" +
                        "True\r\n" +
                        "--AaB03x--").response();

        assertThat(response.code()).isEqualTo(200);
        ArgumentCaptor<BatchSearch> argument = ArgumentCaptor.forClass(BatchSearch.class);
        verify(batchSearchRepository).save(argument.capture());
        assertThat(argument.getValue().published).isTrue();
        assertThat(argument.getValue().fileTypes).containsExactly("application/pdf", "image/jpeg");
        assertThat(argument.getValue().paths).containsExactly("/path/to/document", "/other/path/");
        assertThat(argument.getValue().fuzziness).isEqualTo(4);
        assertThat(argument.getValue().phraseMatches).isTrue();
        assertThat(argument.getValue().user).isEqualTo(User.local());
        assertThat(argument.getValue().description).isEqualTo("search description");
        Iterator<String> iterator = argument.getValue().queries.keySet().iterator();
        assertThat(iterator.next()).isEqualTo("query one");
        assertThat(iterator.next()).isEqualTo("query two");
        assertThat(iterator.next()).isEqualTo("query three");
        assertThat(iterator.hasNext()).isFalse();
    }


    @Test
    public void test_upload_batch_search_csv_less_that_2chars_queries_are_filtered() throws SQLException {
        when(batchSearchRepository.save(any())).thenReturn(true);

        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                "--AaB03x\r\n" +
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
        verify(batchSearchRepository).save(eq(new BatchSearch(response.content(),
                project("prj"), "my batch search", "search description",
                asSet("query"), new Date(), BatchSearch.State.RUNNING, User.local())));
    }

    @Test
    public void test_get_batch_search() {
        BatchSearch search1 = new BatchSearch(project("prj"), "name1", "description1", asSet("query 1", "query 2"), User.local());
        BatchSearch search2 = new BatchSearch(project("prj"), "name2", "description2", asSet("query 3", "query 4"), User.local());
        when(batchSearchRepository.get(User.local(), search1.uuid)).thenReturn(search1);
        when(batchSearchRepository.get(User.local(), search2.uuid)).thenReturn(search2);

        get("/api/batch/search/" + search1.uuid).should().respond(200).haveType("application/json").contain("\"name\":\"name1\"");
        get("/api/batch/search/" + search2.uuid).should().respond(200).haveType("application/json").contain("\"name\":\"name2\"");
    }

    @Test
    public void test_get_batch_searches_json() {
        when(batchSearchRepository.get(User.local(), singletonList("local-datashare"))).thenReturn(asList(
                new BatchSearch(project("prj"), "name1", "description1", asSet("query 1", "query 2"), User.local()),
                new BatchSearch(project("prj"), "name2", "description2", asSet("query 3", "query 4"), User.local())
        ));

        get("/api/batch/search").should().respond(200).haveType("application/json").
                contain("\"name\":\"name1\"").
                contain("\"name\":\"name2\"");
    }

    @Test
    public void test_get_search_results_json() {
        when(batchSearchRepository.getResults(eq(User.local()), eq("batchSearchId"), any())).thenReturn(asList(
                new SearchResult("q1", "docId1", "rootId1", "doc1", new Date(), "content/type", 123L, 1),
                new SearchResult("q2", "docId2", "rootId2", "doc2", new Date(), "content/type", 123L, 2)
        ));

        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":0}").
                should().respond(200).haveType("application/json").
                contain("\"documentId\":\"docId1\"").
                contain("\"documentId\":\"docId2\"");
    }

    @Test
    public void test_get_search_results_json_paginated() {
        List<SearchResult> results = IntStream.range(0, 10).
                mapToObj(i -> new SearchResult("q" + i, "docId" + i, "rootId" + i,
                        "/path/to/doc" + i, new Date(), "content/type", 123L, i)).collect(toList());
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", new BatchSearchRepository.WebQuery(5, 0))).thenReturn(results.subList(0, 5));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", new BatchSearchRepository.WebQuery(2, 9))).thenReturn(results.subList(8, 10));

        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":5}").should().
                contain("\"documentId\":\"docId0\"").
                contain("\"documentId\":\"docId4\"").
                should().not().contain("\"documentId\":\"docId5\"");
        post("/api/batch/search/result/batchSearchId", "{\"from\":9, \"size\":2}").should().
                contain("\"documentId\":\"docId8\"").
                contain("\"documentId\":\"docId9\"").
                not().contain("\"documentId\":\"docId7\"");
    }

    @Test
    public void test_get_search_results_csv() {
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(project("prj"), "name", "desc", asSet("q1", "q2"),User.local()));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", new BatchSearchRepository.WebQuery())).thenReturn(asList(
                new SearchResult("q1", "docId1", "rootId1", "doc1", new Date(), "content/type", 123L, 1),
                new SearchResult("q2", "docId2", "rootId2", "doc2", new Date(), "content/type", 123L, 2)
        ));

        get("/api/batch/search/result/csv/batchSearchId").
                should().respond(200).haveType("text/csv").
                haveHeader("Content-Disposition", "attachment;filename=\"batchSearchId.csv\"").
                contain(format("\"localhost:%d/#/d/prj/docId1/rootId1\",\"docId1\",\"rootId1\"", port())).
                contain(format("\"localhost:%d/#/d/prj/docId2/rootId2\",\"docId2\",\"rootId2\"", port()));
    }

    @Test
    public void test_get_search_results_csv_with_url_prefix_parameter() {
        server.configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
                put("rootHost", "http://foo.com:12345");
            }});
            routes.add(new BatchSearchResource(batchSearchRepository, propertiesProvider)).
                    filter(new LocalUserFilter(propertiesProvider));
        });
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(project("prj"), "name", "desc", asSet("q"), User.local()));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", new BatchSearchRepository.WebQuery())).thenReturn(singletonList(
                new SearchResult("q", "docId", "rootId", "doc", new Date(), "content/type", 123L, 1)
        ));

        get("/api/batch/search/result/csv/batchSearchId").should().respond(200).haveType("text/csv").
                haveHeader("Content-Disposition", "attachment;filename=\"batchSearchId.csv\"").
                contain("\"http://foo.com:12345/#/d/prj/docId/rootId\",\"docId\",\"rootId\"");
    }

    @Test
    public void test_get_search_results_unauthorized_user() {
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", new BatchSearchRepository.WebQuery(0, 0))).
                thenThrow(new JooqBatchSearchRepository.UnauthorizedUserException("batchSearchId", "owner", "actual"));

        get("/api/batch/search/result/csv/batchSearchId").should().respond(401);
        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":0}").should().respond(401);
    }

    @Test
    public void test_delete_batch_search() {
        when(batchSearchRepository.deleteAll(User.local())).thenReturn(true).thenReturn(false);

        delete("/api/batch/search").should().respond(204);
        delete("/api/batch/search").should().respond(404);
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

        delete("/api/batch/search/unknownid").should().respond(404);
        delete("/api/batch/search/myid").should().respond(204);
        delete("/api/batch/search/myid").should().respond(404);
    }

    @Before
    public void setUp() {
        initMocks(this);
        server.configure(routes -> routes.add(new BatchSearchResource(batchSearchRepository, new PropertiesProvider())).
                filter(new LocalUserFilter(new PropertiesProvider())));
    }

    @Override
    public int port() { return server.port();}
}
