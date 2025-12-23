package org.icij.datashare.session;

import org.icij.datashare.RecordNotFoundException;
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
    @Mock
    private UsersWritable users;

    private UserPolicyVerifier verifier;

    public User mockUser(String userId, String projectId, Role[] roles) {
        UserPolicy policy = new UserPolicy(userId, projectId, roles);
        DatashareUser user = new DatashareUser((localUser(userId, Stream.of(policy))));
        user.addProject(projectId);
        when(jooqRepository.getProject(projectId)).thenReturn(project(projectId));
        when(users.find(user.id)).thenReturn(user);

        when(jooqUserPolicyRepository.get(user.id, projectId)).thenReturn(policy);
        return user;
    }

    @Before
    public void setUp() throws IOException {
        openMocks(this);
        UserPolicy policy1 = new UserPolicy("user1", "project1", new Role[]{Role.READER});
        UserPolicy policy2 = new UserPolicy("user2", "project2", new Role[]{Role.WRITER, Role.ADMIN});

        mockUser("user1", "project1", new Role[]{Role.READER});
        mockUser("user2", "project2", new Role[]{Role.WRITER, Role.ADMIN});

        when(jooqUserPolicyRepository.getAllPolicies()).thenReturn(Stream.of(policy1, policy2));
        when(jooqUserPolicyRepository.getByProjectId("project1")).thenReturn(Stream.of(policy1));
        when(jooqUserPolicyRepository.getByProjectId("project2")).thenReturn(Stream.of(policy2));
        verifier = new UserPolicyVerifier(jooqUserPolicyRepository, jooqRepository, users);
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

    @Test(expected = RecordNotFoundException.class)
    public void test_save_throws_exception_when_user_does_not_exist() {
        when(users.find("unknown")).thenReturn(new DatashareUser(User.nullUser()));
        verifier.saveUserPolicy("unknown", "project1", new Role[]{Role.READER});
    }

    @Test(expected = RecordNotFoundException.class)
    public void test_save_throws_exception_when_project_does_not_exist() {
        when(jooqRepository.getProject("unknown")).thenReturn(null);
        verifier.saveUserPolicy("user1", "unknown", new Role[]{Role.READER});
    }

    @Test(expected = RecordNotFoundException.class)
    public void test_delete_throws_exception_when_user_does_not_exist() {
        when(users.find("unknown")).thenReturn(new DatashareUser(User.nullUser()));
        verifier.deleteUserPolicy("unknown", "project1");
    }

    @Test(expected = RecordNotFoundException.class)
    public void test_delete_throws_exception_when_project_does_not_exist() {
        when(jooqRepository.getProject("unknown")).thenReturn(null);
        verifier.deleteUserPolicy("user1", "unknown");
    }

}
