package org.icij.datashare.web;

import net.codestory.http.annotations.Get;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.Users;
import org.icij.datashare.Repository;
import org.icij.datashare.policies.*;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.UsersWritable;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class WebAcceptanceTest extends AbstractProdWebServerTest {
    private static AutoCloseable mocks;
    @Mock
    Repository jooqRepository;
    @Mock
    CasbinRuleAdapter adapter;
    @Mock
    UsersWritable users;

    Authorizer authorizer;
    @Before
    public void setUp() throws Exception {
        mocks = openMocks(this);
        authorizer = new Authorizer(adapter);
    }

    public void tearDown() throws Exception {
        mocks.close();
    }

    public User mockUserProjectRole(String userId, String projectId, Role role) {
        User user = localUser(userId, projectId);
        when(jooqRepository.getUser(userId)).thenReturn(user);
        when(jooqRepository.getProject(projectId)).thenReturn(project(projectId));
        when(users.find(user.id)).thenReturn(new DatashareUser(user));

        authorizer.addRoleForUserInProject(userId, role, Domain.DEFAULT, projectId);

        return user;
    }

    @Test
    public void route_with_index_in_path_policy_annotation_accepts_user_with_same_policy() {
        User john = mockUserProjectRole("john", "test-datashare", Role.PROJECT_ADMIN);
        PolicyAnnotation policyAnnotation = new PolicyAnnotation(authorizer);
        Users users = DatashareUser.singleUser(john);

        configure(routes -> routes.registerAroundAnnotation(Policy.class, policyAnnotation).filter(new BasicAuthFilter("/", "icij", users)).add(new FakeResource()));

        get("/admin/test-datashare").withPreemptiveAuthentication("john", "pass").should().respond(200);
    }

    @Test
    public void route_with_policy_annotation_rejects_user_without_admin_role() {
        User jane = mockUserProjectRole("jane", "test-datashare", Role.PROJECT_MEMBER);

        PolicyAnnotation userPolicyAnnotation = new PolicyAnnotation(authorizer);
        Users users = DatashareUser.singleUser(jane);

        configure(routes -> routes.registerAroundAnnotation(Policy.class, userPolicyAnnotation).filter(new BasicAuthFilter("/", "icij", users)).add(new FakeResource()));

        get("/admin/test-datashare").withPreemptiveAuthentication("jane", "pass").should().respond(403);
    }

    // Fake resource with @Policy annotation
    static class FakeResource {
        public FakeResource() {
        }

        @Get("/admin/:index")
        @Policy(role = Role.PROJECT_EDITOR)
        public String getAdminResource(String index) {
            return "admin-content " + index;
        }

    }

    @After
    public void teardown() throws Exception {
        mocks.close();
    }


}
