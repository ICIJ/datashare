package org.icij.datashare.project.admin;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.session.UsersIdProviderCache;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.ReportMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ProjectAdminServiceImplTest {

    private Repository repository;
    private Indexer indexer;
    private Authorizer authorizer;
    private DocumentCollectionFactory<Path> documentCollectionFactory;
    private PropertiesProvider propertiesProvider;
    private UsersIdProviderCache usersWritable;
    private ProjectAdminServiceImpl service;

    @Before
    public void setUp() {
        repository = mock(Repository.class);
        indexer = mock(Indexer.class);
        authorizer = mock(Authorizer.class);
        documentCollectionFactory = mock(DocumentCollectionFactory.class);
        propertiesProvider = mock(PropertiesProvider.class);
        usersWritable = mock(UsersIdProviderCache.class);
        service = new ProjectAdminServiceImpl(
                repository, indexer, authorizer, documentCollectionFactory, propertiesProvider, usersWritable);
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
        User user = new User("promera", "Pierre", "p@icij.org", "local", new HashMap<>());
        when(repository.getUser("promera")).thenReturn(user);
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(repository.save(any(User.class))).thenReturn(true);

        ProjectGranted granted = service.grant("banana", "promera",
                org.icij.datashare.policies.Role.PROJECT_EDITOR);

        assertThat(granted.name()).isEqualTo("banana");
        assertThat(granted.userLogin()).isEqualTo("promera");
        assertThat(granted.role()).isEqualTo(org.icij.datashare.policies.Role.PROJECT_EDITOR);
        assertThat(granted.previousRole()).isNull();
        assertThat(granted.noop()).isFalse();

        // Inventory write: groups_by_applications.datashare contains "banana".
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(repository).save(savedUser.capture());
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
        User user = new User("promera", "Pierre", "p@icij.org", "local", new HashMap<>());
        when(repository.getUser("promera")).thenReturn(user);
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_ADMIN"));
        when(repository.save(any(User.class))).thenReturn(true);

        ProjectGranted granted = service.grant("banana", "promera",
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
            service.grant("ghost", "promera", org.icij.datashare.policies.Role.PROJECT_EDITOR);
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
        when(repository.getUser("ghost")).thenReturn(null);
        when(usersWritable.find("ghost")).thenReturn(null);
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
    public void test_grant_falls_back_to_users_writable_when_user_not_in_sql() throws Exception {
        // Simulates a setup where --authUsersProvider UsersInRedis is set:
        // the user exists in Redis but has no SQL user_inventory row yet.
        Project project = new Project("local-datashare");
        when(repository.getProject("local-datashare")).thenReturn(project);
        when(repository.getUser("test")).thenReturn(null);
        User redisUser = new User("test", "Test User", "test@icij.org", "redis", new java.util.HashMap<>());
        when(usersWritable.find("test")).thenReturn(new org.icij.datashare.session.DatashareUser(redisUser));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(repository.save(any(User.class))).thenReturn(true);

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
    public void test_grant_rejects_non_project_roles() {
        when(repository.getProject("banana")).thenReturn(new Project("banana"));
        for (org.icij.datashare.policies.Role bad : new org.icij.datashare.policies.Role[]{
                org.icij.datashare.policies.Role.INSTANCE_ADMIN,
                org.icij.datashare.policies.Role.DOMAIN_ADMIN,
                org.icij.datashare.policies.Role.NONE}) {
            try {
                service.grant("banana", "promera", bad);
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
        User user = new User("promera", "Pierre", "p@icij.org", "local", new HashMap<>());
        when(repository.getUser("promera")).thenReturn(user);
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(authorizer.addRoleForUserInProject(any(User.class), any(), any(), any()))
                .thenThrow(new RuntimeException("casbin boom"));
        when(repository.save(any(User.class))).thenReturn(true);

        try {
            service.grant("banana", "promera", org.icij.datashare.policies.Role.PROJECT_EDITOR);
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("casbin boom");
        }

        ArgumentCaptor<User> savedUsers = ArgumentCaptor.forClass(User.class);
        verify(repository, Mockito.times(2)).save(savedUsers.capture());
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
        when(repository.getUser("promera")).thenReturn(
                new User("promera", "Pierre", "p@icij.org", "local", new HashMap<>()));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_EDITOR"));

        ProjectGranted granted = service.grantIfNotExists("banana", "promera",
                org.icij.datashare.policies.Role.PROJECT_EDITOR);

        assertThat(granted.noop()).isTrue();
        assertThat(granted.previousRole()).isNull();
        verify(repository, never()).save(any(User.class));
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
        User user = new User("promera", "Pierre", "p@icij.org", "local", details);
        when(repository.getUser("promera")).thenReturn(user);
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(repository.save(any(User.class))).thenReturn(true);

        ProjectGranted granted = service.grant("banana", "promera",
                org.icij.datashare.policies.Role.PROJECT_ADMIN);

        assertThat(granted.role()).isEqualTo(org.icij.datashare.policies.Role.PROJECT_ADMIN);
        // The save still happened and now contains a well-formed map with "banana" in it.
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).save(saved.capture());
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
        User user = new User("promera", "Pierre", "p@icij.org", "local", details);
        when(repository.getUser("promera")).thenReturn(user);
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());
        when(repository.save(any(User.class))).thenReturn(true);

        ProjectGranted granted = service.grant("banana", "promera",
                org.icij.datashare.policies.Role.PROJECT_ADMIN);

        assertThat(granted.role()).isEqualTo(org.icij.datashare.policies.Role.PROJECT_ADMIN);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).save(saved.capture());
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
        User user = new User("promera", "Pierre", "p@icij.org", "local", details);
        when(repository.getUser("promera")).thenReturn(user);
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_EDITOR"));
        when(repository.save(any(User.class))).thenReturn(true);

        ProjectRevoked revoked = service.revoke("banana", "promera");

        assertThat(revoked.name()).isEqualTo("banana");
        assertThat(revoked.userLogin()).isEqualTo("promera");
        assertThat(revoked.noop()).isFalse();
        assertThat(revoked.revokedRoles())
                .containsOnly(org.icij.datashare.policies.Role.PROJECT_EDITOR);

        verify(authorizer).deleteRoleForUserInProject(
                any(User.class), eq(org.icij.datashare.policies.Role.PROJECT_EDITOR),
                eq(Domain.DEFAULT), eq(project));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).save(saved.capture());
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
            service.revoke("ghost", "promera");
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
        when(repository.getUser("ghost")).thenReturn(null);
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
        when(repository.getUser("ghost")).thenReturn(null);

        ProjectRevoked revoked = service.revokeIfExists("banana", "ghost");

        assertThat(revoked.noop()).isTrue();
        assertThat(revoked.revokedRoles()).isEmpty();
        verify(repository, never()).save(any(User.class));
    }

    @Test
    public void test_revokeIfExists_noop_when_user_has_no_roles() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        when(repository.getUser("promera")).thenReturn(
                new User("promera", "Pierre", "p@icij.org", "local", new HashMap<>()));
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of());

        ProjectRevoked revoked = service.revokeIfExists("banana", "promera");

        assertThat(revoked.noop()).isTrue();
        verify(repository, never()).save(any(User.class));
    }

    @Test
    public void test_revokeIfExists_removes_casbin_and_prunes_inventory_when_user_has_roles() throws Exception {
        Project project = new Project("banana");
        when(repository.getProject("banana")).thenReturn(project);
        Map<String, Object> details = new HashMap<>();
        Map<String, Object> apps = new HashMap<>();
        apps.put("datashare", new java.util.ArrayList<>(List.of("banana", "athena")));
        details.put("groups_by_applications", apps);
        User user = new User("promera", "Pierre", "p@icij.org", "local", details);
        when(repository.getUser("promera")).thenReturn(user);
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_EDITOR"));
        when(repository.save(any(User.class))).thenReturn(true);

        ProjectRevoked revoked = service.revokeIfExists("banana", "promera");

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
            service.revokeIfExists("ghost", "promera");
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
        User user = new User("promera", "Pierre", "p@icij.org", "local", details);
        when(repository.getUser("promera")).thenReturn(user);
        when(authorizer.getRolesForUserInProject(any(User.class), eq(Domain.DEFAULT), eq(project)))
                .thenReturn(List.of("PROJECT_EDITOR"));
        when(authorizer.deleteRoleForUserInProject(any(User.class), any(), any(), any()))
                .thenThrow(new RuntimeException("casbin boom"));
        when(repository.save(any(User.class))).thenReturn(true);

        try {
            service.revoke("banana", "promera");
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("casbin boom");
        }

        ArgumentCaptor<User> savedUsers = ArgumentCaptor.forClass(User.class);
        verify(repository, Mockito.times(2)).save(savedUsers.capture());
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

    private static CasbinRule casbinRule(String userId, String role, String domainProject) {
        return new CasbinRule("g", userId, role, domainProject);
    }
}
