package org.icij.datashare.policies;

import net.codestory.http.Context;
import net.codestory.http.Request;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.ScopedUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

import static junit.framework.TestCase.assertEquals;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class PolicyAnnotationTest {
    private final Policy instanceAdminPolicy = new Policy() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Policy.class;
        }

        @Override
        public Role role() {
            return Role.INSTANCE_ADMIN;
        }

        @Override
        public String idParam() {
            return "index";
        }

        @Override
        public String domain() {
            return "default";
        }
    };
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

        @Override
        public String domain() {
            return "default";
        }

    };
    private final Policy domainAdminPolicy = new Policy() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Policy.class;
        }

        @Override
        public Role role() {
            return Role.DOMAIN_ADMIN;
        }

        @Override
        public String idParam() {
            return "index";
        }

        @Override
        public String domain() {
            return "default";
        }
    };
    private DatashareUser adminUser;
    private DatashareUser nonAdminUser;
    private DatashareUser domainAdminUser;

    private static AutoCloseable mocks;
    private String projectId = "test-datashare";
    @Mock
    CasbinRuleAdapter adapter;
    Authorizer authorizer;
    private PolicyAnnotation annotation;

    @Before
    public void setUp() throws IOException {
        mocks = openMocks(this);
        projectId = "test-datashare";
        Project project = project(projectId);
        adminUser = new DatashareUser("cecile");
        nonAdminUser = new DatashareUser("john");
        authorizer = new Authorizer(adapter);
        //TODO #DOMAIN : currently only default domain is supported in the annotation
        authorizer.addRoleForUserInProject(adminUser, Role.PROJECT_ADMIN, Domain.DEFAULT, project);
        authorizer.addRoleForUserInProject(nonAdminUser, Role.PROJECT_MEMBER, Domain.DEFAULT, project);
        authorizer.addRoleForUserInInstance(adminUser, Role.INSTANCE_ADMIN);
        domainAdminUser = new DatashareUser("alice");
        authorizer.addRoleForUserInDomain(domainAdminUser, Role.DOMAIN_ADMIN, Domain.DEFAULT);
        annotation = new PolicyAnnotation(authorizer);
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
        // an empty body tells a client nothing about whether to re-authenticate or give up
        assertThat(result.rawContent()).isEqualTo(Map.of("error", "insufficient role"));
        assertThat(result.rawContentType()).contains("application/json");
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
    public void should_allow_user_if_has_right_policy() {
        Context context = mock(Context.class);

        when(context.currentUser()).thenReturn(adminUser);
        when(context.pathParam("index")).thenReturn(projectId);
        Payload result = annotation.apply(adminProjectPolicy, context, (c) -> Payload.ok());
        assertEquals(200, result.code());
    }

    @Test
    public void should_allow_instance_admin_with_no_project_param() {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(adminUser);
        when(context.pathParam("index")).thenReturn(null);
        assertEquals(200, annotation.apply(instanceAdminPolicy, context, c -> Payload.ok()).code());
    }

    @Test
    public void should_forbid_non_instance_admin_with_no_project_param() {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(nonAdminUser);
        when(context.pathParam("index")).thenReturn(null);
        assertEquals(403, annotation.apply(instanceAdminPolicy, context, c -> Payload.ok()).code());
    }

    @Test(expected = UnauthorizedException.class)
    public void should_throw_unauthorized_if_no_user_and_no_project_param() {
        Context context = mock(Context.class);
        when(context.pathParam("index")).thenReturn(null);
        annotation.apply(instanceAdminPolicy, context, c -> Payload.ok());
    }

    @Test
    public void should_allow_domain_admin() {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(domainAdminUser);
        assertEquals(200, annotation.apply(domainAdminPolicy, context, c -> Payload.ok()).code());
    }

    @Test
    public void should_forbid_non_domain_admin() {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(nonAdminUser);
        assertEquals(403, annotation.apply(domainAdminPolicy, context, c -> Payload.ok()).code());
    }

    @Test(expected = UnauthorizedException.class)
    public void should_throw_unauthorized_if_no_user_for_domain_policy() {
        Context context = mock(Context.class);
        annotation.apply(domainAdminPolicy, context, c -> Payload.ok());
    }

    @Test
    public void test_scoped_user_refused_on_instance_admin() {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(new ScopedUser(new DatashareUser(User.localUser("cecile", projectId, "p2")), List.of(projectId)));
        Payload result = annotation.apply(instanceAdminPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
        assertThat(result.rawContent()).isEqualTo(Map.of("error", "insufficient role"));
    }

    @Test
    public void test_scoped_user_refused_on_domain_admin() {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(new ScopedUser(new DatashareUser(User.localUser("alice", projectId)), List.of(projectId)));
        Payload result = annotation.apply(domainAdminPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
        assertThat(result.rawContent()).isEqualTo(Map.of("error", "insufficient role"));
    }

    @Test
    public void test_scoped_user_refused_without_project_id() throws IOException {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(new ScopedUser(new DatashareUser(User.localUser("cecile", projectId, "p2")), List.of(projectId)));
        when(context.pathParam("index")).thenReturn(null);
        Request request = mock(Request.class);
        when(request.content()).thenReturn("");
        when(context.request()).thenReturn(request);
        Payload result = annotation.apply(adminProjectPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test
    public void test_scoped_user_refused_outside_scope() {
        Context context = mock(Context.class);
        authorizer.addRoleForUserInProject(adminUser, Role.PROJECT_ADMIN, Domain.DEFAULT, project("p2"));
        when(context.currentUser()).thenReturn(new ScopedUser(new DatashareUser(User.localUser("cecile", projectId, "p2")), List.of(projectId)));
        when(context.pathParam("index")).thenReturn("p2");
        Payload result = annotation.apply(adminProjectPolicy, context, c -> Payload.ok());
        assertEquals(403, result.code());
    }

    @Test
    public void test_scoped_user_allowed_inside_scope() {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(new ScopedUser(new DatashareUser(User.localUser("cecile", projectId, "p2")), List.of(projectId)));
        when(context.pathParam("index")).thenReturn(projectId);
        Payload result = annotation.apply(adminProjectPolicy, context, c -> Payload.ok());
        assertEquals(200, result.code());
    }

    @After
    public void teardown() throws Exception {
        mocks.close();
    }

}