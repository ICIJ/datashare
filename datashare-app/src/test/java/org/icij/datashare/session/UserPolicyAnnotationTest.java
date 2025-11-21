package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserRepository;
import org.junit.Before;
import org.junit.Test;

import java.lang.annotation.Annotation;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.icij.datashare.user.Role.ADMIN;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UserPolicyAnnotationTest {
    private UserPolicyAnnotation annotation;
    private Context context;
    private DatashareUser adminUser;
    private DatashareUser nonAdminUser;

    private Policy policy;
    private String projectId = "test-datashare";

    @Before
    public void setUp() throws URISyntaxException {
        projectId = "test-datashare";
        String adminId = "cecile";
        UserPolicy adminPermission = new UserPolicy(adminId, projectId, new Role[]{Role.ADMIN});
        adminUser = new DatashareUser(Map.of(
                "uid", adminId,
                "policies", Set.of(adminPermission)
        ));

        String nonAdminId = "john";
        UserPolicy nonAdminPermission = new UserPolicy(nonAdminId, projectId, new Role[]{Role.READER});
        nonAdminUser = new DatashareUser( Map.of(
                "uid", nonAdminId,
                "policies", Set.of(nonAdminPermission)
        ));


        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.getAll()).thenReturn(List.of(adminPermission, nonAdminPermission));
        annotation = new UserPolicyAnnotation(userRepository);

        context = mock(Context.class);

        policy = new Policy() {
           @Override
           public Class<? extends Annotation> annotationType() {
               return null;
           }

           @Override
            public Role[] roles() {
                return new Role[]{ADMIN};
            }
        };

    }

    @Test(expected = UnauthorizedException.class)
    public void should_throw_unauthorized_if_no_user() {
        when(context.currentUser()).thenReturn(null);
        when(context.pathParam("index")).thenReturn(projectId);
        annotation.apply(policy, context, (c) -> Payload.ok());
    }

    @Test
    public void should_return_forbidden_if_no_policy() {

        DatashareUser noPolicyUser = new DatashareUser(  "jane");
        when(context.currentUser()).thenReturn(noPolicyUser);

        when(context.pathParam("index")).thenReturn(projectId);

        Payload result = annotation.apply(policy, context,c->Payload.ok());
        assertEquals(403, result.code());
    }
    @Test
    public void should_allow_user_if_has_wrong_policy_as_non_admin() {
        when(context.currentUser()).thenReturn(nonAdminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        Payload result = annotation.apply(policy, context, (c) -> Payload.ok());
        assertEquals(403, result.code());
    }
    @Test
    public void should_allow_user_if_has_right_policy_as_admin() {
        when(context.currentUser()).thenReturn(adminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        Payload result = annotation.apply(policy, context, (c) -> Payload.ok());
        assertEquals(200, result.code());
    }
}
