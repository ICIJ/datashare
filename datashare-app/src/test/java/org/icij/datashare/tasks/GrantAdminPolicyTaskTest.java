package org.icij.datashare.tasks;

import org.icij.datashare.policies.*;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

public class GrantAdminPolicyTaskTest {
    @Mock
    public Authorizer authorizer;
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
    public void test_call_grant_admin_for_existing_user_and_project() {
        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<Project> project = ArgumentCaptor.forClass(Project.class);
        ArgumentCaptor<Domain> domain = ArgumentCaptor.forClass(Domain.class);
        Project projectVal = Project.project("local-datashare");
        when(authorizer.addProjectAdmin(any(), any(), any())).thenReturn(true);

        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of("default"), projectVal).call()).isTrue();

        verify(authorizer).addProjectAdmin(user.capture(), domain.capture(), project.capture());

        assertThat(user.getValue().getId()).isEqualTo(User.local().getId());
        assertThat(domain.getValue().id()).isEqualTo("default");
        assertThat(project.getValue().getId()).isEqualTo(projectVal.getId());
    }

    @Test
    public void test_call_throws_exception_when_verifier_fails() {
        Project project = Project.project("unknown-project");
        when(authorizer.addProjectAdmin(any(), any(), any())).thenReturn(false);
        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of("default"), project).call()).isFalse();
    }

    @Test
    public void test_call_creates_casbin_rule() {
        CasbinRuleAdapter adapter = mock(CasbinRuleAdapter.class);
        Authorizer realAuthorizer = new Authorizer(adapter);
        Project project = Project.project("local-datashare");
        Domain domain = Domain.of("default");
        User user = User.local();

        assertThat(new GrantAdminPolicyTask(realAuthorizer, user, domain, project).call()).isTrue();

        List<CasbinRule> permissions = realAuthorizer.getGroupPermissions(user, domain, project.getId());
        assertThat(permissions).hasSize(1);
        CasbinRule rule = permissions.get(0);
        assertThat(rule.getPtype()).isEqualTo("g");
        assertThat(rule.getV0()).isEqualTo(user.getId());
        assertThat(rule.getV1()).isEqualTo("PROJECT_ADMIN");
        assertThat(rule.getV2()).isEqualTo("default::local-datashare");
        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of("default"), project).call()).isFalse();
    }

    @Test
    public void test_call_returns_true_when_add_fails_due_to_concurrent_grant() {
        // Simulates race: addProjectAdmin() = false (concurrent insert), can() = true
        Project project = Project.project("local-datashare");
        when(authorizer.addProjectAdmin(any(), any(), any())).thenReturn(false);
        when(authorizer.can(User.local().getId(), Domain.of("default"), project.getId(), Role.PROJECT_ADMIN))
                .thenReturn(true);

        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of("default"), project).call()).isTrue();
    }

    @Test
    public void test_call_returns_true_when_user_already_has_admin_role() {
        Project project = Project.project("local-datashare");
        when(authorizer.addProjectAdmin(any(), any(), any())).thenReturn(false);
        when(authorizer.can(User.local().getId(), Domain.of("default"), project.getId(), Role.PROJECT_ADMIN)).thenReturn(true);

        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of("default"), project).call()).isTrue();
    }

    @Test
    public void test_call_is_idempotent_with_real_authorizer() {
        CasbinRuleAdapter adapter = mock(CasbinRuleAdapter.class);
        Authorizer realAuthorizer = new Authorizer(adapter);
        Project project = Project.project("local-datashare");
        Domain domain = Domain.of("default");
        User user = User.local();
        GrantAdminPolicyTask task = new GrantAdminPolicyTask(realAuthorizer, user, domain, project);

        assertThat(task.call()).isTrue();
        assertThat(task.call()).isTrue();

        assertThat(realAuthorizer.getGroupPermissions(user, domain, project.getId())).hasSize(1);
    }

    @Test
    public void test_call_grants_admin_to_multiple_users_on_same_project() {
        CasbinRuleAdapter adapter = mock(CasbinRuleAdapter.class);
        Authorizer realAuthorizer = new Authorizer(adapter);
        Project project = Project.project("local-datashare");
        Domain domain = Domain.of("default");
        User user1 = User.local();
        User user2 = new User("other-user");

        assertThat(new GrantAdminPolicyTask(realAuthorizer, user1, domain, project).call()).isTrue();
        assertThat(new GrantAdminPolicyTask(realAuthorizer, user2, domain, project).call()).isTrue();

        assertThat(realAuthorizer.getGroupPermissions(domain, project.getId())).hasSize(2);
    }

    @Test
    public void test_call_grants_admin_to_same_user_on_multiple_projects() {
        CasbinRuleAdapter adapter = mock(CasbinRuleAdapter.class);
        Authorizer realAuthorizer = new Authorizer(adapter);
        Domain domain = Domain.of("default");
        User user = User.local();
        Project project1 = Project.project("project-one");
        Project project2 = Project.project("project-two");

        assertThat(new GrantAdminPolicyTask(realAuthorizer, user, domain, project1).call()).isTrue();
        assertThat(new GrantAdminPolicyTask(realAuthorizer, user, domain, project2).call()).isTrue();

        assertThat(realAuthorizer.getGroupPermissions(user, domain, project1.getId())).hasSize(1);
        assertThat(realAuthorizer.getGroupPermissions(user, domain, project2.getId())).hasSize(1);
    }

    @Test
    public void test_call_creates_separate_rules_for_different_domains() {
        CasbinRuleAdapter adapter = mock(CasbinRuleAdapter.class);
        Authorizer realAuthorizer = new Authorizer(adapter);
        Project project = Project.project("local-datashare");
        User user = User.local();
        Domain domain1 = Domain.of("domain-a");
        Domain domain2 = Domain.of("domain-b");

        assertThat(new GrantAdminPolicyTask(realAuthorizer, user, domain1, project).call()).isTrue();
        assertThat(new GrantAdminPolicyTask(realAuthorizer, user, domain2, project).call()).isTrue();

        assertThat(realAuthorizer.getGroupPermissions(user, domain1, project.getId())).hasSize(1);
        assertThat(realAuthorizer.getGroupPermissions(user, domain2, project.getId())).hasSize(1);
        CasbinRule rule = realAuthorizer.getGroupPermissions(user, domain1, project.getId()).get(0);
        assertThat(rule.getV2()).isEqualTo("domain-a::local-datashare");
    }


}