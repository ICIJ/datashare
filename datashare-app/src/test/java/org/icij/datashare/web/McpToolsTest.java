package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.policies.TaskPolicyChecker;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.ExtractedText;
import org.icij.datashare.utils.DocumentSourceAccess;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.TaskFinder;
import org.icij.datashare.text.Tag;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class McpToolsTest extends AbstractProdWebServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Mock Repository repository;
    @Mock Indexer indexer;
    @Mock TaskManager taskManager;
    @Mock ProjectAdminService projectAdminService;
    @Mock BatchSearchRepository batchSearchRepository;
    private final PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("mode", Mode.SERVER.name()));

    @Before
    public void setUp() {
        initMocks(this);
    }

    private void serveAs(String login, String... projects) {
        TaskFinder taskFinder = new TaskFinder(taskManager, batchSearchRepository);
        ProjectResource projectResource = new ProjectResource(repository, indexer, taskManager, propertiesProvider,
                                                              projectAdminService);
        DocumentResource documentResource = new DocumentResource(repository, indexer, propertiesProvider,
                                                                 new DocumentSourceAccess(repository, indexer,
                                                                                          propertiesProvider));
        TaskResource taskResource = new TaskResource(mock(DatashareTaskFactory.class), taskManager, propertiesProvider,
                                                     batchSearchRepository, taskFinder);
        McpResource mcp = new McpResource(Set.of(new McpListProjectsTool(projectResource),
                                                 new McpSearchDocumentsTool(indexer),
                                                 new McpGetDocumentTool(documentResource),
                                                 new McpListTasksTool(taskResource),
                                                 new McpStopTaskTool(taskResource, taskManager,
                                                                     TaskPolicyChecker.ALLOW_ALL),
                                                 new McpTagDocumentsTool(documentResource),
                                                 new McpUntagDocumentsTool(documentResource),
                                                 new McpListTagsTool(documentResource)));
        configure(routes -> routes.add(mcp).filter(
                new BasicAuthFilter("/", "icij", DatashareUser.singleUser(localUser(login, projects)))));
    }

    private JsonNode call(String login, String tool, String arguments) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                      + "\",\"arguments\":" + arguments + "}}";
        return MAPPER.readTree(post("/api/mcp", body).withPreemptiveAuthentication(login, "").response().content());
    }

    private JsonNode payload(JsonNode response) throws Exception {
        assertThat(response.at("/result/isError").asBoolean()).as(response.toString()).isFalse();
        return MAPPER.readTree(response.at("/result/content/0/text").asText());
    }

    @Test
    public void test_list_projects_returns_only_the_callers_projects() throws Exception {
        serveAs("local", "foo");
        when(repository.getProjects(eq(List.of("foo")))).thenReturn(List.of(new Project("foo")));
        when(repository.getProjects()).thenReturn(List.of(new Project("foo"), new Project("bar")));

        JsonNode projects = payload(call("local", "list_projects", "{}"));

        assertThat(projects.size()).isEqualTo(1);
        assertThat(projects.at("/0/name").asText()).isEqualTo("foo");
    }

    private static final String ES_RESPONSE = """
            {"hits":{"total":{"value":1,"relation":"eq"},"hits":[{"_id":"doc1","_routing":"root1",
              "_source":{"path":"/data/a.pdf","contentType":"application/pdf"},
              "highlight":{"content":["a <em>leak</em> here"]}}]}}""";

    @Test
    public void test_search_on_granted_project_returns_trimmed_hits() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), any())).thenReturn(ES_RESPONSE);

        JsonNode result = payload(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\"}"));

        assertThat(result.get("total").asLong()).isEqualTo(1);
        assertThat(result.get("relation").asText()).isEqualTo("eq");
        assertThat(result.at("/hits/0/id").asText()).isEqualTo("doc1");
        assertThat(result.at("/hits/0/routing").asText()).isEqualTo("root1");
        assertThat(result.at("/hits/0/path").asText()).isEqualTo("/data/a.pdf");
        assertThat(result.at("/hits/0/contentType").asText()).isEqualTo("application/pdf");
        assertThat(result.at("/hits/0/highlights/0").asText()).isEqualTo("a <em>leak</em> here");
    }

    @Test
    public void test_search_sends_query_string_filtered_on_documents() throws Exception {
        serveAs("local", "foo");
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), body.capture())).thenReturn(ES_RESPONSE);

        call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\",\"size\":5}");

        JsonNode sent = MAPPER.readTree(body.getValue());
        assertThat(sent.get("size").asInt()).isEqualTo(5);
        assertThat(sent.at("/query/bool/must/query_string/query").asText()).isEqualTo("leak");
        assertThat(sent.at("/query/bool/filter/term/type").asText()).isEqualTo("Document");
        assertThat(sent.at("/highlight/fields/content/max_analyzed_offset").asInt()).isEqualTo(999999);
        assertThat(sent.at("/highlight/fields/content/fragment_size").asInt()).isEqualTo(280);
        assertThat(sent.at("/highlight/fields/content/number_of_fragments").asInt()).isEqualTo(2);
    }

    @Test
    public void test_search_pins_the_pages_to_one_shard_copy_per_user() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), any())).thenReturn(ES_RESPONSE);

        JsonNode response = call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\"}");

        assertThat(response.at("/result/isError").asBoolean()).isFalse();
        org.mockito.Mockito.verify(indexer).executeRaw(eq("POST"), eq("foo/_search?preference=local"), any());
    }

    @Test
    public void test_search_with_failed_shards_is_an_error() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), any()))
                .thenReturn("{\"_shards\":{\"failed\":1},\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        JsonNode response = call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\"}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("search failed on 1 shard(s)");
    }

    private static org.elasticsearch.client.ResponseException elasticsearchError(int status) throws Exception {
        org.elasticsearch.client.Response response = mock(org.elasticsearch.client.Response.class);
        when(response.getRequestLine()).thenReturn(
                new org.apache.http.message.BasicRequestLine("POST", "/foo/_search",
                                                             org.apache.http.HttpVersion.HTTP_1_1));
        when(response.getHost()).thenReturn(new org.apache.http.HttpHost("elasticsearch", 9200));
        when(response.getStatusLine()).thenReturn(
                new org.apache.http.message.BasicStatusLine(org.apache.http.HttpVersion.HTTP_1_1, status, "error"));
        return new org.elasticsearch.client.ResponseException(response);
    }

    @Test
    public void test_search_with_a_rejected_query_is_an_invalid_argument() throws Exception {
        serveAs("local", "foo");
        org.elasticsearch.client.ResponseException rejected = elasticsearchError(400);
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), any())).thenThrow(rejected);

        JsonNode response = call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"foo AND\"}");

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(response.toString()).excludes("elasticsearch:9200");
    }

    @Test
    public void test_search_with_a_cluster_failure_is_a_tool_error() throws Exception {
        serveAs("local", "foo");
        org.elasticsearch.client.ResponseException unavailable = elasticsearchError(503);
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), any())).thenThrow(unavailable);

        JsonNode response = call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\"}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.toString()).excludes("elasticsearch:9200");
    }

    @Test
    public void test_search_on_ungranted_project_is_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "search_documents", "{\"project\":\"bar\",\"query\":\"leak\"}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
        verifyNoInteractions(indexer);
    }

    @Test
    public void test_search_ignores_the_mcp_request_query_string() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), any())).thenReturn(ES_RESPONSE);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_documents\","
                      + "\"arguments\":{\"project\":\"foo\",\"query\":\"leak\"}}}";

        post("/api/mcp?q=x", body).withPreemptiveAuthentication("local", "").response();

        verify(indexer).executeRaw(eq("POST"), eq("foo/_search?preference=local"), any());
    }

    @Test
    public void test_search_rejects_project_with_a_path() throws Exception {
        serveAs("local", "foo");

        assertThat(call("local", "search_documents", "{\"project\":\"_search/scroll\",\"query\":\"x\"}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "search_documents", "{\"project\":\"foo,bar\",\"query\":\"x\"}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "search_documents", "{\"project\":\"foo/_doc/1\",\"query\":\"x\"}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        verifyNoInteractions(indexer);
    }

    @Test
    public void test_search_size_above_50_is_invalid() throws Exception {
        serveAs("local", "foo");
        assertThat(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"x\",\"size\":51}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
    }

    @Test
    public void test_search_sends_from() throws Exception {
        serveAs("local", "foo");
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), body.capture())).thenReturn(ES_RESPONSE);

        call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\",\"size\":5,\"from\":20}");

        assertThat(MAPPER.readTree(body.getValue()).get("from").asInt()).isEqualTo(20);
    }

    @Test
    public void test_search_without_from_starts_at_the_first_hit() throws Exception {
        serveAs("local", "foo");
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), body.capture())).thenReturn(ES_RESPONSE);

        JsonNode result = payload(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\"}"));

        assertThat(MAPPER.readTree(body.getValue()).get("from").asInt()).isEqualTo(0);
        assertThat(result.get("total").asLong()).isEqualTo(1);
        assertThat(result.at("/hits/0/id").asText()).isEqualTo("doc1");
    }

    @Test
    public void test_search_on_the_last_answerable_page_is_accepted() throws Exception {
        serveAs("local", "foo");
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), body.capture())).thenReturn(ES_RESPONSE);

        JsonNode response = call("local", "search_documents",
                                 "{\"project\":\"foo\",\"query\":\"leak\",\"from\":9990,\"size\":10}");

        assertThat(response.has("error")).isFalse();
        assertThat(MAPPER.readTree(body.getValue()).get("from").asInt()).isEqualTo(9990);
    }

    @Test
    public void test_search_past_the_result_window_is_invalid() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "search_documents",
                                 "{\"project\":\"foo\",\"query\":\"leak\",\"from\":9999,\"size\":10}");

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(response.at("/error/message").asText()).isEqualTo("invalid argument: from + size");
        org.mockito.Mockito.verifyNoInteractions(indexer);
    }

    @Test
    public void test_search_names_the_pair_when_size_is_what_overflows_the_window() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "search_documents",
                                 "{\"project\":\"foo\",\"query\":\"leak\",\"from\":9960,\"size\":50}");

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(response.at("/error/message").asText()).isEqualTo("invalid argument: from + size");
        org.mockito.Mockito.verifyNoInteractions(indexer);
    }

    @Test
    public void test_search_from_above_the_result_window_is_invalid() throws Exception {
        serveAs("local", "foo");

        assertThat(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"x\",\"from\":10001}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"x\",\"from\":10000}")
                           .at("/error/message").asText()).isEqualTo("invalid argument: from");
        org.mockito.Mockito.verifyNoInteractions(indexer);
    }

    @Test
    public void test_search_with_a_negative_or_fractional_from_is_invalid() throws Exception {
        serveAs("local", "foo");

        assertThat(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"x\",\"from\":-1}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"x\",\"from\":1.5}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"x\",\"from\":20.9}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        org.mockito.Mockito.verifyNoInteractions(indexer);
    }

    @Test
    public void test_search_accepts_a_whole_number_written_as_a_float() throws Exception {
        serveAs("local", "foo");
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), body.capture())).thenReturn(ES_RESPONSE);

        call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\",\"size\":20.0,\"from\":10.0}");

        JsonNode sent = MAPPER.readTree(body.getValue());
        assertThat(sent.get("size").asInt()).isEqualTo(20);
        assertThat(sent.get("from").asInt()).isEqualTo(10);
    }

    @Test
    public void test_search_says_when_the_total_is_a_lower_bound() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search?preference=local"), any()))
                .thenReturn("{\"hits\":{\"total\":{\"value\":10000,\"relation\":\"gte\"},\"hits\":[]}}");

        JsonNode result = payload(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\"}"));

        assertThat(result.get("total").asLong()).isEqualTo(10000);
        assertThat(result.get("relation").asText()).isEqualTo("gte");
    }

    @Test
    public void test_search_without_query_is_invalid() throws Exception {
        serveAs("local", "foo");
        assertThat(call("local", "search_documents", "{\"project\":\"foo\"}").at("/error/code").asInt())
                .isEqualTo(-32602);
    }

    private Document doc(String content) {
        return DocumentBuilder.createDoc("doc1").with(content).with(java.nio.file.Paths.get("/data/a.pdf"))
                              .ofContentType("application/pdf").build();
    }

    @Test
    public void test_get_document_on_granted_project() throws Exception {
        serveAs("local", "foo");
        when(indexer.get("foo", "doc1", "doc1")).thenReturn(doc("hello world"));
        when(indexer.getExtractedText("foo", "doc1", null, 0, 11, null))
                .thenReturn(new ExtractedText("hello world", 0, 11, 11));

        JsonNode result = payload(call("local", "get_document", "{\"project\":\"foo\",\"id\":\"doc1\"}"));

        assertThat(result.get("id").asText()).isEqualTo("doc1");
        assertThat(result.get("contentType").asText()).isEqualTo("application/pdf");
        assertThat(result.get("text").asText()).isEqualTo("hello world");
        assertThat(result.get("maxOffset").asInt()).isEqualTo(11);
    }

    @Test
    public void test_get_document_on_ungranted_project_is_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "get_document", "{\"project\":\"bar\",\"id\":\"doc1\"}");

        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
        verifyNoInteractions(indexer);
    }

    @Test
    public void test_get_document_unknown_id_is_not_found() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "get_document", "{\"project\":\"foo\",\"id\":\"nope\"}");

        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("not found: nope");
    }

    @Test
    public void test_get_document_clamps_slice_to_content_length() throws Exception {
        serveAs("local", "foo");
        when(indexer.get("foo", "doc1", "doc1")).thenReturn(doc("hello world"));
        when(indexer.getExtractedText("foo", "doc1", null, 6, 5, null))
                .thenReturn(new ExtractedText("world", 6, 5, 11));

        JsonNode result = payload(call("local", "get_document",
                                        "{\"project\":\"foo\",\"id\":\"doc1\",\"offset\":6,\"limit\":500}"));

        assertThat(result.get("text").asText()).isEqualTo("world");
    }

    @Test
    public void test_get_document_never_reads_text_from_repository() throws Exception {
        serveAs("local", "foo");
        when(indexer.get("foo", "doc1", "doc1")).thenReturn(doc("hello world"));
        when(indexer.getExtractedText(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                                      org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(new ExtractedText("hello world", 0, 11, 11));

        call("local", "get_document", "{\"project\":\"foo\",\"id\":\"doc1\"}");

        verify(repository, never()).getDocument(any());
    }

    @Test
    public void test_list_tasks_only_returns_the_callers_tasks() throws Exception {
        serveAs("local", "foo");
        Task<?> mine = new Task<>("org.icij.datashare.tasks.ScanTask", localUser("local"), Map.of("defaultProject", "foo"));
        when(taskManager.getTasks(any(TaskFilters.class))).thenAnswer(invocation -> {
            TaskFilters filters = invocation.getArgument(0);
            assertThat(filters.getUser().id).isEqualTo("local");
            return java.util.stream.Stream.of(mine);
        });
        when(batchSearchRepository.getRecords(any(), any())).thenReturn(List.of());

        JsonNode tasks = payload(call("local", "list_tasks", "{}"));

        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.at("/0/id").asText()).isEqualTo(mine.id);
    }

    @Test
    public void test_list_tasks_is_limited_and_paginated() throws Exception {
        serveAs("local", "foo");
        List<Task<?>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(new Task<>("org.icij.datashare.tasks.ScanTask", localUser("local"),
                                 Map.of("defaultProject", "foo")));
        }
        when(taskManager.getTasks(any(TaskFilters.class))).thenAnswer(invocation -> tasks.stream());
        when(batchSearchRepository.getRecords(any(), any())).thenReturn(List.of());

        assertThat(payload(call("local", "list_tasks", "{}")).size()).isEqualTo(20);

        JsonNode page = payload(call("local", "list_tasks", "{\"from\":25,\"size\":100}"));
        assertThat(page.size()).isEqualTo(5);
        assertThat(page.at("/0/id").asText()).isEqualTo(tasks.get(25).id);
    }

    @Test
    public void test_list_tasks_size_above_100_is_invalid() throws Exception {
        serveAs("local", "foo");
        assertThat(call("local", "list_tasks", "{\"size\":101}").at("/error/code").asInt()).isEqualTo(-32602);
    }

    @Test
    public void test_list_tasks_serializes_batch_download_arguments() throws Exception {
        serveAs("local", "foo");
        BatchDownload batchDownload = new BatchDownload(List.of(new Project("foo")), localUser("local"), "leak");
        Task<?> task = new Task<>(BatchDownload.class.getName(), localUser("local"), Map.of("batchDownload", batchDownload));
        when(taskManager.getTasks(any(TaskFilters.class))).thenReturn(java.util.stream.Stream.of(task));
        when(batchSearchRepository.getRecords(any(), any())).thenReturn(List.of());

        JsonNode tasks = payload(call("local", "list_tasks", "{}"));

        assertThat(tasks.at("/0/id").asText()).isEqualTo(task.id);
    }

    @Test
    public void test_tag_documents_writes_to_the_repository_and_the_index() throws Exception {
        serveAs("local", "foo");
        // Mockito 5 captures a varargs parameter one element at a time, so the captor takes the element type.
        ArgumentCaptor<Tag> written = ArgumentCaptor.forClass(Tag.class);

        JsonNode result = payload(call("local", "tag_documents",
                                       "{\"project\":\"foo\",\"doc_ids\":[\"doc1\",\"doc2\"],\"tags\":[\"invoice\"]}"));

        verify(repository).tag(eq(new Project("foo")), eq(List.of("doc1", "doc2")), written.capture());
        verify(indexer).tag(eq(new Project("foo")), eq(List.of("doc1", "doc2")), any(Tag[].class));
        assertThat(written.getValue().label).isEqualTo("invoice");
        assertThat(result.get("tagged").asInt()).isEqualTo(2);
    }

    @Test
    public void test_tag_documents_attributes_the_tags_to_the_caller() throws Exception {
        serveAs("local", "foo");
        ArgumentCaptor<Tag> written = ArgumentCaptor.forClass(Tag.class);

        call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        verify(repository).tag(any(Project.class), eq(List.of("doc1")), written.capture());
        assertThat(written.getValue().user.id).isEqualTo("local");
    }

    // repository.tag writes the row whether or not the id exists, exactly like the REST route. The count is a count
    // of requested ids, not a proof the documents are there.
    @Test
    public void test_tag_documents_counts_unknown_ids_too() throws Exception {
        serveAs("local", "foo");

        JsonNode result = payload(call("local", "tag_documents",
                                       "{\"project\":\"foo\",\"doc_ids\":[\"nope\"],\"tags\":[\"invoice\"]}"));

        assertThat(result.get("tagged").asInt()).isEqualTo(1);
    }

    @Test
    public void test_untag_documents_removes_the_tags() throws Exception {
        serveAs("local", "foo");

        JsonNode result = payload(call("local", "untag_documents",
                                       "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}"));

        verify(repository).untag(eq(new Project("foo")), eq(List.of("doc1")), any(Tag[].class));
        verify(indexer).untag(eq(new Project("foo")), eq(List.of("doc1")), any(Tag[].class));
        assertThat(result.get("untagged").asInt()).isEqualTo(1);
    }

    // The message of a repository failure carries the statement that failed. Only the exceptions this package
    // authors are meant to reach the client.
    @Test
    public void test_a_repository_failure_does_not_hand_the_client_the_sql() throws Exception {
        serveAs("local", "foo");
        when(repository.tag(any(Project.class), anyList(), any(Tag[].class))).thenThrow(new RuntimeException(
                "SQL [insert into \"document_tag\" (\"doc_id\", \"label\", \"prj_id\") values (?, ?, ?) "
                + "on conflict do nothing]; ERROR: value too long for type character varying(64)"));

        JsonNode response = call("local", "tag_documents",
                                 "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.toString()).excludes("document_tag").excludes("insert into").excludes("conflict");
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("RuntimeException");
    }

    // The database write runs first and only the indexer declares IOException, so this failure means the tags are
    // in the database and absent from the index. Saying so stops an agent retrying a write that already landed.
    @Test
    public void test_tag_documents_says_so_when_only_the_index_write_failed() throws Exception {
        serveAs("local", "foo");
        when(indexer.tag(any(Project.class), anyList(), any(Tag[].class)))
                .thenThrow(new java.io.IOException("connection refused to elasticsearch:9200"));

        JsonNode response = call("local", "tag_documents",
                                 "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).contains("saved").contains("index");
        assertThat(response.toString()).excludes("elasticsearch:9200");
        verify(repository).tag(any(Project.class), anyList(), any(Tag[].class));
    }

    @Test
    public void test_untag_documents_says_so_when_only_the_index_write_failed() throws Exception {
        serveAs("local", "foo");
        when(indexer.untag(any(Project.class), anyList(), any(Tag[].class)))
                .thenThrow(new java.io.IOException("connection refused to elasticsearch:9200"));

        JsonNode response = call("local", "untag_documents",
                                 "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).contains("removed").contains("index");
        assertThat(response.toString()).excludes("elasticsearch:9200");
    }

    // The Elasticsearch client throws ElasticsearchException, a RuntimeException, for index_not_found and for a
    // failing script. Those are the likely index failures, so they must reach the same explanation.
    @Test
    public void test_tag_documents_says_so_when_the_index_rejects_the_write() throws Exception {
        serveAs("local", "foo");
        when(indexer.tag(any(Project.class), anyList(), any(Tag[].class))).thenThrow(indexMissing());

        JsonNode response = call("local", "tag_documents",
                                 "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).contains("saved").contains("index");
    }

    @Test
    public void test_untag_documents_says_so_when_the_index_rejects_the_write() throws Exception {
        serveAs("local", "foo");
        when(indexer.untag(any(Project.class), anyList(), any(Tag[].class))).thenThrow(indexMissing());

        JsonNode response = call("local", "untag_documents",
                                 "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).contains("removed").contains("index");
    }

    private static co.elastic.clients.elasticsearch._types.ElasticsearchException indexMissing() {
        return new co.elastic.clients.elasticsearch._types.ElasticsearchException("es/update_by_query",
                co.elastic.clients.elasticsearch._types.ErrorResponse.of(r -> r.status(404).error(
                        co.elastic.clients.elasticsearch._types.ErrorCause.of(
                                c -> c.type("index_not_found_exception").reason("no such index [foo]")))));
    }

    @Test
    public void test_tag_documents_on_ungranted_project_is_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "tag_documents",
                                 "{\"project\":\"bar\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
        verifyNoInteractions(repository, indexer);
    }

    @Test
    public void test_untag_documents_on_ungranted_project_is_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "untag_documents",
                                 "{\"project\":\"bar\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
        verifyNoInteractions(repository, indexer);
    }

    /** A JSON array of distinct document ids, so a test about the cap reads as a test about the cap. */
    private String manyDocIds(int count) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add("\"doc" + i + "\"");
        }
        return "[" + String.join(",", ids) + "]";
    }

    @Test
    public void test_tag_documents_accepts_100_ids() throws Exception {
        serveAs("local", "foo");

        JsonNode result = payload(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":"
                                                                 + manyDocIds(100) + ",\"tags\":[\"invoice\"]}"));

        assertThat(result.get("tagged").asInt()).isEqualTo(100);
    }

    @Test
    public void test_tag_documents_above_100_ids_is_invalid() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":" + manyDocIds(101)
                                                           + ",\"tags\":[\"invoice\"]}");

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32602);
        verifyNoInteractions(repository, indexer);
    }

    // The columns these land in are document_tag.label varchar(64) and document_tag.doc_id varchar(96)
    // (002-tag-document.yml). Without this rail Postgres rejects the insert and its message, which carries the
    // whole INSERT statement, is what the MCP client gets back.
    @Test
    public void test_tag_documents_rejects_an_over_long_tag() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\""
                                                           + "x".repeat(65) + "\"]}");

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(response.toString()).excludes("document_tag");
        verifyNoInteractions(repository, indexer);
    }

    // Hasher.SHA_512 produces 128 character ids, wider than document_tag.doc_id, and SQLite stores them anyway.
    // Rejecting on the column width here would break every tag call on such an instance.
    @Test
    public void test_tag_documents_accepts_a_sha512_length_doc_id() throws Exception {
        serveAs("local", "foo");
        String sha512Id = "a".repeat(128);

        JsonNode result = payload(call("local", "tag_documents",
                                       "{\"project\":\"foo\",\"doc_ids\":[\"" + sha512Id + "\"],\"tags\":[\"invoice\"]}"));

        assertThat(result.get("tagged").asInt()).isEqualTo(1);
        verify(repository).tag(any(Project.class), eq(List.of(sha512Id)), any(Tag[].class));
    }

    // A delete cannot overflow a column, and a label that is already stored and too long must stay removable.
    @Test
    public void test_untag_documents_accepts_an_over_long_tag() throws Exception {
        serveAs("local", "foo");
        String stored = "x".repeat(65);

        JsonNode result = payload(call("local", "untag_documents",
                                       "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"" + stored + "\"]}"));

        assertThat(result.get("untagged").asInt()).isEqualTo(1);
        verify(repository).untag(any(Project.class), anyList(), any(Tag[].class));
    }

    @Test
    public void test_tag_documents_rejects_bad_lists() throws Exception {
        serveAs("local", "foo");

        assertThat(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[],\"tags\":[\"a\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[1],\"tags\":[\"a\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "tag_documents", "{\"doc_ids\":[\"doc1\"],\"tags\":[\"a\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        verifyNoInteractions(repository, indexer);
    }

    @Test
    public void test_get_document_lists_its_tags() throws Exception {
        serveAs("local", "foo");
        when(indexer.get("foo", "doc1", "doc1")).thenReturn(doc("hello world"));
        when(indexer.getExtractedText("foo", "doc1", null, 0, 11, null))
                .thenReturn(new ExtractedText("hello world", 0, 11, 11));
        when(repository.getTags(new Project("foo"), "doc1"))
                .thenReturn(List.of(new Tag("invoice"), new Tag("Reviewed")));

        JsonNode result = payload(call("local", "get_document", "{\"project\":\"foo\",\"id\":\"doc1\"}"));

        assertThat(result.get("tags").size()).isEqualTo(2);
        assertThat(result.at("/tags/0").asText()).isEqualTo("invoice");
        assertThat(result.at("/tags/1").asText()).isEqualTo("Reviewed");
    }

    @Test
    public void test_get_document_without_tags_returns_an_empty_list() throws Exception {
        serveAs("local", "foo");
        when(indexer.get("foo", "doc1", "doc1")).thenReturn(doc("hello world"));
        when(indexer.getExtractedText("foo", "doc1", null, 0, 11, null))
                .thenReturn(new ExtractedText("hello world", 0, 11, 11));

        JsonNode result = payload(call("local", "get_document", "{\"project\":\"foo\",\"id\":\"doc1\"}"));

        assertThat(result.get("tags").isArray()).isTrue();
        assertThat(result.get("tags").size()).isEqualTo(0);
    }

    @Test
    public void test_list_tags_returns_the_buckets_in_order() throws Exception {
        serveAs("local", "foo");
        when(repository.getTagCounts(eq(Project.project("foo")), eq(50))).thenReturn(
                List.of(new Repository.Aggregate<>("invoice", 12), new Repository.Aggregate<>("contract", 3)));

        JsonNode tags = payload(call("local", "list_tags", "{\"project\":\"foo\"}"));

        assertThat(tags.size()).isEqualTo(2);
        assertThat(tags.at("/0/tag").asText()).isEqualTo("invoice");
        assertThat(tags.at("/0/count").asLong()).isEqualTo(12);
        assertThat(tags.at("/1/tag").asText()).isEqualTo("contract");
    }

    @Test
    public void test_list_tags_passes_the_size_through() throws Exception {
        serveAs("local", "foo");
        when(repository.getTagCounts(eq(Project.project("foo")), eq(5))).thenReturn(List.of());

        call("local", "list_tags", "{\"project\":\"foo\",\"size\":5}");

        verify(repository).getTagCounts(eq(Project.project("foo")), eq(5));
    }

    @Test
    public void test_list_tags_returns_the_stored_casing_verbatim() throws Exception {
        serveAs("local", "foo");
        when(repository.getTagCounts(eq(Project.project("foo")), eq(50))).thenReturn(
                List.of(new Repository.Aggregate<>("Invoice", 1)));

        JsonNode tags = payload(call("local", "list_tags", "{\"project\":\"foo\"}"));

        assertThat(tags.at("/0/tag").asText()).isEqualTo("Invoice");
    }

    @Test
    public void test_list_tags_on_a_project_without_tags_is_empty() throws Exception {
        serveAs("local", "foo");
        when(repository.getTagCounts(eq(Project.project("foo")), eq(50))).thenReturn(List.of());

        JsonNode tags = payload(call("local", "list_tags", "{\"project\":\"foo\"}"));

        assertThat(tags.isArray()).isTrue();
        assertThat(tags.size()).isEqualTo(0);
    }

    @Test
    public void test_list_tags_on_ungranted_project_is_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "list_tags", "{\"project\":\"bar\"}");

        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
        verifyNoInteractions(repository);
    }

    @Test
    public void test_list_tags_size_above_500_is_invalid() throws Exception {
        serveAs("local", "foo");

        assertThat(call("local", "list_tags", "{\"project\":\"foo\",\"size\":501}").at("/error/code").asInt())
                .isEqualTo(-32602);
        verifyNoInteractions(repository);
    }

    @Test
    public void test_list_tags_rejects_project_with_a_comma_as_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "list_tags", "{\"project\":\"foo,bar\"}");

        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: foo,bar");
    }
}
