package org.icij.datashare.web;

import net.codestory.http.annotations.Get;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.Users;
import org.icij.datashare.Repository;
import org.icij.datashare.db.JooqUserRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.Policy;
import org.icij.datashare.session.UserPolicyAnnotation;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyVerifier;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class WebAcceptanceTest extends AbstractProdWebServerTest {
    private static AutoCloseable mocks;
    @Mock
    Repository jooqRepository;
    @Mock
    JooqUserRepository jooqUserRepository;
    UserPolicy adminPermission = new UserPolicy("john", "test-datashare", new Role[]{Role.ADMIN});


    @Before
    public void setUp() throws Exception {
        UserPolicyVerifier.resetInstance(); // Reset singleton before each test

        mocks = openMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        when(jooqUserRepository.getAll()).thenReturn(List.of(new UserPolicy[]{adminPermission}));
    }
    @Test
    public void route_with_index_in_path_policy_annotation_accepts_user_with_same_policy() throws URISyntaxException {
        User user = new User(new HashMap<>() {{
            put("uid", "john");
            put("name", "john");
            put("policies", new HashSet<UserPolicy>() {{
                add(adminPermission);
            }});
        }});
        Users users = DatashareUser.singleUser(user);
        UserPolicyAnnotation userPolicyAnnotation = new UserPolicyAnnotation(jooqUserRepository);
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
