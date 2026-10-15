package org.icij.datashare.project.admin;

import net.codestory.http.security.Users;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.session.UserStore;
import org.icij.datashare.session.UsersIdProviderCache;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.web.WebResponse;
import org.icij.extract.extractor.ExtractionStatus;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.Report;
import org.icij.extract.report.ReportMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cascade steps that are only meaningful against real collaborators: queue draining through a
 * live DocumentCollectionFactory, and artifact removal from a real directory. Mocked-collaborator
 * cascade behaviour lives in {@link ProjectAdminServiceImplTest}.
 * <p>
 * These cases used to sit in ProjectResourceTest, exercising the copy of the cascade that
 * ProjectResource carried. That copy is gone (see #2441): the resource delegates, so the behaviour
 * is only reachable here.
 */
public class ProjectAdminServiceCascadeTest {
    @Rule
    public TemporaryFolder artifactDir = new TemporaryFolder();

    private Repository repository;
    private MemoryDocumentCollectionFactory<Path> documentCollectionFactory;
    private TaskRepositoryMemory taskRepository;
    private ProjectAdminServiceImpl service;

    @Before
    public void setUp() {
        repository = mock(Repository.class);
        Indexer indexer = mock(Indexer.class);
        Authorizer authorizer = mock(Authorizer.class);
        Users users = mock(Users.class);
        UserStore userStore = mock(UserStore.class);
        UsersIdProviderCache usersIdProviderCache = mock(UsersIdProviderCache.class);
        documentCollectionFactory = new MemoryDocumentCollectionFactory<>();
        taskRepository = new TaskRepositoryMemory();
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("dataDir", "/vault");
            put("mode", "LOCAL");
            put("artifactDir", artifactDir.getRoot().toString());
        }});
        when(userStore.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.<User>of(), 0, 0, 0));
        service = new ProjectAdminServiceImpl(repository, indexer, authorizer, documentCollectionFactory,
                                              propertiesProvider, users, userStore, usersIdProviderCache,
                                              taskRepository);
    }

    @Test
    public void test_delete_drains_the_legacy_queue() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:foo", Path.class);
        queue.add(Path.of("/"));
        assertThat(queue.size()).isEqualTo(1);

        service.delete("foo", new ProjectDeleteOptions(true));

        assertThat(queue.size()).isEqualTo(0);
    }

    @Test
    public void test_delete_drains_a_per_stage_queue() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:foo:index", Path.class);
        queue.add(Path.of("/"));
        assertThat(queue.size()).isEqualTo(1);

        service.delete("foo", new ProjectDeleteOptions(true));

        assertThat(queue.size()).isEqualTo(0);
    }

    @Test
    public void test_delete_empties_the_report_map() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        ReportMap reportMap = documentCollectionFactory.createMap("extract:report:foo");
        reportMap.put(Path.of("/"), new Report(ExtractionStatus.SUCCESS));
        assertThat(reportMap.size()).isEqualTo(1);

        service.delete("foo", new ProjectDeleteOptions(true));

        assertThat(reportMap.size()).isEqualTo(0);
    }

    @Test
    public void test_delete_removes_the_projects_artifact_dir() throws Exception {
        when(repository.getProject("test-datashare")).thenReturn(new Project("test-datashare"));
        artifactDir.newFolder("test-datashare");
        artifactDir.newFile("test-datashare/foo");

        ProjectDeleted deleted = service.delete("test-datashare", new ProjectDeleteOptions(true));

        assertThat(artifactDir.getRoot().toPath().resolve("test-datashare").toFile()).doesNotExist();
        assertThat(deleted.artifactsDeleted()).isTrue();
    }

    @Test
    public void test_delete_leaves_another_projects_queue_alone() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        DocumentQueue<Path> other = documentCollectionFactory.createQueue("extract:queue:bar", Path.class);
        other.add(Path.of("/"));

        service.delete("foo", new ProjectDeleteOptions(true));

        assertThat(other.size()).isEqualTo(1);
    }

    @Test
    public void test_delete_removes_the_tasks_naming_the_project_in_their_args() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        insertTask("index-foo", Map.of("projectName", "foo"));
        insertTask("scan-foo", Map.of("defaultProject", "foo"));
        insertTask("index-bar", Map.of("projectName", "bar"));

        service.delete("foo", new ProjectDeleteOptions(true));

        assertThat(taskIds()).containsOnly("index-bar");
    }

    @Test
    public void test_delete_leaves_the_tasks_of_a_project_whose_name_starts_the_same() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        insertTask("index-foo-2", Map.of("projectName", "foo-2"));

        service.delete("foo", new ProjectDeleteOptions(true));

        assertThat(taskIds()).containsOnly("index-foo-2");
    }

    @Test
    public void test_delete_removes_a_batch_download_task_listing_the_project() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        insertTask("download-foo", Map.of("batchDownload", Map.of("projects", List.of(Map.of("name", "foo")))));
        insertTask("download-bar", Map.of("batchDownload", Map.of("projects", List.of(Map.of("name", "bar")))));

        service.delete("foo", new ProjectDeleteOptions(true));

        assertThat(taskIds()).containsOnly("download-bar");
    }

    @Test
    public void test_delete_reports_the_task_step() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        insertTask("index-foo", Map.of("projectName", "foo"));

        assertThat(service.delete("foo", new ProjectDeleteOptions(true)).tasksDeleted()).isTrue();
    }

    private void insertTask(String id, Map<String, Object> args) throws Exception {
        taskRepository.insert(new Task<>(id, "IndexTask", User.local(), args), new Group(TaskGroupType.Java));
    }

    private List<String> taskIds() throws Exception {
        return taskRepository.getTaskIds(new TaskFilters()).toList();
    }
}
