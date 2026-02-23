package org.icij.datashare.tasks;

import org.icij.datashare.policies.Domain;
import org.icij.datashare.session.Authorizer;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

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
        ArgumentCaptor<String> domainId = ArgumentCaptor.forClass(String.class);
        Project project = Project.project("local-datashare");
        when(authorizer.addProjectAdmin(any(), any(), any())).thenReturn(true);

        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of(""), project).call()).isTrue();

        verify(authorizer).addProjectAdmin(userId.capture(), Domain.of(domainId.capture()), projectId.capture());

        assertThat(userId.getValue()).isEqualTo(User.local().getId());
        assertThat(domainId.getValue()).isEqualTo("");
        assertThat(projectId.getValue()).isEqualTo(project.getId());
    }

    @Test
    public void test_call_throws_exception_when_verifier_fails() {
        Project project = Project.project("unknown-project");
        when(authorizer.addProjectAdmin(any(), any(), any())).thenReturn(false);
        assertThat(new GrantAdminPolicyTask(authorizer, User.local(), Domain.of(""), project).call()).isFalse();
    }

    @Before
    public void setUp() {
        initMocks(this);
    }
}