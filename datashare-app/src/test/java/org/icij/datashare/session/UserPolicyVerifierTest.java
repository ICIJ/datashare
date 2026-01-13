package org.icij.datashare.session;

import org.icij.datashare.RecordNotFoundException;
import org.icij.datashare.Repository;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
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

    public void mockUser(String userId, String projectId) {
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", userId);
            put("groups_by_applications", Map.of("datashare", List.of(projectId)));
        }});
        when(users.find(user.id)).thenReturn(user);
    }
    public void mockUserWithPolicy(String userId, String projectId, Role[] roles) {
        UserPolicy policy = new UserPolicy(userId, projectId, roles);

        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", userId);
            put("groups_by_applications", Map.of("datashare", List.of(projectId)));
            put("policies",  Stream.of(policy));
        }});
        when(users.find(user.id)).thenReturn(user);
        when(jooqUserPolicyRepository.get(user.id, projectId)).thenReturn(policy);
    }

    @Before
    public void setUp() throws IOException {
        openMocks(this);
        UserPolicy policy1 = new UserPolicy("user1", "project1", new Role[]{Role.READER});
        UserPolicy policy2 = new UserPolicy("user2", "project2", new Role[]{Role.WRITER, Role.ADMIN});

        mockUserWithPolicy("user1", "project1", new Role[]{Role.READER});
        mockUserWithPolicy("user2", "project2", new Role[]{Role.WRITER, Role.ADMIN});

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
        Optional<UserPolicy> policy = Optional.ofNullable(verifier.getUserPolicy("user1", "project1"));
        assertThat(policy.isPresent()).isTrue();
        Optional<UserPolicy> policyNotExist = Optional.ofNullable(verifier.getUserPolicy("user1", "project2"));
        assertThat(policyNotExist.isEmpty()).isTrue();
    }

    @Test
    public void test_get_user_with_policies_by_project_when_user_does_not_exists() {
        mockUserWithPolicy("foo", "bar", new Role[]{Role.READER});
        assertThat(verifier.getUserPolicy("unknown", "bar")).isEqualTo(null);
    }

    @Test
    public void test_get_user_with_policies_by_project_when_project_does_not_exists() {
        mockUserWithPolicy("foo", "bar", new Role[]{Role.READER});
        assertThat(verifier.getUserPolicy("foo", "unknown")).isEqualTo(null);
    }

    @Test
    public void test_save_policy_when_user_exists_and_project_is_granted() {
        mockUser("foo", "bar");
        assertThat(verifier.saveUserPolicy("foo", "bar", new Role[]{Role.READER})).isTrue();
    }

    @Test(expected = RecordNotFoundException.class)
    public void test_save_throws_exception_when_user_does_not_exist() {
        verifier.saveUserPolicy("unknown", "project1", new Role[]{Role.READER});
    }

    @Test(expected = RecordNotFoundException.class)
    public void test_save_throws_exception_when_project_does_not_exist() {
        mockUser("foo", "bar");
        verifier.saveUserPolicy("foo", "unknown", new Role[]{Role.READER});
    }

    @Test(expected = RecordNotFoundException.class)
    public void test_delete_throws_exception_when_user_does_not_exist() {
        verifier.deleteUserPolicy("unknown", "project1");
    }

    @Test(expected = RecordNotFoundException.class)
    public void test_delete_throws_exception_when_user_is_not_granted_to_the_project() {
        mockUser("foo", "bar");
        verifier.deleteUserPolicy("foo", "unknown");
    }

}
