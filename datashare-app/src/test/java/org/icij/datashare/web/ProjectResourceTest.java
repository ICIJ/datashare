package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.session.YesBasicAuthFilter;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.sql.SQLException;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ProjectResourceTest extends AbstractProdWebServerTest {
    @Mock Repository repository;
    @Mock Indexer indexer;

    @Test
    public void test_get_project() {
        when(repository.getProject("projectId")).thenReturn(new Project("projectId"));
        get("/api/project/projectId").should().respond(200).contain("projectId");
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
        configure(routes -> routes.add(new ProjectResource(repository, indexer)).
                filter(new YesBasicAuthFilter(new PropertiesProvider())));
        when(repository.deleteAll("projectId")).thenReturn(true);
        delete("/api/project/hacker-datashare").withPreemptiveAuthentication("hacker", "pass").should().respond(401);
        delete("/api/project/projectId").should().respond(401);
    }

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new ProjectResource(repository, indexer)).
                filter(new LocalUserFilter(new PropertiesProvider())));
    }
}
