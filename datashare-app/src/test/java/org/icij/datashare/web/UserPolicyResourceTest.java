package org.icij.datashare.web;

import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.db.JooqUserPolicyRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.UserPolicyVerifier;
import org.icij.datashare.session.UsersWritable;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.util.stream.Stream;

import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class UserPolicyResourceTest extends AbstractProdWebServerTest {
    @Mock
    JooqUserPolicyRepository userPolicyRepository;
    @Mock
    JooqRepository repository;
    @Mock
    UsersWritable users;

    @Before
    public void setUp() {
        openMocks(this);
        when(users.find("jane")).thenReturn(new DatashareUser("jane"));
        when(repository.getProject("test-datashare")).thenReturn(project("test-datashare"));
    }

    @Test
    public void get_all_user_policy_success() throws IOException {
        UserPolicy policy = UserPolicy.of("jane", "test-datashare", new Role[]{Role.READER});
        UserPolicy policy2 = UserPolicy.of("john", "test-datashare", new Role[]{Role.READER});
        when(userPolicyRepository.getAllPolicies()).thenAnswer(s -> Stream.of(policy, policy2));
        UserPolicyVerifier verifier = new UserPolicyVerifier(userPolicyRepository, repository, users);
        configure(routes -> routes.add(new UserPolicyResource(verifier)));
        get("/api/policies/?from=0&to=10").should().respond(200).contain("\"count\":2");
    }

    @Test
    public void get_user_policy_success_returns_ok() throws IOException {
        UserPolicy policy = UserPolicy.of("jane", "test-datashare", new Role[]{Role.READER});
        when(userPolicyRepository.get("jane", "test-datashare")).thenAnswer(p -> policy);
        UserPolicyVerifier verifier = new UserPolicyVerifier(userPolicyRepository, repository, users);
        configure(routes -> routes.add(new UserPolicyResource(verifier)));
        get("/api/policies/?userId=jane&projectId=test-datashare&from=0&to=10").should().respond(200).contain("\"count\":1");
    }

    @Test
    public void get_user_policy_not_existing_returns_not_found() throws IOException {
        when(repository.getUser("john")).thenReturn(User.localUser("john"));
        when(userPolicyRepository.get("john", "test-datashare")).thenReturn(null);
        UserPolicyVerifier verifier = new UserPolicyVerifier(userPolicyRepository, repository, users);
        configure(routes -> routes.add(new UserPolicyResource(verifier)));
        get("/api/policies/?userId=john&from=0&to=10").should().respond(200).contain("\"items\":[]");
        get("/api/policies/?projectId=test-datashare&from=0&to=10").should().respond(200).contain("\"items\":[]");
        get("/api/policies/?userId=john&projectId=test-datashare&from=0&to=10").should().respond(200).contain("\"items\":[]");
    }

    @Test
    public void add_user_policy_with_bad_role_format_returns_bad_request() throws IOException {
        UserPolicyVerifier verifier = new UserPolicyVerifier(userPolicyRepository, repository, users);
        configure(routes -> routes.add(new UserPolicyResource(verifier)));
        put("/api/policies/?userId=jane&projectId=test-datashare&roles=READER]").should().contain("Invalid role in input: READER]").respond(400);
    }

    @Test
    public void upsert_user_policy_success_returns_ok() throws IOException {
        when(userPolicyRepository.save(any(UserPolicy.class))).thenReturn(true);
        UserPolicyVerifier verifier = new UserPolicyVerifier(userPolicyRepository, repository, users);
        configure(routes -> routes.add(new UserPolicyResource(verifier)));
        put("/api/policies/?userId=jane&projectId=test-datashare&roles=READER").should().respond(200);
        put("/api/policies/?userId=jane&projectId=test-datashare&roles=READER,WRITER").should().respond(200);
    }


    @Test
    public void delete_user_policy_success_returns_204() throws IOException {
        when(userPolicyRepository.delete("jane", "test-datashare")).thenReturn(true);
        when(repository.getUser("john")).thenReturn(User.localUser("john"));
        UserPolicyVerifier verifier = new UserPolicyVerifier(userPolicyRepository, repository, users);
        configure(routes -> routes.add(new UserPolicyResource(verifier)));
        delete("/api/policies/?userId=jane&projectId=test-datashare").should().respond(204);
    }

    // delete responds 204 even if the tuple does not exist in the db
    @Test
    public void delete_user_policy__should_return_204_even_if_the_tuple_does_not_exists() throws IOException {
        when(users.find("john")).thenReturn(new DatashareUser(User.localUser("john")));
        when(userPolicyRepository.delete("john", "test-datashare")).thenReturn(false);
        UserPolicyVerifier verifier = new UserPolicyVerifier(userPolicyRepository, repository, users);
        configure(routes -> routes.add(new UserPolicyResource(verifier)));
        delete("/api/policies/?userId=john&projectId=test-datashare").should().respond(204);
    }
}