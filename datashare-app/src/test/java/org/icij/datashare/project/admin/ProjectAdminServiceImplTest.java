package org.icij.datashare.project.admin;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.ReportMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProjectAdminServiceImplTest {

    private Repository repository;
    private Indexer indexer;
    private Authorizer authorizer;
    private DocumentCollectionFactory<Path> documentCollectionFactory;
    private PropertiesProvider propertiesProvider;
    private ProjectAdminServiceImpl service;

    @Before
    public void setUp() {
        repository = mock(Repository.class);
        indexer = mock(Indexer.class);
        authorizer = mock(Authorizer.class);
        documentCollectionFactory = mock(DocumentCollectionFactory.class);
        propertiesProvider = mock(PropertiesProvider.class);
        service = new ProjectAdminServiceImpl(
                repository, indexer, authorizer, documentCollectionFactory, propertiesProvider);
    }

    private ProjectCreateRequest minimalRequest(String name) {
        return new ProjectCreateRequest(name, null, null, null, null, null, null, null, null, true);
    }

    @Test
    public void test_create_persists_project_with_supplied_fields() throws Exception {
        when(repository.getProject("my-project")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        ProjectCreated created = service.create(new ProjectCreateRequest(
                "my-project", "My Project", "leak archive",
                Path.of("/data/my"), "10.0.0.0",
                "https://src/", "Maint", "Pub", "https://logo.png",
                true));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        Project saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("my-project");
        assertThat(saved.getLabel()).isEqualTo("My Project");
        assertThat(saved.getDescription()).isEqualTo("leak archive");
        assertThat((Object) saved.getSourcePath()).isEqualTo(Path.of("/data/my"));
        assertThat(saved.getAllowFromMask()).isEqualTo("10.0.0.0");

        verify(indexer).createIndex("my-project");

        assertThat(created.name()).isEqualTo("my-project");
        assertThat(created.indexCreated()).isTrue();
        assertThat(created.noop()).isFalse();
    }

    @Test
    public void test_create_defaults_label_to_name_and_path_to_vault() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        service.create(minimalRequest("foo"));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLabel()).isEqualTo("foo");
        assertThat((Object) captor.getValue().getSourcePath()).isEqualTo(Path.of("/vault/foo"));
        assertThat(captor.getValue().getAllowFromMask()).isEqualTo("*.*.*.*");
    }

    @Test
    public void test_create_throws_when_project_exists() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));

        try {
            service.create(minimalRequest("foo"));
            fail("expected ProjectExistsException");
        } catch (ProjectExistsException e) {
            assertThat(e.getMessage()).contains("foo");
        }
        verify(repository, never()).save(any(Project.class));
        verify(indexer, never()).createIndex(any());
    }

    @Test
    public void test_create_with_blank_name_throws_validation() throws Exception {
        try {
            service.create(minimalRequest(""));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("name");
        } catch (ProjectExistsException e) {
            fail("unexpected ProjectExistsException");
        }
        verify(repository, never()).save(any(Project.class));
    }

    @Test
    public void test_create_skips_index_when_createIndex_false() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        ProjectCreated created = service.create(new ProjectCreateRequest(
                "foo", null, null, null, null, null, null, null, null, false));

        verify(indexer, never()).createIndex(any());
        assertThat(created.indexCreated()).isFalse();
    }

    @Test
    public void test_create_compensates_db_when_index_creation_fails() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);
        when(indexer.createIndex("foo")).thenThrow(new IOException("ES down"));

        try {
            service.create(minimalRequest("foo"));
            fail("expected IOException");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("ES down");
        }

        InOrder inOrder = Mockito.inOrder(repository, indexer);
        inOrder.verify(repository).save(any(Project.class));
        inOrder.verify(indexer).createIndex("foo");
        inOrder.verify(repository).deleteAll("foo");
    }

    @Test
    public void test_create_logs_suppressed_when_compensating_delete_also_fails() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);
        when(indexer.createIndex("foo")).thenThrow(new IOException("ES down"));
        when(repository.deleteAll("foo")).thenThrow(new RuntimeException("rollback boom"));

        try {
            service.create(minimalRequest("foo"));
            fail("expected IOException");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("ES down");
            Throwable[] suppressed = e.getSuppressed();
            assertThat(suppressed).hasSize(1);
            assertThat(suppressed[0].getMessage()).contains("rollback boom");
        }
    }

    @Test
    public void test_create_if_not_exists_returns_noop_when_project_exists() throws Exception {
        Project existing = new Project("foo", "Existing", "existing desc",
                Path.of("/old/foo"), "https://old/", null, null, null, "*.*.*.*", null, null);
        when(repository.getProject("foo")).thenReturn(existing);

        ProjectCreated created = service.createIfNotExists(minimalRequest("foo"));

        assertThat(created.noop()).isTrue();
        assertThat(created.indexCreated()).isFalse();
        assertThat(created.name()).isEqualTo("foo");
        assertThat(created.label()).isEqualTo("Existing");
        assertThat(created.description()).isEqualTo("existing desc");
        assertThat((Object) created.sourcePath()).isEqualTo(Path.of("/old/foo"));
        verify(repository, never()).save(any(Project.class));
        verify(indexer, never()).createIndex(any());
    }

    @Test
    public void test_create_if_not_exists_persists_when_project_missing() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        ProjectCreated created = service.createIfNotExists(minimalRequest("foo"));

        verify(repository).save(any(Project.class));
        verify(indexer).createIndex("foo");
        assertThat(created.noop()).isFalse();
        assertThat(created.indexCreated()).isTrue();
    }

    @Test
    public void test_stats_returns_index_count_and_distinct_member_count() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(indexer.count("foo")).thenReturn(42L);
        when(authorizer.getGroupPermissions(any(Domain.class), eq("foo")))
                .thenReturn(List.of(
                        casbinRule("alice", "PROJECT_ADMIN", "datashare::foo"),
                        casbinRule("bob", "PROJECT_MEMBER", "datashare::foo"),
                        casbinRule("alice", "PROJECT_VISITOR", "datashare::foo")  // duplicate user
                ));

        ProjectStats stats = service.stats("foo");

        assertThat(stats.name()).isEqualTo("foo");
        assertThat(stats.indexedDocuments()).isEqualTo(42L);
        assertThat(stats.memberCount()).isEqualTo(2);  // alice + bob, deduped
    }

    @Test
    public void test_stats_throws_when_project_missing() throws Exception {
        when(repository.getProject("ghost")).thenReturn(null);
        try {
            service.stats("ghost");
            fail("expected ProjectNotFoundException");
        } catch (ProjectNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        }
        verify(indexer, never()).count(any());
    }

    @Test
    public void test_delete_runs_full_cascade_in_order() throws Exception {
        Project project = new Project("foo");
        when(repository.getProject("foo")).thenReturn(project);
        when(repository.deleteAll("foo")).thenReturn(true);
        when(indexer.deleteAll("foo")).thenReturn(true);
        DocumentQueue<Path> queue = mock(DocumentQueue.class);
        when(queue.delete()).thenReturn(true);
        when(documentCollectionFactory.getQueues(any(String.class), eq(Path.class))).thenReturn(List.of(queue));
        ReportMap reportMap = mock(ReportMap.class);
        when(reportMap.delete()).thenReturn(true);
        when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
        when(propertiesProvider.createOverriddenWith(any())).thenReturn(new Properties());
        when(propertiesProvider.get(any())).thenReturn(Optional.empty()); // no artifact dir configured

        ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(false));

        InOrder inOrder = Mockito.inOrder(indexer, repository, queue, reportMap);
        inOrder.verify(indexer).deleteAll("foo");
        inOrder.verify(repository).deleteAll("foo");
        // Two queue lookups (legacy prefix + new pattern) both return the same mock,
        // so queue.delete() is called twice.
        inOrder.verify(queue, Mockito.times(2)).delete();
        inOrder.verify(reportMap).delete();

        assertThat(deleted.name()).isEqualTo("foo");
        assertThat(deleted.indexDeleted()).isTrue();
        assertThat(deleted.dbDeleted()).isTrue();
        assertThat(deleted.queuesDeleted()).isTrue();
        assertThat(deleted.reportMapDeleted()).isTrue();
        assertThat(deleted.artifactsDeleted()).isFalse(); // no artifact dir configured
        assertThat(deleted.noop()).isFalse();
    }

    @Test
    public void test_delete_skips_index_when_keepIndex_true() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(repository.deleteAll("foo")).thenReturn(true);
        when(documentCollectionFactory.getQueues(any(String.class), eq(Path.class))).thenReturn(List.of());
        ReportMap reportMap = mock(ReportMap.class);
        when(reportMap.delete()).thenReturn(true);
        when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
        when(propertiesProvider.createOverriddenWith(any())).thenReturn(new Properties());
        when(propertiesProvider.get(any())).thenReturn(Optional.empty());

        ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(true));

        verify(indexer, never()).deleteAll(any());
        assertThat(deleted.indexDeleted()).isFalse();
        assertThat(deleted.dbDeleted()).isTrue();
    }

    @Test
    public void test_delete_throws_when_project_missing() throws Exception {
        when(repository.getProject("ghost")).thenReturn(null);
        try {
            service.delete("ghost", ProjectDeleteOptions.defaults());
            fail("expected ProjectNotFoundException");
        } catch (ProjectNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        }
        verify(indexer, never()).deleteAll(any());
        verify(repository, never()).deleteAll(any());
    }

    @Test
    public void test_delete_if_exists_returns_noop_when_project_missing() throws Exception {
        when(repository.getProject("ghost")).thenReturn(null);

        ProjectDeleted deleted = service.deleteIfExists("ghost", ProjectDeleteOptions.defaults());

        assertThat(deleted.noop()).isTrue();
        assertThat(deleted.dbDeleted()).isFalse();
        assertThat(deleted.indexDeleted()).isFalse();
        verify(indexer, never()).deleteAll(any());
        verify(repository, never()).deleteAll(any());
    }

    @Test
    public void test_delete_aborts_when_db_delete_fails() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(indexer.deleteAll("foo")).thenReturn(true);
        when(repository.deleteAll("foo")).thenThrow(new RuntimeException("DB down"));

        try {
            service.delete("foo", ProjectDeleteOptions.defaults());
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("DB down");
        }
        // Cascade aborts: queues, report-map, artifacts must NOT run.
        verify(documentCollectionFactory, never()).getQueues(any(String.class), any());
        verify(documentCollectionFactory, never()).createMap(any());
    }

    private static CasbinRule casbinRule(String userId, String role, String domainProject) {
        return new CasbinRule("g", userId, role, domainProject);
    }
}
