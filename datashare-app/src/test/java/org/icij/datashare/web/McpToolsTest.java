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
import org.icij.datashare.extract.DocumentCollectionFactory;
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
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class McpToolsTest extends AbstractProdWebServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Mock Repository repository;
    @Mock Indexer indexer;
    @Mock TaskManager taskManager;
    @Mock DocumentCollectionFactory<Path> documentCollectionFactory;
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
                                                              documentCollectionFactory, projectAdminService);
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
                                                                     TaskPolicyChecker.ALLOW_ALL)));
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
            {"hits":{"total":{"value":1},"hits":[{"_id":"doc1","_routing":"root1",
              "_source":{"path":"/data/a.pdf","contentType":"application/pdf"},
              "highlight":{"content":["a <em>leak</em> here"]}}]}}""";

    @Test
    public void test_search_on_granted_project_returns_trimmed_hits() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search"), any())).thenReturn(ES_RESPONSE);

        JsonNode result = payload(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\"}"));

        assertThat(result.get("total").asLong()).isEqualTo(1);
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
        when(indexer.executeRaw(eq("POST"), eq("foo/_search"), body.capture())).thenReturn(ES_RESPONSE);

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
    public void test_search_with_failed_shards_is_an_error() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search"), any()))
                .thenReturn("{\"_shards\":{\"failed\":1},\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}");

        JsonNode response = call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"leak\"}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("search failed on 1 shard(s)");
    }

    @Test
    public void test_search_on_ungranted_project_is_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "search_documents", "{\"project\":\"bar\",\"query\":\"leak\"}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
        org.mockito.Mockito.verifyNoInteractions(indexer);
    }

    @Test
    public void test_search_ignores_the_mcp_request_query_string() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search"), any())).thenReturn(ES_RESPONSE);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_documents\","
                      + "\"arguments\":{\"project\":\"foo\",\"query\":\"leak\"}}}";

        post("/api/mcp?q=x", body).withPreemptiveAuthentication("local", "").response();

        org.mockito.Mockito.verify(indexer).executeRaw(eq("POST"), eq("foo/_search"), any());
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
        org.mockito.Mockito.verifyNoInteractions(indexer);
    }

    @Test
    public void test_search_size_above_50_is_invalid() throws Exception {
        serveAs("local", "foo");
        assertThat(call("local", "search_documents", "{\"project\":\"foo\",\"query\":\"x\",\"size\":51}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
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
        org.mockito.Mockito.verifyNoInteractions(indexer);
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

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).getDocument(any());
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
    public void test_list_tasks_serializes_batch_download_arguments() throws Exception {
        serveAs("local", "foo");
        BatchDownload batchDownload = new BatchDownload(List.of(new Project("foo")), localUser("local"), "leak");
        Task<?> task = new Task<>(BatchDownload.class.getName(), localUser("local"), Map.of("batchDownload", batchDownload));
        when(taskManager.getTasks(any(TaskFilters.class))).thenReturn(java.util.stream.Stream.of(task));
        when(batchSearchRepository.getRecords(any(), any())).thenReturn(List.of());

        JsonNode tasks = payload(call("local", "list_tasks", "{}"));

        assertThat(tasks.at("/0/id").asText()).isEqualTo(task.id);
    }
}
