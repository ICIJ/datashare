package org.icij.datashare.tasks;

import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class GrantAdminPolicyTaskTest {
    @Mock
    public Authorizer authorizer;

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
        assertThat(rule.ptype).isEqualTo("g");
        assertThat(rule.v0).isEqualTo(user.getId());
        assertThat(rule.v1).isEqualTo("PROJECT_ADMIN");
        assertThat(rule.v2).isEqualTo("default::local-datashare");
        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of("default"), project).call()).isFalse();
    }
    @Before
    public void setUp() {
        initMocks(this);
    }
}