package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.session.YesBasicAuthFilter;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.icij.extract.extractor.ExtractionStatus;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.Report;
import org.icij.extract.report.ReportMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ProjectResourceTest extends AbstractProdWebServerTest {
    @Mock Repository repository;
    @Mock JooqRepository jooqRepository;
    @Mock Indexer indexer;
    @Mock TaskManager taskManager;
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    MemoryDocumentCollectionFactory<Path> documentCollectionFactory;
    PropertiesProvider propertiesProvider;

    @Before
    public void setUp() {
        initMocks(this);
        documentCollectionFactory = new MemoryDocumentCollectionFactory<>();
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        configure(routes -> {
            propertiesProvider = new PropertiesProvider(new HashMap<>() {{
                put("dataDir", "/vault");
                put("mode", "LOCAL");
            }});

            ProjectResource projectResource = new ProjectResource(repository, indexer, taskManager, propertiesProvider, documentCollectionFactory);
            routes.filter(new LocalUserFilter(propertiesProvider, jooqRepository)).add(projectResource);
        });
    }

    private Users get_datashare_users(String uid, List<String> groups) {
        User user = new User(new HashMap<>() {
                    {
                        this.put("uid", uid);
                        this.put("groups_by_applications", new HashMap<String, Object>() {
                            {
                                this.put("datashare", groups);
                            }
                        });
                    }
        });
        return DatashareUser.singleUser(user);
    }


    private Users get_datashare_users(List<String> groups) {
        return get_datashare_users("local", groups);
    }

    @Test
    public void test_get_project() {
        Project project = new Project("projectId");
        when(repository.getProjects(any())).thenReturn(List.of(project));
        get("/api/project/projectId").should()
                .respond(200)
                .contain("\"name\":\"projectId\"")
                .contain("\"label\":\"projectId\"");
    }

    @Test
    public void test_cannot_get_unknown_project() {
        when(repository.getProjects(any())).thenReturn(new ArrayList<>());
        get("/api/project/projectId").should().respond(404);
    }

    @Test
    public void test_get_project_with_more_properties() {
        Project project = new Project(
                "projectId",
                "Project ID",
                "A description",
                Path.of("/vault/project"),
                "https://icij.org",
                "Data Team",
                "ICIJ",
                null,
                "*",
                new Date(),
                new Date());
        when(repository.getProjects(any())).thenReturn(List.of(project));
        get("/api/project/projectId").should()
                .respond(200)
                .contain("\"name\":\"projectId\"")
                .contain("\"label\":\"Project ID\"")
                .contain("\"description\":\"A description\"")
                .contain("\"sourceUrl\":\"https://icij.org\"")
                .contain("\"maintainerName\":\"Data Team\"")
                .contain("\"publisherName\":\"ICIJ\"")
                .contain("\"label\":\"Project ID\"");
    }

    @Test
    public void test_get_all_project_in_local_mode() {
        Project foo = new Project("foo");
        Project bar = new Project("bar");
        when(repository.getProjects(any())).thenReturn(asList(foo, bar));
        get("/api/project/").should().respond(200)
                .contain("\"name\":\"foo\"")
                .contain("\"name\":\"bar\"");
    }

    @Test
    public void test_get_ony_user_project_in_server_mode() {
        configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
                put("mode", Mode.SERVER.name());
            }});
            ProjectResource projectResource = new ProjectResource(repository, indexer, taskManager, propertiesProvider, documentCollectionFactory);
            Users datashareUsers = get_datashare_users(asList("foo", "biz"));
            BasicAuthFilter basicAuthFilter = new BasicAuthFilter("/", "icij", datashareUsers);
            routes.filter(basicAuthFilter).add(projectResource);
        });

        Project foo = new Project("foo");
        Project bar = new Project("bar");
        when(repository.getProjects(any())).thenReturn(List.of(foo));
        when(repository.getProjects()).thenReturn(asList(foo, bar));
        get("/api/project/")
                .withPreemptiveAuthentication("local", "")
                .should()
                    .respond(200)
                    .contain("\"name\":\"foo\"")
                    .not().contain("\"name\":\"bar\"");
    }

    @Test
    public void test_create_project_with_name_and_label() throws IOException {
        String body = "{ \"name\": \"foo-bar\", \"label\": \"Foo Bar\", \"sourcePath\": \"/vault/foo\" }";
        when(indexer.createIndex("foo-bar")).thenReturn(true);
        when(indexer.createIndex("local-datashare")).thenReturn(true);
        when(repository.save((Project) any())).thenReturn(true);
        post("/api/project/", body)
                .should()
                    .respond(201)
                    .contain("\"name\":\"foo-bar\"")
                    .contain("\"label\":\"Foo Bar\"");
    }

    @Test
    public void test_create_project() throws IOException {
        String body = "{ \"name\": \"foo\", \"label\": \"Foo v2\", \"sourcePath\": \"/vault/foo\", \"publisherName\":\"ICIJ\" }";
        when(indexer.createIndex("foo")).thenReturn(true);
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save((Project) any())).thenReturn(true);
        post("/api/project/", body).should().respond(201)
                .contain("\"name\":\"foo\"")
                .contain("\"label\":\"Foo v2\"")
                .contain("\"publisherName\":\"ICIJ\"")
                .contain("\"sourcePath\":\"file:///vault/foo\"");
    }
    @Test
    public void test_cannot_create_project_twice() throws IOException {
        String body = "{ \"name\": \"foo\", \"sourcePath\": \"/vault/foo\" }";
        when(indexer.createIndex("foo")).thenReturn(true);
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
    public void test_can_create_project_with_source_path_using_data_dir() throws IOException {
        String body = "{ \"name\": \"foo\", \"label\": \"Foo Bar\", \"sourcePath\": \"/vault\" }";
        when(indexer.createIndex("foo")).thenReturn(true);
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
        Project project = new Project("local-datashare", "127.0.0.1");
        when(repository.getProject("local-datashare")).thenReturn(project);
        get("/api/project/isDownloadAllowed/local-datashare").should().respond(200);
    }

    @Test
    public void test_unknown_is_allowed() {
        get("/api/project/isDownloadAllowed/projectId").should().respond(200);
    }

    @Test
    public void test_is_not_allowed() {
        when(repository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "127.0.0.2"));
        get("/api/project/isDownloadAllowed/local-datashare").should().respond(403);
    }

    @Test
    public void test_delete_project() {
        Project foo = new Project("local-datashare");
        when(repository.getProjects(any())).thenReturn(List.of(foo));
        when(repository.deleteAll("local-datashare")).thenReturn(true).thenReturn(false);
        delete("/api/project/local-datashare").should().respond(204);
        delete("/api/project/local-datashare").should().respond(204);
    }

    @Test
    public void test_delete_project_even_without_index() throws IOException {
        Project foo = new Project("foo");
        when(repository.getProjects(any())).thenReturn(List.of(foo));
        when(indexer.deleteAll(foo.getId())).thenReturn(false);
        delete("/api/project/foo").should().respond(204);
    }

    @Test
    public void test_delete_project_only_delete_index() throws Exception {
        Project foo = new Project("local-datashare");
        when(repository.getProjects(any())).thenReturn(List.of(foo));
        when(repository.deleteAll("local-datashare")).thenReturn(false).thenReturn(false);
        when(indexer.deleteAll("local-datashare")).thenReturn(true).thenReturn(false);
        delete("/api/project/local-datashare").should().respond(204);
        delete("/api/project/local-datashare").should().respond(204);
    }

    @Test
    public void test_delete_project_delete_artifacts() throws Exception {
        configure(routes -> {
            propertiesProvider = new PropertiesProvider(new HashMap<>() {{
                put("dataDir", "/vault");
                put("mode", "LOCAL");
                put("artifactDir", artifactDir.getRoot().toString());
            }});

            ProjectResource projectResource = new ProjectResource(repository, indexer, taskManager, propertiesProvider, documentCollectionFactory);
            routes.filter(new LocalUserFilter(propertiesProvider, jooqRepository)).add(projectResource);
        });

        artifactDir.newFolder("test-datashare");
        artifactDir.newFile("test-datashare/foo");
        Project project = new Project("test-datashare");
        when(repository.getProjects(any())).thenReturn(List.of(project));
        when(repository.deleteAll(project.getId())).thenReturn(false).thenReturn(false);
        when(indexer.deleteAll(project.getId())).thenReturn(true).thenReturn(false);
        delete("/api/project/test-datashare").should().respond(204);
        assertThat(artifactDir.getRoot().toPath().resolve(project.getId()).toFile()).doesNotExist();
    }

    @Test
    public void test_delete_project_with_unauthorized_user() {
        configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(Collections.singletonMap("mode", Mode.SERVER.name()));
            routes.filter(new YesBasicAuthFilter(propertiesProvider))
                    .add(new ProjectResource(repository, indexer, taskManager, propertiesProvider, documentCollectionFactory));
        });
        when(repository.deleteAll("hacker-datashare")).thenReturn(true);
        when(repository.deleteAll("projectId")).thenReturn(true);
        delete("/api/project/hacker-datashare").withPreemptiveAuthentication("hacker", "pass").should().respond(403);
        delete("/api/project/projectId").should().respond(401);
    }

    @Test
    public void test_delete_all_projects() throws Exception{
        Project foo = new Project("foo");
        Project bar = new Project("bar");
        Task task = new Task("name", User.local(), new HashMap<>());
        when(repository.getProjects(any())).thenReturn(asList(foo, bar));
        when(repository.deleteAll("foo")).thenReturn(true).thenReturn(false);
        when(repository.deleteAll("bar")).thenReturn(true).thenReturn(false);
        when(taskManager.clearDoneTasks()).thenReturn(List.of(task)).thenReturn(List.of());
        delete("/api/project/").should().respond(204);
    }

    @Test
    public void test_delete_project_and_its_legacy_queue() {
        Project foo = new Project("foo");
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:foo", Path.class);
        when(repository.getProjects(any())).thenReturn(List.of(foo));
        when(repository.deleteAll("foo")).thenReturn(true);
        queue.add(Path.of("/"));
        assertThat(queue.size()).isEqualTo(1);
        delete("/api/project/foo").should().respond(204);
        assertThat(queue.size()).isEqualTo(0);
    }

    @Test
    public void test_delete_project_and_its_index_queue() {
        Project foo = new Project("foo");
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:foo:index", Path.class);
        when(repository.getProjects(any())).thenReturn(List.of(foo));
        when(repository.deleteAll("foo")).thenReturn(true);
        queue.add(Path.of("/"));
        assertThat(queue.size()).isEqualTo(1);
        delete("/api/project/foo").should().respond(204);
        assertThat(queue.size()).isEqualTo(0);
    }


    @Test
    public void test_delete_project_and_it_nlp_queue() {
        Project foo = new Project("foo");
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:foo:index", Path.class);
        when(repository.getProjects(any())).thenReturn(List.of(foo));
        when(repository.deleteAll("foo")).thenReturn(true);
        queue.add(Path.of("/"));
        assertThat(queue.size()).isEqualTo(1);
        delete("/api/project/foo").should().respond(204);
        assertThat(queue.size()).isEqualTo(0);
    }

    @Test
    public void test_delete_project_and_its_report_map() {
        Project foo = new Project("foo");
        ReportMap reportMap = documentCollectionFactory.createMap("extract:report:foo");
        when(repository.getProjects(any())).thenReturn(List.of(foo));
        when(repository.deleteAll("foo")).thenReturn(true);
        reportMap.put(Path.of("/"), new Report(ExtractionStatus.SUCCESS));
        assertThat(reportMap.size()).isEqualTo(1);
        delete("/api/project/foo").should().respond(204);
        assertThat(reportMap.size()).isEqualTo(0);
    }
}
