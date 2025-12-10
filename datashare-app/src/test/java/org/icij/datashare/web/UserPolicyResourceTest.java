package org.icij.datashare.web;

import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.db.JooqUserPolicyRepository;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.net.URISyntaxException;
import java.util.stream.Stream;

import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class UserPolicyResourceTest extends AbstractProdWebServerTest {
    @Mock
    JooqUserPolicyRepository userPolicyRepository;
    @Mock
    JooqRepository repository;

    @Before
    public void setUp() {
        openMocks(this);
        configure(routes -> {
            try {
                routes.add(new UserPolicyResource(userPolicyRepository, repository));
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        });

    }

    @Test
    public void get_all_user_policy_success() {
        UserPolicy policy = UserPolicy.of("jane", "test-datashare", new Role[]{Role.READER});
        when(userPolicyRepository.getAllPolicies()).thenReturn(Stream.of(policy));
        get("/api/policies/").should().respond(200);
    }

    @Test
    public void get_user_policy_success() {
        UserPolicy policy = UserPolicy.of("jane", "test-datashare", new Role[]{Role.READER});
        when(repository.getUser("jane")).thenReturn(User.localUser("jane"));
        when(repository.getProject("test-datashare")).thenReturn(project("test-datashare"));
        when(userPolicyRepository.get("jane", "test-datashare")).thenReturn(policy);
        get("/api/policies/?userId=jane&projectId=test-datashare").should().respond(200);
    }

    @Test
    public void add_user_policy_with_bad_role_format() {
        put("/api/policies/?userId=jane&projectId=test-datashare&roles=READER]").should().respond(400);
    }

    // add test on bad user / project

/*
    @Test
    public void test_add_user_policy_success() {
        UserPolicy policy = UserPolicy.of("jane", "test-datashare", new Role[]{Role.READER});
        UserPolicy policy2 = UserPolicy.of("jane", "test-datashare", new Role[]{Role.READER, Role.WRITER});
        when(repository.getUser("jane")).thenReturn(User.localUser("jane"));
        when(repository.getProject("test-datashare")).thenReturn(project("test-datashare"));
        when(userPolicyRepository.save(policy)).thenReturn(true);
        when(userPolicyRepository.save(policy2)).thenReturn(true);
        put("/api/policies/?userId=jane&projectId=test-datashare&roles=READER").should().respond(200);
        put("/api/policies/?userId=jane&projectId=test-datashare&roles=READER,WRITER").should().respond(200);
    }
*/

}