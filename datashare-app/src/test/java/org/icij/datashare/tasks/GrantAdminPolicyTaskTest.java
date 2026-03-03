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
import org.mockito.Mockito;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class GrantAdminPolicyTaskTest {
    @Mock
    public Authorizer authorizer;

    @Test
    public void test_call_grant_admin_for_existing_user_and_project() {
        ArgumentCaptor<String> userId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> projectId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Domain> domain = ArgumentCaptor.forClass(Domain.class);
        Project project = Project.project("local-datashare");
        when(authorizer.addProjectAdmin(any(), any(), any())).thenReturn(true);

        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of("default"), project).call()).isTrue();

        verify(authorizer).addProjectAdmin(userId.capture(), domain.capture(), projectId.capture());

        assertThat(userId.getValue()).isEqualTo(User.local().getId());
        assertThat(domain.getValue().id()).isEqualTo("default");
        assertThat(projectId.getValue()).isEqualTo(project.getId());
    }

    @Test
    public void test_call_returns_true_when_admin_already_exists() {
        Project project = Project.project("unknown-project");
        when(authorizer.addProjectAdmin(any(), any(), any())).thenReturn(false);
        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of("default"), project).call()).isTrue();
    }

    @Test
    public void test_call_creates_casbin_rule() {
        CasbinRuleAdapter adapter = Mockito.mock(CasbinRuleAdapter.class);
        Authorizer realAuthorizer = new Authorizer(adapter, false);
        Project project = Project.project("local-datashare");
        Domain domain = Domain.of("default");
        User user = User.local();

        assertThat(new GrantAdminPolicyTask(realAuthorizer, user, domain, project).call()).isTrue();

        List<CasbinRule> permissions = realAuthorizer.getGroupPermissions(user.getId(), domain, project.getId());
        assertThat(permissions).hasSize(1);
        CasbinRule rule = permissions.get(0);
        assertThat(rule.ptype).isEqualTo("g");
        assertThat(rule.v0).isEqualTo(user.getId());
        assertThat(rule.v1).isEqualTo("PROJECT_ADMIN");
        assertThat(rule.v2).isEqualTo("default::local-datashare");
    }

    @Before
    public void setUp() {
        initMocks(this);
    }
}