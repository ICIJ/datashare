package org.icij.datashare.web;

import net.codestory.http.annotations.Get;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.Users;
import org.icij.datashare.Repository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.Policy;
import org.icij.datashare.session.UserPolicyAnnotation;
import org.icij.datashare.session.UserPolicyVerifier;
import org.icij.datashare.session.UsersWritable;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Set;

import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.localUser;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class WebAcceptanceTest extends AbstractProdWebServerTest {
    private static AutoCloseable mocks;
    @Mock
    Repository jooqRepository;
    @Mock
    UserPolicyRepository jooqUserPolicyRepository;
    @Mock
    UsersWritable users;

    @Before
    public void setUp() throws Exception {
        mocks = openMocks(this);
    }


    public User mockUserProjectRole(String userId, String projectId, Role[] roles) {
        DatashareUser user = new DatashareUser(localUser(userId));
        user.addProject(projectId);
        when(jooqRepository.getProject(projectId)).thenReturn(project(projectId));
        when(users.find(user.id)).thenReturn(user);

        UserPolicy policy = new UserPolicy(user.id, projectId, roles);
        when(jooqUserPolicyRepository.get(user.id, projectId)).thenReturn(policy);
        return user.withPolicies(Set.of(policy));
    }

    @Test
    public void route_with_index_in_path_policy_annotation_accepts_user_with_same_policy() throws IOException, URISyntaxException {
        User john = mockUserProjectRole("john", "test-datashare", new Role[]{Role.ADMIN});
        when(jooqUserPolicyRepository.getAllPolicies()).thenReturn(john.policies.stream());

        UserPolicyVerifier verifier = new UserPolicyVerifier(jooqUserPolicyRepository, users);
        UserPolicyAnnotation userPolicyAnnotation = new UserPolicyAnnotation(verifier);
        Users users = DatashareUser.singleUser(john);

        configure(routes -> routes.registerAroundAnnotation(Policy.class, userPolicyAnnotation).filter(new BasicAuthFilter("/", "icij", users)).add(new FakeResource()));

        get("/admin/test-datashare").withPreemptiveAuthentication("john", "pass").should().respond(200);
    }

    // Fake resource with @Policy annotation
    static class FakeResource {
        public FakeResource() {
        }

        @Get("/admin/:index")
        @Policy(roles = {Role.ADMIN})
        public String getAdminResource(String index) {
            return "admin-content " + index;
        }

    }

    @After
    public void teardown() throws Exception {
        mocks.close();
    }


}
