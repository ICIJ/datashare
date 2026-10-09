package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class McpToolsTest extends AbstractProdWebServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Mock Repository repository;
    @Mock Indexer indexer;
    @Mock TaskManager taskManager;
    @Mock DocumentCollectionFactory<Path> documentCollectionFactory;
    @Mock ProjectAdminService projectAdminService;
    private final PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("mode", Mode.SERVER.name()));

    @Before
    public void setUp() {
        initMocks(this);
    }

    private void serveAs(String login, String... projects) {
        McpResource mcp = new McpResource(new ProjectResource(repository, indexer, taskManager, propertiesProvider,
                                                              documentCollectionFactory, projectAdminService), indexer);
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
        assertThat(sent.at("/highlight/fields/content").isObject()).isTrue();
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
}
