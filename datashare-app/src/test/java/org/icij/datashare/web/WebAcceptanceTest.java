package org.icij.datashare.web;

import net.codestory.http.annotations.Get;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.Users;
import org.icij.datashare.Repository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.Policy;
import org.icij.datashare.session.UserPolicyAnnotation;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.icij.datashare.user.UserPolicyVerifier;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.net.URISyntaxException;
import java.util.stream.Stream;

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

    @Before
    public void setUp() throws Exception {
        UserPolicyVerifier.resetInstance(); // Reset singleton before each test

        mocks = openMocks(this);
    }


    public void mockUserPolicyRepository(User user, String projectId, Role[] roles) {

        when(jooqRepository.getProject(projectId)).thenReturn(project(projectId));
        when(jooqRepository.getUser(user.id)).thenReturn(user);

        UserPolicy policy = new UserPolicy(user.id, projectId, roles);
        when(jooqUserPolicyRepository.get(user.id, projectId)).thenReturn(policy);
        when(jooqUserPolicyRepository.getAllPolicies()).thenReturn(Stream.of(policy));
    }

    @Test
    public void route_with_index_in_path_policy_annotation_accepts_user_with_same_policy() throws URISyntaxException {
        User john = localUser("john");
        mockUserPolicyRepository(john, "test-datashare", new Role[]{Role.ADMIN});
        UserPolicyAnnotation userPolicyAnnotation = new UserPolicyAnnotation(UserPolicyVerifier.getInstance(jooqUserPolicyRepository, jooqRepository));
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
