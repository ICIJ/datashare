package org.icij.datashare.tasks;

import org.icij.datashare.RecordNotFoundException;
import org.icij.datashare.session.UserPolicyVerifier;
import org.icij.datashare.text.Project;

import org.icij.datashare.user.Role;
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
    public UserPolicyVerifier userPolicyVerifier;

    @Test
    public void test_call_grant_admin_for_existing_user_and_project() throws Exception {
        ArgumentCaptor<String> userId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> projectId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Role[]> roles = ArgumentCaptor.forClass(Role[].class);
        Project project = Project.project("local-datashare");
        when(userPolicyVerifier.grantAdminIfNoneExists(any(), any())).thenCallRealMethod();
        when(userPolicyVerifier.saveUserPolicy(any(), any(), any())).thenReturn(true);

        assertThat(new GrantAdminPolicyTask(userPolicyVerifier, User.local(), project).call()).isTrue();

        verify(userPolicyVerifier).saveUserPolicy(userId.capture(), projectId.capture(), roles.capture());

        assertThat(userId.getValue()).isEqualTo(User.local().getId());
        assertThat(projectId.getValue()).isEqualTo(project.getId());
        assertThat(roles.getValue()).contains(Role.ADMIN);
    }

    @Test
    public void test_call_throws_exception_when_verifier_fails() throws Exception {
        Project project = Project.project("unknown-project");
        when(userPolicyVerifier.saveUserPolicy(any(),any(),any()))
                .thenThrow(new RecordNotFoundException(Project.class, project.getId()));

        assertThat(new GrantAdminPolicyTask(userPolicyVerifier, User.local(), project).call()).isFalse();
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
    }
}