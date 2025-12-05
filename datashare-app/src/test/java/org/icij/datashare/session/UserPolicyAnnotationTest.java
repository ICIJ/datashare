package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.icij.datashare.user.UserPolicyVerifier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.annotation.Annotation;
import java.net.URISyntaxException;

import static junit.framework.TestCase.assertEquals;
import static org.icij.datashare.user.Role.ADMIN;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class UserPolicyAnnotationTest {
    private UserPolicyAnnotation annotation;
    private DatashareUser adminUser;
    private DatashareUser nonAdminUser;

    private static AutoCloseable mocks;
    ;
    private String projectId = "test-datashare";
    private final Policy adminPolicy = new Policy() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Policy.class;
        }

        @Override
        public Role[] roles() {
            return new Role[]{Role.ADMIN};
        }
    };
    UserPolicyVerifier userPolicyVerifier;
    @Before
    public void setUp() throws URISyntaxException {
        UserPolicyVerifier.resetInstance(); // Reset singleton before each test

        mocks = openMocks(this);
        projectId = "test-datashare";
        adminUser = new DatashareUser("cecile");
        nonAdminUser = new DatashareUser("john");
        UserPolicy adminPermission = new UserPolicy("cecile", projectId, new Role[]{ADMIN});
        UserPolicy nonAdminPermission = new UserPolicy("john", projectId, new Role[]{Role.READER});
        UserPolicyRepository userRepository = mock(UserPolicyRepository.class);
        Repository repository = mock(Repository.class);

        userPolicyVerifier = mock(UserPolicyVerifier.class);
        when(userPolicyVerifier.getUserPolicyByProject("cecile", projectId)).thenReturn(adminPermission);
        when(userPolicyVerifier.getUserPolicyByProject("john", projectId)).thenReturn(nonAdminPermission);
        when(userPolicyVerifier.enforce("cecile", projectId, ADMIN.name())).thenReturn(true);
        when(userPolicyVerifier.enforce("john", projectId, ADMIN.name())).thenReturn(false);
        // inject mocked singleton so that UserPolicyAnnotation uses it
        annotation = new UserPolicyAnnotation(userPolicyVerifier);

    }

    @Test(expected = UnauthorizedException.class)
    public void should_throw_unauthorized_if_no_user() {
        Context context = mock(Context.class);
        when(context.pathParam("index")).thenReturn(projectId);
        annotation.apply(adminPolicy, context, (c) -> Payload.ok());
    }

    @Test
    public void should_return_forbidden_if_no_policy() {
        Context context = mock(Context.class);

        DatashareUser noPolicyUser = new DatashareUser("jane");
        when(context.currentUser()).thenReturn(noPolicyUser);

        when(context.pathParam("index")).thenReturn(projectId);

        Payload result = annotation.apply(adminPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test
    public void should_allow_user_if_has_wrong_policy_as_non_isAdmin() {
        Context context = mock(Context.class);

        when(context.currentUser()).thenReturn(nonAdminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        Payload result = annotation.apply(adminPolicy, context, (c) -> Payload.forbidden());
        assertEquals(403, result.code());
    }

    @Test
    public void should_allow_user_if_has_right_policy_as_isAdmin() {
        Context context = mock(Context.class);

        when(context.currentUser()).thenReturn(adminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        Payload result = annotation.apply(adminPolicy, context, (c) -> Payload.ok());
        assertEquals(200, result.code());
    }

    @After
    public void teardown() throws Exception {
        mocks.close();
    }

}