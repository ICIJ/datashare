package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.CasbinRuleAdapter;
import org.icij.datashare.user.Domain;
import org.icij.datashare.user.Role;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.lang.annotation.Annotation;
import java.net.URISyntaxException;

import static junit.framework.TestCase.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class AuthorizationAnnotationTest {
    private final Policy adminProjectPolicy = new Policy() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Policy.class;
        }

        @Override
        public Role role() {
            return Role.PROJECT_ADMIN;
        }

        @Override
        public String idParam() {
            return "index";
        }

    };
    private DatashareUser adminUser;
    private DatashareUser nonAdminUser;

    private static AutoCloseable mocks;
    private String projectId = "test-datashare";
    @Mock
    CasbinRuleAdapter jooqCasbinRuleRepository;
    Authorizer authorizer;
    private AuthorizationAnnotation annotation;

    @Before
    public void setUp() throws URISyntaxException {
        mocks = openMocks(this);
        projectId = "test-datashare";
        adminUser = new DatashareUser("cecile");
        nonAdminUser = new DatashareUser("john");
        authorizer = new Authorizer(jooqCasbinRuleRepository);
        authorizer.addRoleForUserInProject("cecile", Role.PROJECT_ADMIN, Domain.of(""), projectId);
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, Domain.of(""), projectId);
        annotation = new AuthorizationAnnotation(authorizer);
    }

    @Test(expected = UnauthorizedException.class)
    public void should_throw_unauthorized_if_no_user() {
        Context context = mock(Context.class);
        when(context.pathParam("index")).thenReturn(projectId);
        annotation.apply(adminProjectPolicy, context, (c) -> Payload.ok());
    }

    @Test
    public void should_return_forbidden_if_no_policy() {
        Context context = mock(Context.class);

        DatashareUser noPolicyUser = new DatashareUser("jane");
        when(context.currentUser()).thenReturn(noPolicyUser);

        when(context.pathParam("index")).thenReturn(projectId);

        Payload result = annotation.apply(adminProjectPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test
    public void should_forbid_access_for_user_with_policy_as_non_isAdmin() {
        Context context = mock(Context.class);

        when(context.currentUser()).thenReturn(nonAdminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        Payload result = annotation.apply(adminProjectPolicy, context, (c) -> Payload.forbidden());
        assertEquals(403, result.code());
    }

    @Test
    public void should_allow_user_if_has_right_policy_as_isAdmin() {
        Context context = mock(Context.class);

        when(context.currentUser()).thenReturn(adminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        Payload result = annotation.apply(adminProjectPolicy, context, (c) -> Payload.ok());
        assertEquals(200, result.code());
    }

    @After
    public void teardown() throws Exception {
        mocks.close();
    }

}