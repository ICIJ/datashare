package org.icij.datashare.project.admin;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import net.codestory.http.security.Users;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.session.UserStore;
import org.icij.datashare.session.UsersIdProviderCache;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.web.WebResponse;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.ReportMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ProjectAdminServiceImplTest {

    private Repository repository;
    private Indexer indexer;
    private Authorizer authorizer;
    private Users users;
    private UserStore userStore;
    private UsersIdProviderCache usersIdProviderCache;
    private DocumentCollectionFactory<Path> documentCollectionFactory;
    private PropertiesProvider propertiesProvider;
    private ProjectAdminServiceImpl service;

    @Before
    public void setUp() {
        repository = mock(Repository.class);
        indexer = mock(Indexer.class);
        authorizer = mock(Authorizer.class);
        users = mock(Users.class);
        userStore = mock(UserStore.class);
        usersIdProviderCache = mock(UsersIdProviderCache.class);
        documentCollectionFactory = mock(DocumentCollectionFactory.class);
        propertiesProvider = mock(PropertiesProvider.class);
        service = new ProjectAdminServiceImpl(
                repository, indexer, authorizer, documentCollectionFactory, propertiesProvider, users, userStore,
                usersIdProviderCache);
        // the #2441 inventory sweep lists every user on each project delete; empty by default
        when(userStore.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.of(), 0, 0, 0));
    }

    private ProjectCreateRequest minimalRequest(String name) {
        return new ProjectCreateRequest(name, null, null, null, null, null, null, null, null, null, null, true);
    }

    @Test
    public void test_create_persists_project_with_supplied_fields() throws Exception {
        when(repository.getProject("my-project")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        Date suppliedCreation = Date.from(java.time.Instant.parse("2026-05-15T10:00:00Z"));
        Date suppliedUpdate = Date.from(java.time.Instant.parse("2026-05-16T10:00:00Z"));
        ProjectCreated created = service.create(new ProjectCreateRequest(
                "my-project", "My Project", "leak archive",
                Path.of("/data/my"), "10.0.0.0",
                "https://src/", "Maint", "Pub", "https://logo.png",
                suppliedCreation, suppliedUpdate,
                true));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        Project saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("my-project");
        assertThat(saved.getLabel()).isEqualTo("My Project");
        assertThat(saved.getDescription()).isEqualTo("leak archive");
        assertThat((Object) saved.getSourcePath()).isEqualTo(Path.of("/data/my"));
        assertThat(saved.getAllowFromMask()).isEqualTo("10.0.0.0");
        assertThat(saved.creationDate).isEqualTo(suppliedCreation);
        assertThat(saved.updateDate).isEqualTo(suppliedUpdate);

        verify(indexer).createIndex("my-project");

        assertThat(created.name()).isEqualTo("my-project");
        assertThat(created.creationDate()).isEqualTo(suppliedCreation);
        assertThat(created.updateDate()).isEqualTo(suppliedUpdate);
        assertThat(created.indexCreated()).isTrue();
        assertThat(created.noop()).isFalse();
    }

    @Test
    public void test_create_auto_stamps_dates_when_request_omits_them() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        Date before = new Date();
        ProjectCreated created = service.create(minimalRequest("foo"));
        Date after = new Date();

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        Project saved = captor.getValue();
        assertThat(saved.creationDate).isNotNull();
        assertThat(saved.updateDate).isNotNull();
        // Both fields share the same "now" timestamp for a fresh row.
        assertThat(saved.creationDate).isEqualTo(saved.updateDate);
        // Stamped within the test window (allow a small tolerance below).
        assertThat(saved.creationDate.getTime() >= before.getTime()).isTrue();
        assertThat(saved.creationDate.getTime() <= after.getTime()).isTrue();

        assertThat(created.creationDate()).isEqualTo(saved.creationDate);
        assertThat(created.updateDate()).isEqualTo(saved.updateDate);
    }

    @Test
    public void test_create_defaults_label_to_name_and_path_to_data_dir() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);
        when(propertiesProvider.get("dataDir")).thenReturn(Optional.of("/srv/data"));

        service.create(minimalRequest("foo"));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLabel()).isEqualTo("foo");
        assertThat((Object) captor.getValue().getSourcePath()).isEqualTo(Path.of("/srv/data"));
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
                "foo", null, null, null, null, null, null, null, null, null, null, false));

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
        // Pin the Casbin domain to Domain.DEFAULT: that is where existing
        // instances store project-scoped grants, so stats() must read from it.
        when(authorizer.getGroupPermissions(eq(Domain.DEFAULT), eq("foo")))
                .thenReturn(List.of(
                        casbinRule("alice", "PROJECT_ADMIN", "default::foo"),
                        casbinRule("bob", "PROJECT_MEMBER", "default::foo"),
                        casbinRule("alice", "PROJECT_VISITOR", "default::foo")  // duplicate user
                ));

        ProjectStats stats = service.stats("foo", true);

        assertThat(stats.name()).isEqualTo("foo");
        assertThat(stats.indexedDocuments()).isEqualTo(OptionalLong.of(42L));
        assertThat(stats.memberCount()).isEqualTo(2);  // alice + bob, deduped
    }

    @Test
    public void test_stats_throws_when_project_missing() throws Exception {
        when(repository.getProject("ghost")).thenReturn(null);
        try {
            service.stats("ghost", true);
            fail("expected ProjectNotFoundException");
        } catch (ProjectNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        }
        verify(indexer, never()).count(any());
    }

    @Test
    public void test_stats_skips_index_count_when_includeIndexCount_false() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(authorizer.getGroupPermissions(eq(Domain.DEFAULT), eq("foo"))).thenReturn(List.of());

        ProjectStats stats = service.stats("foo", false);

        assertThat(stats.indexedDocuments().isPresent()).isFalse();
        verify(indexer, never()).count(any());
    }

    @Test
    public void test_create_with_invalid_allow_from_mask_throws_validation() throws Exception {
        try {
            service.create(new ProjectCreateRequest(
                    "foo", null, null, null, "not-a-mask", null, null, null, null, null, null, true));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("allowFromMask");
        } catch (ProjectExistsException e) {
            fail("unexpected ProjectExistsException");
        }
        verify(repository, never()).save(any(Project.class));
    }

    @Test
    public void test_create_with_invalid_source_url_throws_validation() throws Exception {
        try {
            service.create(new ProjectCreateRequest(
                    "foo", null, null, null, null, "not a uri", null, null, null, null, null, true));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("sourceUrl");
        } catch (ProjectExistsException e) {
            fail("unexpected ProjectExistsException");
        }
        verify(repository, never()).save(any(Project.class));
    }

    @Test
    public void test_create_with_invalid_logo_url_throws_validation() throws Exception {
        try {
            service.create(new ProjectCreateRequest(
                    "foo", null, null, null, null, null, null, null, "not a uri", null, null, true));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("logoUrl");
        } catch (ProjectExistsException e) {
            fail("unexpected ProjectExistsException");
        }
        verify(repository, never()).save(any(Project.class));
    }

    @Test
    public void test_delete_runs_full_cascade_in_order() throws Exception {
        Project project = new Project("foo");
        when(repository.getProject("foo")).thenReturn(project);
        when(repository.deleteAll("foo")).thenReturn(true);
        when(indexer.deleteAll("foo")).thenReturn(true);
        when(indexer.exists("foo.entities")).thenReturn(true);
        when(indexer.deleteAll("foo.entities")).thenReturn(true);
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
    public void test_delete_empties_the_entities_index_too() throws Exception {
        Project project = new Project("foo");
        when(repository.getProject("foo")).thenReturn(project);
        when(repository.deleteAll("foo")).thenReturn(true);
        when(indexer.deleteAll("foo")).thenReturn(true);
        when(indexer.exists("foo.entities")).thenReturn(true);
        when(indexer.deleteAll("foo.entities")).thenReturn(true);
        DocumentQueue<Path> queue = mock(DocumentQueue.class);
        when(queue.delete()).thenReturn(true);
        when(documentCollectionFactory.getQueues(any(String.class), eq(Path.class))).thenReturn(List.of(queue));
        ReportMap reportMap = mock(ReportMap.class);
        when(reportMap.delete()).thenReturn(true);
        when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
        when(propertiesProvider.createOverriddenWith(any())).thenReturn(new Properties());
        when(propertiesProvider.get(any())).thenReturn(Optional.empty());

        ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(false));

        verify(indexer).deleteAll("foo");
        verify(indexer).deleteAll("foo.entities");
        assertThat(deleted.indexDeleted()).isTrue();
    }

    @Test
    public void test_delete_reports_the_index_step_failed_when_only_the_entities_index_fails() throws Exception {
        Project project = new Project("foo");
        when(repository.getProject("foo")).thenReturn(project);
        when(repository.deleteAll("foo")).thenReturn(true);
        when(indexer.deleteAll("foo")).thenReturn(true);
        when(indexer.exists("foo.entities")).thenReturn(true);
        when(indexer.deleteAll("foo.entities")).thenThrow(new IOException("ES down"));
        DocumentQueue<Path> queue = mock(DocumentQueue.class);
        when(queue.delete()).thenReturn(true);
        when(documentCollectionFactory.getQueues(any(String.class), eq(Path.class))).thenReturn(List.of(queue));
        ReportMap reportMap = mock(ReportMap.class);
        when(reportMap.delete()).thenReturn(true);
        when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
        when(propertiesProvider.createOverriddenWith(any())).thenReturn(new Properties());
        when(propertiesProvider.get(any())).thenReturn(Optional.empty());

        ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(false));

        verify(indexer).deleteAll("foo.entities");
        assertThat(deleted.indexDeleted()).isFalse();
        assertThat(deleted.dbDeleted()).isTrue();
        assertThat(deleted.queuesDeleted()).isTrue();
    }

    @Test
    public void test_delete_reports_index_deleted_when_the_project_has_no_entities_index() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(repository.deleteAll("foo")).thenReturn(true);
        when(indexer.deleteAll("foo")).thenReturn(true);
        when(indexer.exists("foo.entities")).thenReturn(false);
        when(documentCollectionFactory.getQueues(any(String.class), eq(Path.class))).thenReturn(List.of());
        ReportMap reportMap = mock(ReportMap.class);
        when(reportMap.delete()).thenReturn(true);
        when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
        when(propertiesProvider.createOverriddenWith(any())).thenReturn(new Properties());
        when(propertiesProvider.get(any())).thenReturn(Optional.empty());

        ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(false));

        verify(indexer, never()).deleteAll("foo.entities");
        assertThat(deleted.indexDeleted()).isTrue();
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
            service.delete("ghost", new ProjectDeleteOptions(false));
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

        ProjectDeleted deleted = service.deleteIfExists("ghost", new ProjectDeleteOptions(false));

        assertThat(deleted.noop()).isTrue();
        assertThat(deleted.dbDeleted()).isFalse();
        assertThat(deleted.indexDeleted()).isFalse();
        verify(indexer, never()).deleteAll(any());
        verify(repository, never()).deleteAll(any());
    }

    @Test
    public void test_delete_continues_cascade_when_db_delete_fails() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(indexer.deleteAll("foo")).thenReturn(true);
        when(indexer.exists("foo.entities")).thenReturn(true);
        when(indexer.deleteAll("foo.entities")).thenReturn(true);
        when(repository.deleteAll("foo")).thenThrow(new RuntimeException("DB down"));
        DocumentQueue<Path> queue = mock(DocumentQueue.class);
        when(queue.delete()).thenReturn(true);
        when(documentCollectionFactory.getQueues(any(String.class), eq(Path.class)))
                .thenReturn(List.of(queue));
        ReportMap reportMap = mock(ReportMap.class);
        when(reportMap.delete()).thenReturn(true);
        when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
        when(propertiesProvider.createOverriddenWith(any())).thenReturn(new Properties());
        when(propertiesProvider.get(any())).thenReturn(Optional.empty());

        ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(false));

        // Cascade continues past the DB failure: queues and report-map still run.
        assertThat(deleted.indexDeleted()).isTrue();
        assertThat(deleted.dbDeleted()).isFalse();
        assertThat(deleted.queuesDeleted()).isTrue();
        assertThat(deleted.reportMapDeleted()).isTrue();
        verify(documentCollectionFactory).createMap(any());
    }

    @Test
    public void test_delete_continues_cascade_when_index_delete_fails() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(indexer.deleteAll("foo")).thenThrow(new IOException("ES down"));
        when(repository.deleteAll("foo")).thenReturn(true);
        DocumentQueue<Path> queue = mock(DocumentQueue.class);
        when(queue.delete()).thenReturn(true);
        when(documentCollectionFactory.getQueues(any(String.class), eq(Path.class)))
                .thenReturn(List.of(queue));
        ReportMap reportMap = mock(ReportMap.class);
        when(reportMap.delete()).thenReturn(true);
        when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
        when(propertiesProvider.createOverriddenWith(any())).thenReturn(new Properties());
        when(propertiesProvider.get(any())).thenReturn(Optional.empty());

        ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(false));

        assertThat(deleted.indexDeleted()).isFalse();
        assertThat(deleted.dbDeleted()).isTrue();
        assertThat(deleted.queuesDeleted()).isTrue();
        assertThat(deleted.reportMapDeleted()).isTrue();
    }

    @Test
    public void test_grant_writes_casbin_policy_and_appends_inventory_for_new_user() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", new HashMap<>());
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(userStore.save(any(User.class))).thenReturn(true);

        ProjectGranted granted = service.grant("banana", "jdoe",
                org.icij.datashare.policies.Role.PROJECT_EDITOR);

        assertThat(granted.name()).isEqualTo("banana");
        assertThat(granted.userLogin()).isEqualTo("jdoe");
        assertThat(granted.role()).isEqualTo(org.icij.datashare.policies.Role.PROJECT_EDITOR);
        assertThat(granted.previousRole()).isNull();
        assertThat(granted.noop()).isFalse();

        // Inventory write: groups_by_applications.datashare contains "banana".
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(savedUser.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) savedUser.getValue().details
                .get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) apps.get("datashare");
        assertThat(ds).contains("banana");

        // Casbin write: the new role grouping policy was added.
        verify(authorizer).addRoleForUserInProject(
                any(User.class),
                eq(org.icij.datashare.policies.Role.PROJECT_EDITOR),
                eq(Domain.DEFAULT),
                eq(project));
    }

    @Test
    public void test_grant_replaces_existing_role_and_reports_previousRole() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", new HashMap<>());
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_ADMIN"));
        when(userStore.save(any(User.class))).thenReturn(true);

        ProjectGranted granted = service.grant("banana", "jdoe",
                org.icij.datashare.policies.Role.PROJECT_EDITOR);

        assertThat(granted.previousRole()).isEqualTo(org.icij.datashare.policies.Role.PROJECT_ADMIN);
        assertThat(granted.role()).isEqualTo(org.icij.datashare.policies.Role.PROJECT_EDITOR);
        verify(authorizer).deleteRoleForUserInProject(
                any(User.class), eq(org.icij.datashare.policies.Role.PROJECT_ADMIN),
                eq(Domain.DEFAULT), eq(project));
        verify(authorizer).addRoleForUserInProject(
                any(User.class), eq(org.icij.datashare.policies.Role.PROJECT_EDITOR),
                eq(Domain.DEFAULT), eq(project));
    }

    @Test
    public void test_grant_throws_project_not_found_when_project_missing() {
        when(repository.getProject("ghost")).thenReturn(null);
        try {
            service.grant("ghost", "jdoe", org.icij.datashare.policies.Role.PROJECT_EDITOR);
            fail("expected ProjectNotFoundException");
        } catch (ProjectNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        } catch (Exception other) {
            fail("unexpected " + other);
        }
    }

    @Test
    public void test_grant_throws_user_not_found_when_user_missing() throws Exception {
        when(repository.getProject("banana")).thenReturn(new Project("banana"));
        when(users.find("ghost")).thenReturn(null);
        try {
            service.grant("banana", "ghost", org.icij.datashare.policies.Role.PROJECT_EDITOR);
            fail("expected UserNotFoundException");
        } catch (UserNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        } catch (Exception other) {
            fail("unexpected " + other);
        }
    }

    @Test
    public void test_grant_finds_user_via_users_provider() throws Exception {
        // Simulates --authUsersProvider UsersInRedis: user exists in Redis, not in SQL.
        // Users.find() is the sole lookup — no SQL fallback.
        Project project = new Project("local-datashare");
        when(repository.getProject("local-datashare")).thenReturn(project);
        User redisUser = new User("test", "Test User", "test@icij.org", "redis", new java.util.HashMap<>());
        when(users.find("test")).thenReturn(new DatashareUser(redisUser));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(userStore.save(any(User.class))).thenReturn(true);

        ProjectGranted granted = service.grant("local-datashare", "test",
                org.icij.datashare.policies.Role.PROJECT_MEMBER);

        assertThat(granted.userLogin()).isEqualTo("test");
        assertThat(granted.role()).isEqualTo(org.icij.datashare.policies.Role.PROJECT_MEMBER);
        assertThat(granted.noop()).isFalse();
        verify(authorizer).addRoleForUserInProject(
                any(User.class),
                eq(org.icij.datashare.policies.Role.PROJECT_MEMBER),
                eq(Domain.DEFAULT),
                eq(project));
    }

    @Test
    public void test_grant_updates_groups_by_applications_in_user_store_not_repository() throws Exception {
        // Regression: grant must persist updated project list via userStore, not repository.save(User).
        // Without this, Redis users would not see the granted project in the app (scenario 3).
        Project project = new Project("local-datashare");
        when(repository.getProject("local-datashare")).thenReturn(project);
        Map<String, Object> details = new HashMap<>();
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", details);
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(userStore.save(any(User.class))).thenReturn(true);

        service.grant("local-datashare", "jdoe", org.icij.datashare.policies.Role.PROJECT_MEMBER);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) saved.getValue().details.get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) apps.get("datashare");
        assertThat(ds).containsOnly("local-datashare");
        verify(repository, never()).save(any(User.class));
    }

    @Test
    public void test_grant_updates_session_cache_so_oauth_sessions_see_it_without_relogin() throws Exception {
        // Regression: grant must write through to the OAuth session cache (UsersIdProviderCache),
        // otherwise an already-logged-in user's cached DatashareUser never learns about a new
        // grant and isGranted() keeps returning stale/false until they log out and back in.
        Project project = new Project("local-datashare");
        when(repository.getProject("local-datashare")).thenReturn(project);
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", new HashMap<>());
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(userStore.save(any(User.class))).thenReturn(true);

        service.grant("local-datashare", "jdoe", org.icij.datashare.policies.Role.PROJECT_MEMBER);

        ArgumentCaptor<net.codestory.http.security.User> cached =
                ArgumentCaptor.forClass(net.codestory.http.security.User.class);
        verify(usersIdProviderCache).saveOrUpdate(cached.capture());
        DatashareUser cachedUser = (DatashareUser) cached.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) cachedUser.details.get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) apps.get("datashare");
        assertThat(ds).containsOnly("local-datashare");
    }

    @Test
    public void test_revoke_updates_session_cache_so_oauth_sessions_see_it_without_relogin() throws Exception {
        Project project = new Project("local-datashare");
        when(repository.getProject("local-datashare")).thenReturn(project);
        Map<String, Object> details = new HashMap<>();
        details.put("groups_by_applications", Map.of("datashare", List.of("local-datashare")));
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", details);
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_MEMBER"));
        when(userStore.save(any(User.class))).thenReturn(true);

        service.revoke("local-datashare", "jdoe");

        ArgumentCaptor<net.codestory.http.security.User> cached =
                ArgumentCaptor.forClass(net.codestory.http.security.User.class);
        verify(usersIdProviderCache).saveOrUpdate(cached.capture());
        DatashareUser cachedUser = (DatashareUser) cached.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) cachedUser.details.get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) apps.get("datashare");
        assertThat(ds).excludes("local-datashare");
    }

    @Test
    public void test_grant_rejects_non_project_roles() {
        when(repository.getProject("banana")).thenReturn(new Project("banana"));
        for (org.icij.datashare.policies.Role bad : new org.icij.datashare.policies.Role[]{
                org.icij.datashare.policies.Role.INSTANCE_ADMIN,
                org.icij.datashare.policies.Role.DOMAIN_ADMIN,
                org.icij.datashare.policies.Role.NONE}) {
            try {
                service.grant("banana", "jdoe", bad);
                fail("expected ValidationException for " + bad);
            } catch (ValidationException e) {
                assertThat(e.getMessage()).contains("PROJECT_");
            } catch (Exception other) {
                fail("unexpected " + other);
            }
        }
    }

    @Test
    public void test_grant_rolls_back_inventory_when_casbin_fails() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", new HashMap<>());
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(authorizer.addRoleForUserInProject(any(User.class), any(), any(), any()))
                .thenThrow(new RuntimeException("casbin boom"));
        when(userStore.save(any(User.class))).thenReturn(true);

        try {
            service.grant("banana", "jdoe", org.icij.datashare.policies.Role.PROJECT_EDITOR);
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("casbin boom");
        }

        ArgumentCaptor<User> savedUsers = ArgumentCaptor.forClass(User.class);
        verify(userStore, Mockito.times(2)).save(savedUsers.capture());
        java.util.List<User> saves = savedUsers.getAllValues();

        // Forward write: inventory contains "banana".
        @SuppressWarnings("unchecked")
        Map<String, Object> forwardApps = (Map<String, Object>) saves.get(0).details
                .get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> forwardDs = (List<String>) forwardApps.get("datashare");
        assertThat(forwardDs).contains("banana");

        // Rollback write: restored to the original empty inventory.
        Object rollbackApps = saves.get(1).details.get("groups_by_applications");
        if (rollbackApps instanceof Map<?, ?> map) {
            Object ds = map.get("datashare");
            if (ds instanceof List<?> list) {
                assertThat(list).excludes("banana");
            }
        }
    }

    @Test
    public void test_grantIfNotExists_returns_noop_when_user_already_holds_exact_role() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        when(users.find("jdoe")).thenReturn(new DatashareUser(
                new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", new HashMap<>())));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_EDITOR"));

        ProjectGranted granted = service.grantIfNotExists("banana", "jdoe",
                org.icij.datashare.policies.Role.PROJECT_EDITOR);

        assertThat(granted.noop()).isTrue();
        assertThat(granted.previousRole()).isNull();
        verify(userStore, never()).save(any(User.class));
        verify(authorizer, never()).addRoleForUserInProject(any(), any(), any(), any());
    }

    @Test
    public void test_grant_handles_malformed_groups_by_applications() throws Exception {
        // Inventory has groups_by_applications stored as a String instead of a Map
        // (e.g., from a hand-edited row). doGrant must not ClassCastException.
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        Map<String, Object> details = new HashMap<>();
        details.put("groups_by_applications", "not-a-map");
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", details);
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(userStore.save(any(User.class))).thenReturn(true);

        ProjectGranted granted = service.grant("banana", "jdoe",
                org.icij.datashare.policies.Role.PROJECT_ADMIN);

        assertThat(granted.role()).isEqualTo(org.icij.datashare.policies.Role.PROJECT_ADMIN);
        // The save still happened and now contains a well-formed map with "banana" in it.
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) saved.getValue().details
                .get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) apps.get("datashare");
        assertThat(ds).containsOnly("banana");
    }

    @Test
    public void test_grant_handles_malformed_datashare_list() throws Exception {
        // Inventory has groups_by_applications.datashare stored as a String instead
        // of a List. doGrant must not ClassCastException.
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        Map<String, Object> details = new HashMap<>();
        Map<String, Object> apps = new HashMap<>();
        apps.put("datashare", "not-a-list");
        details.put("groups_by_applications", apps);
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", details);
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(userStore.save(any(User.class))).thenReturn(true);

        ProjectGranted granted = service.grant("banana", "jdoe",
                org.icij.datashare.policies.Role.PROJECT_ADMIN);

        assertThat(granted.role()).isEqualTo(org.icij.datashare.policies.Role.PROJECT_ADMIN);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> savedApps = (Map<String, Object>) saved.getValue().details
                .get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) savedApps.get("datashare");
        assertThat(ds).containsOnly("banana");
    }

    @Test
    public void test_revoke_removes_casbin_policy_and_prunes_inventory_entry() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        Map<String, Object> details = new HashMap<>();
        Map<String, Object> apps = new HashMap<>();
        apps.put("datashare", new java.util.ArrayList<>(List.of("banana", "athena")));
        details.put("groups_by_applications", apps);
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", details);
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_EDITOR"));
        when(userStore.save(any(User.class))).thenReturn(true);

        ProjectRevoked revoked = service.revoke("banana", "jdoe");

        assertThat(revoked.name()).isEqualTo("banana");
        assertThat(revoked.userLogin()).isEqualTo("jdoe");
        assertThat(revoked.noop()).isFalse();
        assertThat(revoked.revokedRoles())
                .containsOnly(org.icij.datashare.policies.Role.PROJECT_EDITOR);

        verify(authorizer).deleteRoleForUserInProject(
                any(User.class), eq(org.icij.datashare.policies.Role.PROJECT_EDITOR),
                eq(Domain.DEFAULT), eq(project));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> savedApps = (Map<String, Object>) saved.getValue().details.get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) savedApps.get("datashare");
        assertThat(ds).containsOnly("athena");
    }

    @Test
    public void test_revoke_throws_project_not_found_when_project_missing() {
        when(repository.getProject("ghost")).thenReturn(null);
        try {
            service.revoke("ghost", "jdoe");
            fail("expected ProjectNotFoundException");
        } catch (ProjectNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        } catch (Exception other) {
            fail("unexpected " + other);
        }
    }

    @Test
    public void test_revoke_throws_user_not_found_when_user_missing() {
        when(repository.getProject("banana")).thenReturn(new Project("banana"));
        when(users.find("ghost")).thenReturn(null);
        try {
            service.revoke("banana", "ghost");
            fail("expected UserNotFoundException");
        } catch (UserNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        } catch (Exception other) {
            fail("unexpected " + other);
        }
    }

    @Test
    public void test_revokeIfExists_noop_when_user_missing() throws Exception {
        when(repository.getProject("banana")).thenReturn(new Project("banana"));
        when(users.find("ghost")).thenReturn(null);

        ProjectRevoked revoked = service.revokeIfExists("banana", "ghost");

        assertThat(revoked.noop()).isTrue();
        assertThat(revoked.revokedRoles()).isEmpty();
        verify(userStore, never()).save(any(User.class));
    }

    @Test
    public void test_revokeIfExists_noop_when_user_has_no_roles() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        when(users.find("jdoe")).thenReturn(new DatashareUser(
                new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", new HashMap<>())));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());

        ProjectRevoked revoked = service.revokeIfExists("banana", "jdoe");

        assertThat(revoked.noop()).isTrue();
        verify(userStore, never()).save(any(User.class));
    }

    @Test
    public void test_revokeIfExists_removes_casbin_and_prunes_inventory_when_user_has_roles() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        Map<String, Object> details = new HashMap<>();
        Map<String, Object> apps = new HashMap<>();
        apps.put("datashare", new java.util.ArrayList<>(List.of("banana", "athena")));
        details.put("groups_by_applications", apps);
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", details);
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_EDITOR"));
        when(userStore.save(any(User.class))).thenReturn(true);

        ProjectRevoked revoked = service.revokeIfExists("banana", "jdoe");

        assertThat(revoked.noop()).isFalse();
        assertThat(revoked.revokedRoles())
                .containsOnly(org.icij.datashare.policies.Role.PROJECT_EDITOR);
        verify(authorizer).deleteRoleForUserInProject(
                any(User.class), eq(org.icij.datashare.policies.Role.PROJECT_EDITOR),
                eq(Domain.DEFAULT), eq(project));
    }

    @Test
    public void test_revokeIfExists_still_throws_project_not_found() {
        when(repository.getProject("ghost")).thenReturn(null);
        try {
            service.revokeIfExists("ghost", "jdoe");
            fail("expected ProjectNotFoundException");
        } catch (ProjectNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        }
    }

    @Test
    public void test_revoke_rolls_back_inventory_when_casbin_fails() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        Map<String, Object> details = new HashMap<>();
        Map<String, Object> apps = new HashMap<>();
        apps.put("datashare", new java.util.ArrayList<>(List.of("banana", "athena")));
        details.put("groups_by_applications", apps);
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", details);
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_EDITOR"));
        when(authorizer.deleteRoleForUserInProject(any(User.class), any(), any(), any()))
                .thenThrow(new RuntimeException("casbin boom"));
        when(userStore.save(any(User.class))).thenReturn(true);

        try {
            service.revoke("banana", "jdoe");
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("casbin boom");
        }

        ArgumentCaptor<User> savedUsers = ArgumentCaptor.forClass(User.class);
        verify(userStore, Mockito.times(2)).save(savedUsers.capture());
        java.util.List<User> saves = savedUsers.getAllValues();

        // Forward write: "banana" pruned from inventory.
        @SuppressWarnings("unchecked")
        Map<String, Object> forwardApps = (Map<String, Object>) saves.get(0).details
                .get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> forwardDs = (List<String>) forwardApps.get("datashare");
        assertThat(forwardDs).excludes("banana");
        assertThat(forwardDs).contains("athena");

        // Rollback write: "banana" restored to inventory.
        @SuppressWarnings("unchecked")
        Map<String, Object> rollbackApps = (Map<String, Object>) saves.get(1).details
                .get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> rollbackDs = (List<String>) rollbackApps.get("datashare");
        assertThat(rollbackDs).contains("banana");
    }

    @Test
    public void test_add_projects_to_inventory_appends_every_project_without_writing_casbin() throws Exception {
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", new HashMap<>());
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(userStore.save(any(User.class))).thenReturn(true);

        service.addProjectsToInventory(List.of("foo", "bar"), "jdoe");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) saved.getValue().details.get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) apps.get("datashare");
        assertThat(ds).contains("foo").contains("bar");
        verifyNoInteractions(authorizer);
    }

    @Test
    public void test_remove_projects_from_inventory_prunes_every_project_without_writing_casbin() throws Exception {
        Map<String, Object> details = new HashMap<>();
        details.put("groups_by_applications", Map.of("datashare", List.of("foo", "bar", "baz")));
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", details);
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(userStore.save(any(User.class))).thenReturn(true);

        service.removeProjectsFromInventory(List.of("foo", "bar"), "jdoe");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) saved.getValue().details.get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) apps.get("datashare");
        assertThat(ds).excludes("foo").excludes("bar").contains("baz");
        verify(authorizer, never()).deleteRoleForUserInProject(any(), any(), any(), any());
    }

    @Test
    public void test_remove_projects_from_inventory_keeps_a_project_still_held_by_a_project_role() throws Exception {
        Map<String, Object> details = new HashMap<>();
        details.put("groups_by_applications", Map.of("datashare", List.of("foo", "bar")));
        User user = new User("jdoe", "Jane Doe", "jdoe@icij.org", "local", details);
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(userStore.save(any(User.class))).thenReturn(true);
        when(authorizer.getRolesForUserInProject(any(), eq(Domain.DEFAULT), argThat(project -> "foo".equals(project.getName()))))
                .thenReturn(List.of("PROJECT_MEMBER"));

        service.removeProjectsFromInventory(List.of("foo", "bar"), "jdoe");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) saved.getValue().details.get("groups_by_applications");
        @SuppressWarnings("unchecked")
        List<String> ds = (List<String>) apps.get("datashare");
        assertThat(ds).containsOnly("foo");
    }

    @Test
    public void test_backfill_adds_every_project_to_wide_admins_only() throws Exception {
        when(repository.getProjects()).thenReturn(List.of(new Project("foo"), new Project("bar")));
        when(authorizer.getGroupPermissions()).thenReturn(List.of(
                casbinRule("jdoe", "INSTANCE_ADMIN", "*::*"),
                casbinRule("egarcia", "DOMAIN_ADMIN", "default::*"),
                casbinRule("bob", "PROJECT_MEMBER", "default::foo"),
                casbinRule("ghost", "INSTANCE_ADMIN", "*::*")));
        when(users.find("jdoe")).thenReturn(new DatashareUser(new User("jdoe", "Jane", "j@icij.org", "local", new HashMap<>())));
        when(users.find("egarcia")).thenReturn(new DatashareUser(new User("egarcia", "Eva", "e@icij.org", "local", new HashMap<>())));
        when(users.find("ghost")).thenReturn(null);

        service.backfillWideAdminInventories();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore, Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().stream().map(u -> u.id).toList()).containsOnly("jdoe", "egarcia");
        for (User u : saved.getAllValues()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> apps = (Map<String, Object>) u.details.get("groups_by_applications");
            assertThat((List<?>) apps.get("datashare")).containsOnly("foo", "bar");
        }
        verify(users, never()).find("bob");
        verify(authorizer).deleteProjectRolesForUser(argThat(u -> "jdoe".equals(u.id)));
        verify(authorizer).deleteProjectRolesForUser(argThat(u -> "egarcia".equals(u.id)));
        verify(authorizer, Mockito.times(2)).deleteProjectRolesForUser(any());
    }

    @Test
    public void test_backfill_keeps_going_after_a_failure_on_one_admin() throws Exception {
        when(repository.getProjects()).thenReturn(List.of(new Project("foo")));
        when(authorizer.getGroupPermissions()).thenReturn(List.of(
                casbinRule("jdoe", "INSTANCE_ADMIN", "*::*"),
                casbinRule("egarcia", "DOMAIN_ADMIN", "default::*")));
        when(users.find("jdoe")).thenReturn(new DatashareUser(new User("jdoe", "Jane", "j@icij.org", "local", new HashMap<>())));
        when(users.find("egarcia")).thenReturn(new DatashareUser(new User("egarcia", "Eva", "e@icij.org", "local", new HashMap<>())));
        when(userStore.save(argThat(u -> u != null && "jdoe".equals(u.id)))).thenThrow(new RuntimeException("db down"));

        service.backfillWideAdminInventories();

        verify(userStore).save(argThat(u -> u != null && "egarcia".equals(u.id)));
        verify(authorizer).deleteProjectRolesForUser(argThat(u -> "egarcia".equals(u.id)));
    }

    @Test
    public void test_backfill_deletes_domain_admin_roles_of_instance_admins_only() throws Exception {
        when(repository.getProjects()).thenReturn(List.of());
        when(authorizer.getGroupPermissions()).thenReturn(List.of(
                casbinRule("jdoe", "INSTANCE_ADMIN", "*::*"),
                casbinRule("egarcia", "DOMAIN_ADMIN", "default::*")));
        when(users.find("jdoe")).thenReturn(new DatashareUser(new User("jdoe", "Jane", "j@icij.org", "local", new HashMap<>())));
        when(users.find("egarcia")).thenReturn(new DatashareUser(new User("egarcia", "Eva", "e@icij.org", "local", new HashMap<>())));
        when(authorizer.getRolesForUserInDomain(argThat(u -> u != null && "jdoe".equals(u.id)), eq(Domain.of("*"))))
                .thenReturn(List.of("INSTANCE_ADMIN"));

        service.backfillWideAdminInventories();

        verify(authorizer).deleteDomainRolesForUser(argThat(u -> "jdoe".equals(u.id)));
        verify(authorizer, Mockito.times(1)).deleteDomainRolesForUser(any());
    }

    @Test
    public void test_grant_throws_when_user_holds_a_wide_role() throws Exception {
        User user = new User("jdoe", "Jane", "j@icij.org", "local", new HashMap<>());
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(users.find("jdoe")).thenReturn(new DatashareUser(user));
        when(authorizer.holdsWideRole(any())).thenReturn(true);

        try {
            service.grant("foo", "jdoe", org.icij.datashare.policies.Role.PROJECT_MEMBER);
            fail("expected WideRoleHeldException");
        } catch (WideRoleHeldException e) {
            assertThat(e.getMessage()).contains("jdoe");
        }
        verify(userStore, never()).save(any());
        verify(authorizer, never()).addRoleForUserInProject(any(), any(), any(), any());
    }

    @Test
    public void test_create_adds_the_project_to_wide_admin_inventories() throws Exception {
        when(repository.getProject("my-project")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);
        when(authorizer.getGroupPermissions()).thenReturn(List.of(casbinRule("jdoe", "INSTANCE_ADMIN", "*::*")));
        when(users.find("jdoe")).thenReturn(new DatashareUser(new User("jdoe", "Jane", "j@icij.org", "local", new HashMap<>())));

        service.create(new ProjectCreateRequest("my-project", null, null, null, null, null, null, null, null, null,
                                                null, true));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) saved.getValue().details.get("groups_by_applications");
        assertThat((List<?>) apps.get("datashare")).containsOnly("my-project");
    }

    @Test
    public void test_add_project_to_wide_admin_inventories_appends_only_that_project() {
        Map<String, Object> details = new HashMap<>();
        details.put("groups_by_applications", Map.of("datashare", List.of("foo")));
        when(authorizer.getGroupPermissions()).thenReturn(List.of(casbinRule("jdoe", "INSTANCE_ADMIN", "*::*")));
        when(users.find("jdoe")).thenReturn(new DatashareUser(new User("jdoe", "Jane", "j@icij.org", "local", details)));

        service.addProjectToWideAdminInventories("bar");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> apps = (Map<String, Object>) saved.getValue().details.get("groups_by_applications");
        assertThat((List<?>) apps.get("datashare")).containsOnly("foo", "bar");
        verify(repository, never()).getProjects();
    }

    @Test
    public void test_add_project_to_wide_admin_inventories_does_not_throw_on_a_store_failure() {
        when(authorizer.getGroupPermissions()).thenReturn(List.of(casbinRule("jdoe", "INSTANCE_ADMIN", "*::*")));
        when(users.find("jdoe")).thenReturn(new DatashareUser(new User("jdoe", "Jane", "j@icij.org", "local", new HashMap<>())));
        when(userStore.save(any(User.class))).thenThrow(new RuntimeException("db down"));

        service.addProjectToWideAdminInventories("bar");
    }

    @Test
    public void test_add_projects_to_inventory_throws_user_not_found_when_user_missing() {
        when(users.find("ghost")).thenReturn(null);
        try {
            service.addProjectsToInventory(List.of("foo"), "ghost");
            fail("expected UserNotFoundException");
        } catch (UserNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        }
    }

    @Test
    public void test_remove_projects_from_inventory_throws_user_not_found_when_user_missing() {
        when(users.find("ghost")).thenReturn(null);
        try {
            service.removeProjectsFromInventory(List.of("foo"), "ghost");
            fail("expected UserNotFoundException");
        } catch (UserNotFoundException e) {
            assertThat(e.getMessage()).contains("ghost");
        }
    }

    private static CasbinRule casbinRule(String userId, String role, String domainProject) {
        return new CasbinRule("g", userId, role, domainProject);
    }

    // --- #2441: casbin rows and inventory entries on project delete ---

    @Test
    public void test_delete_removes_the_projects_casbin_rows() throws Exception {
        when(repository.getProject("proj")).thenReturn(new Project("proj"));
        when(repository.deleteAll("proj")).thenReturn(true);

        service.delete("proj", new ProjectDeleteOptions(true));

        verify(authorizer).removeAllPoliciesForProject(Domain.DEFAULT, "proj");
    }

    @Test
    public void test_delete_removes_the_project_from_every_users_inventory() throws Exception {
        when(repository.getProject("proj")).thenReturn(new Project("proj"));
        when(repository.deleteAll("proj")).thenReturn(true);
        User alice = userWithProjects("alice", List.of("proj", "keep"));
        User bob = userWithProjects("bob", List.of("keep"));
        when(userStore.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.of(alice, bob), 0, 2, 2));

        service.delete("proj", new ProjectDeleteOptions(true));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.id).isEqualTo("alice");
        assertThat(((Map<String, Object>) saved.details.get("groups_by_applications")).get("datashare"))
                .isEqualTo(List.of("keep"));
    }

    @Test
    public void test_delete_skips_a_user_whose_inventory_is_missing_or_malformed() throws Exception {
        when(repository.getProject("proj")).thenReturn(new Project("proj"));
        when(repository.deleteAll("proj")).thenReturn(true);
        User noDetails = new User("nodetails", "No Details", "n@e.test", "local", new HashMap<>());
        Map<String, Object> broken = new HashMap<>();
        broken.put("groups_by_applications", "not-a-map");
        User malformed = new User("broken", "Broken", "b@e.test", "local", broken);
        User alice = userWithProjects("alice", List.of("proj"));
        when(userStore.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.of(noDetails, malformed, alice), 0, 3, 3));

        service.delete("proj", new ProjectDeleteOptions(true));

        // the two unusable rows must not abort the sweep before alice is reached
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userStore).save(captor.capture());
        assertThat(captor.getValue().id).isEqualTo("alice");
    }

    @Test
    public void test_delete_still_deletes_the_db_row_when_the_casbin_cleanup_fails() throws Exception {
        when(repository.getProject("proj")).thenReturn(new Project("proj"));
        when(repository.deleteAll("proj")).thenReturn(true);
        User alice = userWithProjects("alice", List.of("proj"));
        when(userStore.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.of(alice), 0, 1, 1));
        doThrow(new RuntimeException("casbin down"))
                .when(authorizer).removeAllPoliciesForProject(Domain.DEFAULT, "proj");

        service.delete("proj", new ProjectDeleteOptions(true));

        // per-step containment: the failing step must not strand the rest of the cascade
        verify(repository).deleteAll("proj");
        verify(userStore).save(any(User.class));
    }

    @Test
    public void test_delete_keeps_the_grants_when_the_db_delete_fails() throws Exception {
        when(repository.getProject("proj")).thenReturn(new Project("proj"));
        when(repository.deleteAll("proj")).thenThrow(new RuntimeException("db down"));
        User alice = userWithProjects("alice", List.of("proj"));
        when(userStore.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.of(alice), 0, 1, 1));

        ProjectDeleted deleted = service.delete("proj", new ProjectDeleteOptions(true));

        // the project row survives, so wiping its grants would leave a live project nobody can
        // reach, and grants are the one cascade output that cannot be rebuilt
        assertThat(deleted.dbDeleted()).isFalse();
        verify(authorizer, never()).removeAllPoliciesForProject(any(), any());
        verify(userStore, never()).save(any(User.class));
    }

    @Test
    public void test_delete_reports_a_failed_casbin_cleanup() throws Exception {
        when(repository.getProject("proj")).thenReturn(new Project("proj"));
        when(repository.deleteAll("proj")).thenReturn(true);
        doThrow(new RuntimeException("casbin down"))
                .when(authorizer).removeAllPoliciesForProject(Domain.DEFAULT, "proj");

        ProjectDeleted deleted = service.delete("proj", new ProjectDeleteOptions(true));

        // reporting db OK and exiting 0 while the access cleanup failed is a silent security hole
        assertThat(deleted.casbinDeleted()).isFalse();
        assertThat(deleted.dbDeleted()).isTrue();
    }

    @Test
    public void test_delete_reports_a_successful_access_cleanup() throws Exception {
        when(repository.getProject("proj")).thenReturn(new Project("proj"));
        when(repository.deleteAll("proj")).thenReturn(true);

        ProjectDeleted deleted = service.delete("proj", new ProjectDeleteOptions(true));

        assertThat(deleted.casbinDeleted()).isTrue();
        assertThat(deleted.inventoryDeleted()).isTrue();
    }

    @Test
    public void test_delete_if_exists_clears_the_access_rows_of_a_missing_project() throws Exception {
        when(repository.getProject("ghost")).thenReturn(null);
        User alice = userWithProjects("alice", List.of("ghost"));
        when(userStore.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.of(alice), 0, 1, 1));

        ProjectDeleted deleted = service.deleteIfExists("ghost", new ProjectDeleteOptions(false));

        // rows outliving the project row are reachable from nowhere else, and a later project of the
        // same name would inherit them
        verify(authorizer).removeAllPoliciesForProject(Domain.DEFAULT, "ghost");
        verify(userStore).save(any(User.class));
        assertThat(deleted.noop()).isTrue();
        assertThat(deleted.casbinDeleted()).isTrue();
        assertThat(deleted.inventoryDeleted()).isTrue();
    }

    @Test
    public void test_delete_reports_a_failed_inventory_cleanup() throws Exception {
        when(repository.getProject("proj")).thenReturn(new Project("proj"));
        when(repository.deleteAll("proj")).thenReturn(true);
        User alice = userWithProjects("alice", List.of("proj"));
        when(userStore.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new WebResponse<>(List.of(alice), 0, 1, 1));
        doThrow(new RuntimeException("user store down")).when(userStore).save(any(User.class));

        ProjectDeleted deleted = service.delete("proj", new ProjectDeleteOptions(true));

        // alice keeps listing a deleted project, so the step must not report success
        assertThat(deleted.inventoryDeleted()).isFalse();
    }

    private static User userWithProjects(String id, List<String> projects) {
        Map<String, Object> details = new HashMap<>();
        Map<String, Object> apps = new HashMap<>();
        apps.put("datashare", new ArrayList<>(projects));
        details.put("groups_by_applications", apps);
        return new User(id, id, id + "@e.test", "local", details);
    }
}
