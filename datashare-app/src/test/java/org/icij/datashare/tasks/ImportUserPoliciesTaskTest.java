package org.icij.datashare.tasks;

import org.icij.datashare.Repository;
import org.icij.datashare.policies.*;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

public class ImportUserPoliciesTaskTest {
    @Mock
    public Authorizer authorizer;
    @Mock
    public Repository repository;
    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = openMocks(this);
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void test_no_users_returns_zero() throws Exception {
        when(repository.listUsers()).thenReturn(emptyList());
        assertThat(new ImportUserPoliciesTask(authorizer, repository).call()).isEqualTo(0);
        verifyNoInteractions(authorizer);
    }

    @Test
    public void test_user_with_no_projects_returns_zero() throws Exception {
        User user = new User("uid", "name", "email", "oauth2", Map.of());
        when(repository.listUsers()).thenReturn(List.of(user));
        assertThat(new ImportUserPoliciesTask(authorizer, repository).call()).isEqualTo(0);
        verifyNoInteractions(authorizer);
    }

    @Test
    public void test_single_user_single_project() throws Exception {
        User user = User.localUser("alice", "project-a");
        when(repository.listUsers()).thenReturn(List.of(user));

        assertThat(new ImportUserPoliciesTask(authorizer, repository).call()).isEqualTo(1);

        verify(authorizer).addRoleForUserInProject(user, Role.PROJECT_MEMBER, Domain.DEFAULT, new Project("project-a"));
    }

    @Test
    public void test_single_user_multiple_projects() throws Exception {
        User user = User.localUser("bob", "p1", "p2", "p3");
        when(repository.listUsers()).thenReturn(List.of(user));

        assertThat(new ImportUserPoliciesTask(authorizer, repository).call()).isEqualTo(3);

        verify(authorizer).addRoleForUserInProject(user, Role.PROJECT_MEMBER, Domain.DEFAULT, new Project("p1"));
        verify(authorizer).addRoleForUserInProject(user, Role.PROJECT_MEMBER, Domain.DEFAULT, new Project("p2"));
        verify(authorizer).addRoleForUserInProject(user, Role.PROJECT_MEMBER, Domain.DEFAULT, new Project("p3"));
    }

    @Test
    public void test_multiple_users_multiple_projects() throws Exception {
        User alice = User.localUser("alice", "proj-x");
        User bob = User.localUser("bob", "proj-x", "proj-y");
        when(repository.listUsers()).thenReturn(List.of(alice, bob));

        assertThat(new ImportUserPoliciesTask(authorizer, repository).call()).isEqualTo(3);

        verify(authorizer, times(3)).addRoleForUserInProject(any(), eq(Role.PROJECT_MEMBER), eq(Domain.DEFAULT), any());
    }

    @Test
    public void test_creates_correct_casbin_rule_with_real_authorizer() throws Exception {
        CasbinRuleAdapter adapter = mock(CasbinRuleAdapter.class);
        Authorizer realAuthorizer = new Authorizer(adapter);
        User user = User.localUser("alice", "project-a");
        when(repository.listUsers()).thenReturn(List.of(user));

        new ImportUserPoliciesTask(realAuthorizer, repository).call();

        List<CasbinRule> permissions = realAuthorizer.getGroupPermissions(user, Domain.DEFAULT, "project-a");
        assertThat(permissions).hasSize(1);
        CasbinRule rule = permissions.get(0);
        assertThat(rule.getPtype()).isEqualTo("g");
        assertThat(rule.getV0()).isEqualTo("alice");
        assertThat(rule.getV1()).isEqualTo("PROJECT_MEMBER");
        assertThat(rule.getV2()).isEqualTo("default::project-a");
    }

    @Test
    public void test_idempotent_with_real_authorizer() throws Exception {
        CasbinRuleAdapter adapter = mock(CasbinRuleAdapter.class);
        Authorizer realAuthorizer = new Authorizer(adapter);
        User user = User.localUser("alice", "project-a");
        when(repository.listUsers()).thenReturn(List.of(user));
        ImportUserPoliciesTask task = new ImportUserPoliciesTask(realAuthorizer, repository);

        assertThat(task.call()).isEqualTo(1);
        assertThat(task.call()).isEqualTo(1);

        assertThat(realAuthorizer.getGroupPermissions(user, Domain.DEFAULT, "project-a")).hasSize(1);
    }
}
