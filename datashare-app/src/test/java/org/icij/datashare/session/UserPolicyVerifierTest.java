package org.icij.datashare.session;

import org.icij.datashare.EntityNotFoundException;
import org.icij.datashare.Repository;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.icij.datashare.user.UserPolicyVerifier;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.net.URISyntaxException;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class UserPolicyVerifierTest {
    @Mock
    private UserPolicyRepository userPolicyRepository;
    @Mock
    private Repository repository;

    private UserPolicyVerifier verifier;

    UserPolicy policy1 = new UserPolicy("user1", "project1", new Role[] {Role.READER});
    UserPolicy policy2 = new UserPolicy("user2", "project2",  new Role[] {Role.WRITER,Role.ADMIN});
    @Before
    public void setUp() throws URISyntaxException {
        openMocks(this);
        Stream<UserPolicy> policies = Stream.of(policy1, policy2);
        User user1 = new User("foo", "user1", "user1@example.com", "local", "{}");
        Project project1 = project("project1");
        Project project2 = project("project2");
        when(repository.getUser("user1")).thenReturn(user1);
        when(repository.getProject("project1")).thenReturn(project1);
        when(repository.getProject("project2")).thenReturn(project2);
        when(userPolicyRepository.getPolicies("user1")).thenReturn(Stream.of(policy1));
        when(userPolicyRepository.get("user1", "project1")).thenReturn(policy1);
        when(userPolicyRepository.getAllPolicies()).thenReturn(policies);

        verifier = UserPolicyVerifier.getInstance(userPolicyRepository, repository);
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

        testEnforce(verifier, "user1", "project1", "READER", true);
        testEnforce(verifier, "user1", "project1", "WRITER", false);
        testEnforce(verifier, "user1", "project1", "ADMIN", false);
        testEnforce(verifier, "user2", "project2", "READER", false);
        testEnforce(verifier, "user2", "project2", "WRITER", true);
        testEnforce(verifier, "user2", "project2", "ADMIN", true);

        //Negative test: user 1 is not allowed to READER project2
        testEnforce(verifier, "user1", "project2", "READER", false);

        //Negative test: "test" action is not defined in the policy
        testEnforce(verifier, "user1", "project1", "test", false);
    }
    @Test
    public void test_enforce_with_user_project_permission() {
        User user1 = new User("user1", "user1", "user1@example.com", "local", "{}");
        User user2 = new User("user2", "user2", "user2@example.com", "local", "{}");
        Project project1 = new Project("project1", "Project 1");
        Project project2 = new Project("project2", "Project 2");

        assertTrue(verifier.enforce(user1, project1, Role.READER));
        assertFalse(verifier.enforce(user1, project1, Role.WRITER));
        assertFalse(verifier.enforce(user1, project1, Role.ADMIN));

        assertFalse(verifier.enforce(user2, project2, Role.READER));
        assertTrue(verifier.enforce(user2, project2, Role.WRITER));
        assertTrue(verifier.enforce(user2, project2, Role.ADMIN));

        // Negative test: user1 should not have READER on project2
        assertFalse(verifier.enforce(user1, project2, Role.READER));
    }
    @Test
    public void test_enforce_bad_policy() {
        UserPolicy badPolicy = new UserPolicy("user2", "project2", new Role[]{Role.READER});
        assertFalse(verifier.enforceAllRoles(badPolicy));
    }

    @Test
    public void test_get_user_with_policies() {
        User foo = verifier.getUserWithPolicies("user1");
        assertThat(foo.getRoles("project1")).containsOnly(Role.READER);
    }

    @Test
    public void test_get_user_policy_by_project() {
        Project project1 = new Project("project1", "Project 1");
        when(repository.getProject("project1")).thenReturn(project1);
        UserPolicy policy = verifier.getUserPolicyByProject("user1", "project1");
        assertThat(policy).isNotNull();
        UserPolicy policyNotExist = verifier.getUserPolicyByProject("user1", "project2");
        assertThat(policyNotExist).isNull();
    }

    @Test(expected = EntityNotFoundException.class)
    public void test_get_user_with_policies_when_user_does_not_exists() {
        verifier.getUserWithPolicies("foo");
    }

    @Test(expected = EntityNotFoundException.class)
    public void test_get_user_with_policies_by_project_when_user_does_not_exists() {
        verifier.getUserPolicyByProject("foo", "bar");
    }

    @Test(expected = EntityNotFoundException.class)
    public void test_get_user_with_policies_by_project_when_project_does_not_exists() {
        verifier.getUserPolicyByProject("user1", "bar");
    }
}
