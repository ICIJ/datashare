package org.icij.datashare.tasks;

import org.icij.datashare.policies.*;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
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

    private static CasbinRule casbinRule(String v0, String v1, String v2) {
        return CasbinRule.fromArray(List.of("g", v0, v1, v2));
    }

    @Test
    public void test_grant_succeeds_for_new_user_no_existing_admin() {
        when(authorizer.getGroupPermissions()).thenReturn(List.of());
        when(authorizer.addRoleForUserInInstance(User.local(), Role.INSTANCE_ADMIN)).thenReturn(true);

        assertThat(new GrantAdminPolicyTask(authorizer, User.local()).call()).isTrue();

        verify(authorizer).addRoleForUserInInstance(User.local(), Role.INSTANCE_ADMIN);
    }

    @Test
    public void test_grant_returns_true_when_user_already_has_instance_admin() {
        CasbinRule existingRule = casbinRule(User.local().getId(), Role.INSTANCE_ADMIN.name(), "*::*");
        when(authorizer.getGroupPermissions()).thenReturn(List.of(existingRule));

        assertThat(new GrantAdminPolicyTask(authorizer, User.local()).call()).isTrue();

        verify(authorizer, never()).addRoleForUserInInstance(any(), any());
    }

    @Test
    public void test_grant_returns_false_when_different_user_already_has_instance_admin() {
        CasbinRule existingRule = casbinRule("other-user", Role.INSTANCE_ADMIN.name(), "*::*");
        when(authorizer.getGroupPermissions()).thenReturn(List.of(existingRule));

        assertThat(new GrantAdminPolicyTask(authorizer, User.local()).call()).isFalse();

        verify(authorizer, never()).addRoleForUserInInstance(any(), any());
    }

    @Test
    public void test_grant_returns_true_when_add_fails_but_can_confirms_concurrent_grant() {
        when(authorizer.getGroupPermissions()).thenReturn(List.of());
        when(authorizer.addRoleForUserInInstance(User.local(), Role.INSTANCE_ADMIN)).thenReturn(false);
        when(authorizer.can(User.local().getId(), Domain.of("*"), "*", Role.INSTANCE_ADMIN)).thenReturn(true);

        assertThat(new GrantAdminPolicyTask(authorizer, User.local()).call()).isTrue();
    }

    @Test
    public void test_grant_returns_false_when_add_fails_and_can_also_fails() {
        when(authorizer.getGroupPermissions()).thenReturn(List.of());
        when(authorizer.addRoleForUserInInstance(User.local(), Role.INSTANCE_ADMIN)).thenReturn(false);
        when(authorizer.can(User.local().getId(), Domain.of("*"), "*", Role.INSTANCE_ADMIN)).thenReturn(false);

        assertThat(new GrantAdminPolicyTask(authorizer, User.local()).call()).isFalse();
    }

    @Test
    public void test_grant_is_idempotent_with_real_authorizer() throws IOException {
        CasbinRuleAdapter adapter = mock(CasbinRuleAdapter.class);
        Authorizer realAuthorizer = new Authorizer(adapter);
        User user = User.local();
        GrantAdminPolicyTask task = new GrantAdminPolicyTask(realAuthorizer, user);

        assertThat(task.call()).isTrue();
        assertThat(task.call()).isTrue();

        long instanceAdminCount = realAuthorizer.getGroupPermissions().stream()
                .filter(r -> Role.INSTANCE_ADMIN.name().equals(r.getV1()) && "*::*".equals(r.getV2()))
                .filter(r -> user.getId().equals(r.getV0()))
                .count();
        assertThat(instanceAdminCount).isEqualTo(1);
    }
}
