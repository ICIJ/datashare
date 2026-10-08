package org.icij.datashare.project.admin;

import net.codestory.http.security.Users;
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
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("dataDir", "/vault");
            put("mode", "LOCAL");
            put("artifactDir", artifactDir.getRoot().toString());
        }});
        when(userStore.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.<User>of(), 0, 0, 0));
        service = new ProjectAdminServiceImpl(repository, indexer, authorizer, documentCollectionFactory,
                                              propertiesProvider, users, userStore, usersIdProviderCache);
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
}
