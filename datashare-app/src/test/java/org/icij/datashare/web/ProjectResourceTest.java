package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.session.YesBasicAuthFilter;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ProjectResourceTest extends AbstractProdWebServerTest {
    @Mock Repository repository;
    @Mock Indexer indexer;
    PropertiesProvider propertiesProvider;

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> {
            propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
                put("dataDir", "/vault");
                put("mode", "LOCAL");
            }});

            ProjectResource projectResource = new ProjectResource(repository, indexer, propertiesProvider);
            routes.filter(new LocalUserFilter(propertiesProvider)).add(projectResource);
        });
    }

    @Test
    public void test_get_project() {
        Project project = new Project("projectId");
        when(repository.getProject("projectId")).thenReturn(project);
        get("/api/project/projectId").should().respond(200)
                .contain("\"name\":\"projectId\"")
                .contain("\"label\":\"projectId\"");
    }

    @Test
    public void test_get_project_with_more_properties() {
        Project project = new Project(
                "projectId",
                "Project ID",
                Path.of("/vault/project"),
                "https://icij.org",
                "Data Team",
                "ICIJ",
                null,
                "*",
                new Date(),
                new Date());
        when(repository.getProject("projectId")).thenReturn(project);
        get("/api/project/projectId").should().respond(200)
                .contain("\"name\":\"projectId\"")
                .contain("\"label\":\"Project ID\"")
                .contain("\"sourceUrl\":\"https://icij.org\"")
                .contain("\"maintainerName\":\"Data Team\"")
                .contain("\"publisherName\":\"ICIJ\"")
                .contain("\"label\":\"Project ID\"");
    }

    @Test
    public void test_create_project_with_name_and_label() {
        String body = "{ \"name\": \"foo-bar\", \"label\": \"Foo Bar\", \"sourcePath\": \"/vault/foo\" }";
        when(repository.save((Project) any())).thenReturn(true);
        post("/api/project/", body).should().respond(201)
                .contain("\"name\":\"foo-bar\"")
                .contain("\"label\":\"Foo Bar\"");
    }

    @Test
    public void test_create_project() {
        String body = "{ \"name\": \"foo\", \"label\": \"Foo v2\", \"sourcePath\": \"/vault/foo\", \"publisherName\":\"ICIJ\" }";
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save((Project) any())).thenReturn(true);
        post("/api/project/", body).should().respond(201)
                .contain("\"name\":\"foo\"")
                .contain("\"label\":\"Foo v2\"")
                .contain("\"publisherName\":\"ICIJ\"")
                .contain("\"sourcePath\":\"file:///vault/foo\"");
    }
    @Test
    public void test_cannot_create_project_twice() {
        String body = "{ \"name\": \"foo\", \"sourcePath\": \"/vault/foo\" }";
        when(repository.save((Project) any())).thenReturn(true);
        when(repository.getProject("foo")).thenReturn(null);
        post("/api/project/", body).should().respond(201);
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        post("/api/project/", body).should().respond(409);
    }

    @Test
    public void test_cannot_create_project_without_source_path() {
        String body = "{ \"name\": \"projectId\", \"label\": \"Project ID\", \"sourceUrl\": \"https://icij.org\", \"publisherName\":\"ICIJ\" }";
        when(repository.getProject("foo")).thenReturn(null);
        post("/api/project/", body).should().respond(400);
    }

    @Test
    public void test_cannot_create_project_with_source_path_outside_data_dir() {
        String body = "{ \"name\": \"foo\", \"label\": \"Foo Bar\", \"sourcePath\": \"/home/foo\" }";
        when(repository.getProject("foo")).thenReturn(null);
        post("/api/project/", body).should().respond(400);
    }

    @Test
    public void test_can_create_project_with_source_path_using_data_dir() {
        String body = "{ \"name\": \"foo\", \"label\": \"Foo Bar\", \"sourcePath\": \"/vault\" }";
        when(repository.save((Project) any())).thenReturn(true);
        post("/api/project/", body).should().respond(201);
    }

    @Test
    public void test_cannot_create_project_without_name() {
        String body = "{ \"label\": \"Foo Bar\", \"sourcePath\": \"/vault/foo\" }";
        when(repository.getProject("foo")).thenReturn(null);
        post("/api/project/", body).should().respond(400);
    }

    @Test
    public void test_update_project() {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(repository.save((Project) any())).thenReturn(true);
        String body = "{ \"name\": \"foo\", \"label\": \"Foo v3\" }";
        put("/api/project/foo", body).should().respond(200)
                .contain("\"name\":\"foo\"")
                .contain("\"label\":\"Foo v3\"");
    }

    @Test
    public void test_cannot_update_project_in_server_mode() {
        Properties props = new PropertiesProvider(Collections.singletonMap("mode", Mode.SERVER.name())).getProperties();
        propertiesProvider.overrideWith(props);
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(repository.save((Project) any())).thenReturn(true);
        String body = "{ \"name\": \"foo\" }";
        put("/api/project/foo", body).should().respond(403);
    }

    @Test
    public void test_is_allowed() {
        when(repository.getProject("projectId")).thenReturn(new Project("projectId", "127.0.0.1"));
        get("/api/project/isDownloadAllowed/projectId").should().respond(200);
    }

    @Test
    public void test_unknown_is_allowed() {
        get("/api/project/isDownloadAllowed/projectId").should().respond(200);
    }

    @Test
    public void test_is_not_allowed() {
        when(repository.getProject("projectId")).thenReturn(new Project("projectId", "127.0.0.2"));
        get("/api/project/isDownloadAllowed/projectId").should().respond(403);
    }

    @Test
    public void test_delete_project() throws SQLException {
        when(repository.deleteAll("local-datashare")).thenReturn(true).thenReturn(false);
        delete("/api/project/local-datashare").should().respond(204);
        delete("/api/project/local-datashare").should().respond(204);
    }

    @Test
    public void test_delete_project_only_delete_index() throws Exception {
        when(repository.deleteAll("local-datashare")).thenReturn(false).thenReturn(false);
        when(indexer.deleteAll("local-datashare")).thenReturn(true).thenReturn(false);
        delete("/api/project/local-datashare").should().respond(204);
        delete("/api/project/local-datashare").should().respond(204);
    }

    @Test
    public void test_delete_project_with_unauthorized_user() {
        configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(Collections.singletonMap("mode", Mode.SERVER.name()));
            routes.filter(new YesBasicAuthFilter(propertiesProvider))
                    .add(new ProjectResource(repository, indexer, propertiesProvider));
        });
        when(repository.deleteAll("hacker-datashare")).thenReturn(true);
        when(repository.deleteAll("projectId")).thenReturn(true);
        delete("/api/project/hacker-datashare").withPreemptiveAuthentication("hacker", "pass").should().respond(403);
        delete("/api/project/projectId").should().respond(401);
    }
}
