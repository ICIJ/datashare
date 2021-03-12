package org.icij.datashare.web;

import net.codestory.rest.Response;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.function.Pair;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
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

public class BatchSearchResourceTest extends AbstractProdWebServerTest {
    @Mock BatchSearchRepository batchSearchRepository;
    BlockingQueue<String> batchSearchQueue = new ArrayBlockingQueue<>(5);

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
    public void test_upload_batch_search_csv_with_name_and_csvfile_should_send_OK() throws InterruptedException {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
            new MultipartContentBuilder("AaB03x")
                    .addField("name","nameValue")
                    .addFile(new FileUpload("csvFile").withContent("query\r\néèàç\r\n")).build()).response();
        assertThat(response.code()).isEqualTo(200);
        BatchSearch expected = new BatchSearch(response.content(),
                project("prj"), "nameValue", null,
                asSet("query", "éèàç"), new Date(), BatchSearch.State.QUEUED, User.local());
        verify(batchSearchRepository).save(eq(expected));
        assertThat(batchSearchQueue.take()).isEqualTo(expected.uuid);
    }

    @Test
    public void test_upload_batch_search_csv_triple_double_quote_match_phrases_false() {
        testTripleQuote(false, "\"query one\"");
    }

    @Test
    public void test_upload_batch_search_csv_triple_double_quote_match_phrases_true(){
        testTripleQuote(true, "\"\"\"query one\"\"\"");
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
                .addField("paths","/path/to/document")
                .addField("paths","/other/path/")
                .addField("fuzziness",String.valueOf(4))
                .addField("phrase_matches",String.valueOf(true)).build()).response();

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
    public void test_rerun_batch_search_not_found() {
        post("/api/batch/search/copy/bad_uuid", "{}").should().respond(404);
    }

    @Test
    public void test_rerun_batch_search() throws InterruptedException {
        BatchSearch sourceSearch = new BatchSearch(project("prj"), "name", "description1", asSet("query 1", "query 2"), User.local());
        when(batchSearchRepository.get(User.local(), sourceSearch.uuid)).thenReturn(sourceSearch);
        when(batchSearchRepository.save(any())).thenReturn(true);

        post("/api/batch/search/copy/" + sourceSearch.uuid,
                "{\"project\":\"prj\", \"name\": \"test\", \"description\": \"test description\"}").
                should().respond(200);

        ArgumentCaptor<BatchSearch> argument = ArgumentCaptor.forClass(BatchSearch.class);
        verify(batchSearchRepository).save(argument.capture());
        assertThat(argument.getValue().name).isEqualTo("test");
        assertThat(argument.getValue().description).isEqualTo("test description");
        assertThat(argument.getValue().project.name).isEqualTo("prj");
        assertThat(argument.getValue().queries).isEqualTo(sourceSearch.queries);
        assertThat(argument.getValue().user).isEqualTo(sourceSearch.user);

        assertThat(argument.getValue().state).isEqualTo(BatchSearchRecord.State.QUEUED);
        assertThat(batchSearchQueue.take()).isEqualTo(argument.getValue().uuid);
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
        when(batchSearchRepository.getRecords(User.local(), singletonList("local-datashare"))).thenReturn(asList(
                new BatchSearch(project("prj"), "name1", "description1", asSet("query 1", "query 2"), User.local()),
                new BatchSearch(project("prj"), "name2", "description2", asSet("query 3", "query 4"), User.local())
        ));

        get("/api/batch/search").should().respond(200).haveType("application/json").
                contain("\"name\":\"name1\"").
                contain("\"name\":\"name2\"");
    }

    @Test
    public void test_get_batch_searches_records_json_paginated() {
        List<BatchSearchRecord> batchSearches = IntStream.range(0, 10).mapToObj(i -> new BatchSearchRecord(project("prj"), "name" + i, "description" + i, 2, new Date())).collect(toList());
        when(batchSearchRepository.getRecords(User.local(), singletonList("local-datashare"), new BatchSearchRepository.WebQuery(2, 0))).thenReturn(batchSearches.subList(0, 2));
        when(batchSearchRepository.getRecords(User.local(), singletonList("local-datashare"), new BatchSearchRepository.WebQuery(3, 4))).thenReturn(batchSearches.subList(5, 8));
        when(batchSearchRepository.getTotal(User.local(), singletonList("local-datashare"), new BatchSearchRepository.WebQuery(2,0))).thenReturn(batchSearches.size());
        when(batchSearchRepository.getTotal(User.local(), singletonList("local-datashare"), new BatchSearchRepository.WebQuery(3,4))).thenReturn(batchSearches.size());

        post("/api/batch/search", "{\"from\":0, \"size\":2, \"query\":\"*\", \"field\":\"all\"}").should().respond(200).haveType("application/json").
                contain("\"name\":\"name0\"").
                contain("\"name\":\"name1\"").
                contain("\"total\":10").
                should().not().contain("name2");

        post("/api/batch/search", "{\"from\":4, \"size\":3, \"query\":\"*\", \"field\":\"all\"}").should().respond(200).haveType("application/json").
                contain("\"name\":\"name5\"").
                contain("\"name\":\"name6\"").
                contain("\"name\":\"name7\"").
                contain("\"total\":10").
                should().not().contain("name8").
                should().not().contain("name4");
    }

    @Test
    public void test_get_search_results_json() {
        when(batchSearchRepository.getResults(eq(User.local()), eq("batchSearchId"), any())).thenReturn(asList(
                new SearchResult("q1", "docId1", "rootId1", Paths.get("/path/to/doc1"), new Date(), "content/type", 123L, 1),
                new SearchResult("q2", "docId2", "rootId2", Paths.get("/path/to/doc2"), new Date(), "content/type", 123L, 2)
        ));

        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":0, \"query\":\"*\", \"field\":\"all\"}").
                should().respond(200).haveType("application/json").
                contain("\"documentId\":\"docId1\"").
                contain("\"documentId\":\"docId2\"");
    }

    @Test
    public void test_get_search_results_json_paginated() {
        List<SearchResult> results = IntStream.range(0, 10).
                mapToObj(i -> new SearchResult("q" + i, "docId" + i, "rootId" + i,
                        Paths.get("/path/to/doc" + i), new Date(), "content/type", 123L, i)).collect(toList());
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", new BatchSearchRepository.WebQuery(5, 0))).thenReturn(results.subList(0, 5));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", new BatchSearchRepository.WebQuery(2, 9))).thenReturn(results.subList(8, 10));

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
    public void test_get_search_results_csv() {
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(project("prj"), "name", "desc", asSet("q1", "q2"),User.local()));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", new BatchSearchRepository.WebQuery())).thenReturn(asList(
                new SearchResult("q1", "docId1", "rootId1", Paths.get("/path/to/doc1"), new Date(), "content/type", 123L, 1),
                new SearchResult("q2", "docId2", "rootId2", Paths.get("/path/to/doc2"), new Date(), "content/type", 123L, 2)
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
            routes.add(new BatchSearchResource(batchSearchRepository, batchSearchQueue, propertiesProvider)).
                    filter(new LocalUserFilter(propertiesProvider));
        });
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(project("prj"), "name", "desc", asSet("q"), User.local()));
        when(batchSearchRepository.getResults(User.local(), "batchSearchId", new BatchSearchRepository.WebQuery())).thenReturn(singletonList(
                new SearchResult("q", "docId", "rootId", Paths.get("/path/to/doc"), new Date(), "content/type", 123L, 1)
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
        post("/api/batch/search/result/batchSearchId", "{\"from\":0, \"size\":0, \"query\":\"*\", \"field\":\"all\"}").should().respond(401);
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

    @Test
    public void test_get_queries_json() {
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(project("prj"), "name", "desc", asSet("q1", "q2"),User.local()));
        get("/api/batch/search/batchSearchId/queries").should().
                respond(200).
                haveType("application/json;charset=UTF-8").
                contain("[\"q1\",\"q2\"]");
    }

    @Test
    public void test_get_queries_csv() {
        when(batchSearchRepository.get(User.local(), "batchSearchId")).thenReturn(new BatchSearch(project("prj"), "name", "desc", asSet("q1", "q2"),User.local()));
        get("/api/batch/search/batchSearchId/queries?format=csv").should().
                respond(200).
                haveType("text/csv;charset=UTF-8").
                contain("q1\nq2");
    }

    private void testTripleQuote(Boolean phraseMatch, String tripleQuoteResult) {
        when(batchSearchRepository.save(any())).thenReturn(true);
        Response response = postRaw("/api/batch/search/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x").
                        addField("name", "my batch search").
                        addFile(
                                new FileUpload("csvFile").withFilename("search.csv").withContentType("text/csv").withContent("\"\"\"query one\"\"\"\n" +
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
        configure(routes -> routes.add(new BatchSearchResource(batchSearchRepository, batchSearchQueue, new PropertiesProvider())).
                filter(new LocalUserFilter(new PropertiesProvider())));
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
