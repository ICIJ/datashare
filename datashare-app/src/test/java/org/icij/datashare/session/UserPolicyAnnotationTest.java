package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserRepository;
import org.junit.Before;
import org.junit.Test;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UserPolicyAnnotationTest {
    private UserRepository userRepository;
    private UserPolicyAnnotation annotation;
    private Context context;
    private DatashareUser adminUser;
    private DatashareUser nonAdminUser;
    private Policy policy;
    String projectId = "test-datashare";
    UserPolicy adminPermission;
    UserPolicy nonAdminPermission;

    @Before
    public void setUp() throws URISyntaxException {
        userRepository = mock(UserRepository.class);
        String adminId = "cecile";
        adminPermission = new UserPolicy(adminId, projectId, new Role[]{Role.ADMIN});
        Map<String, Object> adminUserMap = Map.of(
                "uid", adminId,
                "policies", Set.of(adminPermission)
        );
        adminUser = new DatashareUser(adminUserMap);

        String nonAdminId = "john";
        nonAdminPermission = new UserPolicy(nonAdminId, projectId, new Role[]{Role.READER});
        Map<String, Object> nonAdminUserMap = Map.of(
                "uid", nonAdminId,
                "policies", Set.of(nonAdminPermission)
        );
        nonAdminUser = new DatashareUser(nonAdminId);

        when(userRepository.getAll()).thenReturn(List.of(adminPermission,nonAdminPermission));

        annotation = new UserPolicyAnnotation(userRepository);
        context = mock(Context.class);
        policy = mock(Policy.class);

        projectId = "test-datashare";

    }

    @Test(expected = UnauthorizedException.class)
    public void should_throw_unauthorized_if_no_user() {
        when(context.currentUser()).thenReturn(null);
        when(context.pathParam("index")).thenReturn(projectId);
        annotation.apply(policy, context, (c) -> Payload.ok());
    }

    @Test(expected = ForbiddenException.class)
    public void should_return_forbidden_if_no_policy() {
        when(context.currentUser()).thenReturn(nonAdminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        when(userRepository.get(nonAdminUser, projectId)).thenReturn(null);
        annotation.apply(policy, context, (c) -> Payload.forbidden());
    }

    @Test
    public void should_return_forbidden_if_wrong_policy() {
        when(context.currentUser()).thenReturn(nonAdminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        when(userRepository.get(nonAdminUser, projectId)).thenReturn(null);
        Payload result = annotation.apply(policy, context,c->Payload.forbidden());
        assertEquals(403, result.code());
    }

    @Test
    public void should_allow_user_if_has_policy() {
        when(context.currentUser()).thenReturn(adminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        when(userRepository.get(adminUser, projectId)).thenReturn(adminPermission);
        Payload result = annotation.apply(policy, context, (c) -> Payload.ok());
        assertEquals(200, result.code());
    }
}
