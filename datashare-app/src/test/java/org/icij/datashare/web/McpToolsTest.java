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
                                                              documentCollectionFactory, projectAdminService));
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
}
