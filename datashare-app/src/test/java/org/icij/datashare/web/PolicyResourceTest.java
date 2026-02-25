package org.icij.datashare.web;

import net.codestory.http.payload.Payload;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.UsersWritable;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.icij.datashare.text.Project.project;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class PolicyResourceTest extends AbstractProdWebServerTest {
    @Mock
    CasbinRuleAdapter adapter;
    @Mock
    JooqRepository repository;
    @Mock
    UsersWritable users;
    Authorizer authorizer;

    @Before
    public void setUp() {
        openMocks(this);
        DatashareUser user = new DatashareUser(new HashMap<>() {{
            put("uid", "jane");
            put("groups_by_applications", Map.of("datashare", List.of("test-datashare")));
        }});

        authorizer = new Authorizer(adapter);
        when(users.find("jane")).thenReturn(user);
        when(repository.getProject("test-datashare")).thenReturn(project("test-datashare"));
    }

    @Test
    public void get_instance_policies_success() {
        Domain domain1 = Domain.of("icij");
        Domain domain2 = Domain.of("datashare");
        authorizer.addRoleForUserInProject("jane", Role.PROJECT_MEMBER, domain1, "test-datashare");
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, domain2, "test-datashare");
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, domain1, "project2");
        configure(routes -> routes.add(new PolicyResource(authorizer, repository)));
        get("/api/policies?from=0&to=10").should().respond(200).contain("\"count\":3");
    }

    @Test
    public void get_domain_policies_success() {
        Domain domain1 = Domain.of("icij");
        Domain domain2 = Domain.of("datashare");
        authorizer.addRoleForUserInProject("jane", Role.PROJECT_MEMBER, domain1, "test-datashare");
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, domain2, "test-datashare");
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, domain1, "project2");
        configure(routes -> routes.add(new PolicyResource(authorizer, repository)));
        get("/api/policies/icij?from=0&to=10").should().respond(200).contain("\"count\":2");
        get("/api/policies/?from=0&to=10").should().respond(404);
        get("/api/policies/   ?from=0&to=10").should().respond(400);
    }
    @Test
    public void get_project_policies_success() {
        Domain domain = Domain.of("icij");
        when(repository.getProject("test-datashare")).thenReturn(project("test-datashare"));
        when(repository.getProject("project2")).thenReturn(project("project2"));
        when(repository.getProject("unknown")).thenReturn(null);
        authorizer.addRoleForUserInProject("jane", Role.PROJECT_MEMBER, domain, "test-datashare");
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, domain, "test-datashare");
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, domain, "project2");
        configure(routes -> routes.add(new PolicyResource(authorizer, repository)));
        get("/api/policies/icij/   ?from=0&to=10").should().respond(Payload.badRequest().code());
        get("/api/policies/icij/test-datashare?from=0&to=10").should().respond(200).contain("\"count\":2");
        get("/api/policies/icij/project2?from=0&to=10").should().respond(200).contain("\"count\":1");
        get("/api/policies/icij/unknown?from=0&to=10").should().respond(404);
    }

    @Test
    public void filter_policies_by_user() {
        String projectId = "test-datashare";
        authorizer.addRoleForUserInInstance("jane", Role.INSTANCE_ADMIN);
        when(repository.getProject(projectId)).thenReturn(project(projectId));
        when(repository.getUser("jane")).thenReturn(User.localUser("jane", projectId));

        configure(routes -> routes.add(new PolicyResource(authorizer, repository)));
        get("/api/policies?user=nonExistingUser&from=0&to=10").should().respond(200).contain("\"items\":[]");
        get("/api/policies/icij?user=nonExistingUser&from=0&to=10").should().respond(200).contain("\"items\":[]");
        get("/api/policies/icij/test-datashare?user=nonExistingUser&from=0&to=10").should().respond(200).contain("\"items\":[]");
        get("/api/policies?user=jane&from=0&to=10").should().respond(200).contain("\"count\":1");
        get("/api/policies/icij?user=jane&from=0&to=10").should().respond(200).contain("\"count\":1");
        get("/api/policies/icij/test-datashare?user=jane&from=0&to=10").should().respond(200).contain("\"count\":1");
    }

    @Test
    public void add_user_policy_with_bad_role_format_returns_bad_request() {
        when(repository.getUser("jane")).thenReturn(User.localUser("jane", "test-datashare"));
        authorizer.addRoleForUserInProject("jane", Role.PROJECT_MEMBER, Domain.of(""), "test-datashare");
        configure(routes -> routes.add(new PolicyResource(authorizer, repository)));
        put("/api/policies?domain=default&user=jane&project=test-datashare&role=READER]").should().contain("Invalid role in input: READER]").respond(400);
    }

    @Test
    public void upsert_user_policy_success_returns_ok() {
        when(repository.getUser("jane")).thenReturn(User.localUser("jane", "test-datashare"));
        configure(routes -> routes.add(new PolicyResource(authorizer, repository)));
        put("/api/policies?domain=default&user=jane&project=test-datashare&role=PROJECT_MEMBER").withPreemptiveAuthentication("jane", "").should().respond(200);
        put("/api/policies?domain=default&user=jane&project=test-datashare&role=PROJECT_ADMIN").withPreemptiveAuthentication("jane", "").should().respond(200);
    }


    @Test
    public void delete_user_policy_success_returns_204() throws IOException {
        //GIVEN
        when(repository.getUser("jane")).thenReturn(User.localUser("jane", "test-datashare"));
        authorizer.addRoleForUserInProject("jane", Role.PROJECT_MEMBER, Domain.of("default"), "test-datashare");
        configure(routes -> routes.add(new PolicyResource(authorizer, repository)));
        get("/api/policies?domain=default&user=jane&project=test-datashare&from=0&to=10").withPreemptiveAuthentication("jane", "").should().respond(200).contain("\"count\":1");

        //WHEN
        delete("/api/policies?domain=default&user=jane&project=test-datashare&role=PROJECT_MEMBER").withPreemptiveAuthentication("jane", "").should().respond(204);
        //THEN
        get("/api/policies?domain=default&user=jane&project=test-datashare&from=0&to=10").withPreemptiveAuthentication("jane", "").should().respond(200).contain("\"count\":0");
    }
}