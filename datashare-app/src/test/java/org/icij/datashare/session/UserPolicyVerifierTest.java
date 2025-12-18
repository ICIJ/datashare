package org.icij.datashare.session;

import org.icij.datashare.Repository;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.localUser;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class UserPolicyVerifierTest {
    @Mock
    private UserPolicyRepository jooqUserPolicyRepository;
    @Mock
    private Repository jooqRepository;

    private UserPolicyVerifier verifier;

    public User mockPolicy(String userId, String projectId, Role[] roles) {
        User user = localUser(userId);
        user.addProject(projectId);
        when(jooqRepository.getProject(projectId)).thenReturn(project(projectId));
        when(jooqRepository.getUser(user.id)).thenReturn(user);

        UserPolicy policy = new UserPolicy(user.id, projectId, roles);
        when(jooqUserPolicyRepository.get(user.id, projectId)).thenReturn(policy);
        return user.withPolicies(Set.of(policy));
    }

    @Before
    public void setUp() throws IOException {
        openMocks(this);
        User user1 = mockPolicy("user1", "project1", new Role[]{Role.READER});
        User user2 = mockPolicy("user2", "project2", new Role[]{Role.WRITER, Role.ADMIN});
        when(jooqUserPolicyRepository.getAllPolicies()).thenReturn(Stream.concat(user1.policies.stream(), user2.policies.stream()));
        when(jooqUserPolicyRepository.getByProjectId("project1")).thenReturn(user1.policies.stream());
        when(jooqUserPolicyRepository.getByProjectId("project2")).thenReturn(user2.policies.stream());
        verifier = new UserPolicyVerifier(jooqUserPolicyRepository, jooqRepository);
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
    public void test_enforce_bad_policy() {
        UserPolicy badPolicy = new UserPolicy("user2", "project2", new Role[]{Role.READER});
        assertFalse(verifier.enforceAllRoles(badPolicy));
    }

    @Test
    public void test_get_user_policy_by_project() {
        Project project1 = new Project("project1", "Project 1");
        when(jooqRepository.getProject("project1")).thenReturn(project1);
        Optional<UserPolicy> policy = Optional.ofNullable(verifier.getUserPolicy("user1", "project1"));
        assertThat(policy.isPresent()).isTrue();
        Optional<UserPolicy> policyNotExist = Optional.ofNullable(verifier.getUserPolicy("user1", "project2"));
        assertThat(policyNotExist.isEmpty()).isTrue();
    }

    @Test
    public void test_get_user_with_policies_by_project_when_user_does_not_exists() {
        assertThat(verifier.getUserPolicy("foo", "bar")).isEqualTo(null);
    }

    @Test
    public void test_get_user_with_policies_by_project_when_project_does_not_exists() {
        assertThat(verifier.getUserPolicy("user1", "bar")).isEqualTo(null);
    }

    @Test
    public void test_project_has_admin() {
        assertThat(verifier.hasAdmin("project2")).isTrue();
        assertThat(verifier.hasAdmin("project1")).isFalse();
    }
}
