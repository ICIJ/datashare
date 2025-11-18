package org.icij.datashare.user;

import org.icij.datashare.text.Project;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class UserPolicyVerifierTest {
    @Mock private UserRepository repository;
    private UserPolicyVerifier verifier;

    @Before
    public void setUp() throws URISyntaxException {
        openMocks(this);
        UserPolicy policy1 = new UserPolicy("user1", "project1", new Role[] {Role.READER});
        UserPolicy policy2 = new UserPolicy("user2", "project2",  new Role[] {Role.WRITER,Role.ADMIN});
        List<UserPolicy> policies = Arrays.asList(policy1, policy2);
        when(repository.getAll()).thenReturn(policies);
        verifier =  UserPolicyVerifier.getInstance(repository);
    }
    public static void testEnforce(UserPolicyVerifier verifier, String subject, String obj, String act, boolean expectedResult) {
        try {
            boolean enforcedResult = verifier.enforce(subject, obj, act);
            assertEquals(String.format("%s, %s, %s: %b, supposed to be %b", subject, obj, act, enforcedResult, expectedResult), expectedResult, enforcedResult);
        } catch (Exception ex) {
            throw new RuntimeException(String.format("Enforce Error: %s", ex.getMessage()), ex);
        }
    }
    @Test
    public void test_permission_enforcement() {

        testEnforce(verifier, "user1", "project1", "read", true);
        testEnforce(verifier, "user1", "project1", "write", false);
        testEnforce(verifier, "user1", "project1", "admin", false);
        testEnforce(verifier, "user2", "project2", "read", false);
        testEnforce(verifier, "user2", "project2", "write", true);
        testEnforce(verifier, "user2", "project2", "admin", true);

        //Negative test: user 1 is not allowed to read project2
        testEnforce(verifier, "user1", "project2", "read", false);

        //Negative test: "test" action is not defined in the policy
        testEnforce(verifier, "user1", "project1", "test", false);
    }
    @Test
    public void test_enforce_with_user_project_permission() {
        User user1 = new User("user1", "user1", "user1@example.com", "local", "{}");
        User user2 = new User("user2", "user2", "user2@example.com", "local", "{}");
        Project project1 = new Project("project1", "Project 1");
        Project project2 = new Project("project2", "Project 2");

        assertTrue(verifier.enforce(user1, project1, UserPolicyRepositoryAdapter.Permission.READ));
        assertFalse(verifier.enforce(user1, project1, UserPolicyRepositoryAdapter.Permission.WRITE));
        assertFalse(verifier.enforce(user1, project1, UserPolicyRepositoryAdapter.Permission.ADMIN));

        assertFalse(verifier.enforce(user2, project2, UserPolicyRepositoryAdapter.Permission.READ));
        assertTrue(verifier.enforce(user2, project2, UserPolicyRepositoryAdapter.Permission.WRITE));
        assertTrue(verifier.enforce(user2, project2, UserPolicyRepositoryAdapter.Permission.ADMIN));

        // Negative test: user1 should not have read on project2
        assertFalse(verifier.enforce(user1, project2, UserPolicyRepositoryAdapter.Permission.READ));
    }


}