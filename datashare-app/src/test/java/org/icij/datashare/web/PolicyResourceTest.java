package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.db.JooqCasbinRuleAdapter;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.UsersWritable;
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
    JooqCasbinRuleAdapter adapter;
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
    public void get_all_user_policy_success() {
        authorizer.addRoleForUserInProject("jane", Role.PROJECT_MEMBER, Domain.of(""), "test-datashare");
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, Domain.of(""), "test-datashare");
        configure(routes -> routes.add(new PolicyResource(authorizer)));
        get("/api/policies/?from=0&to=10").should().respond(200).contain("\"count\":2");
    }

    @Test
    public void get_user_policy_success_returns_ok() {
        authorizer.addRoleForUserInProject("jane", Role.PROJECT_MEMBER, Domain.of(""), "test-datashare");
        authorizer.addRoleForUserInProject("john", Role.PROJECT_MEMBER, Domain.of(""), "test-datashare");
        configure(routes -> routes.add(new PolicyResource(authorizer)));
        get("/api/policies/?userId=jane&projectId=test-datashare&from=0&to=10").should().respond(200).contain("\"count\":1");
    }

    @Test
    public void get_user_policy_not_existing_returns_not_found() {
        configure(routes -> routes.add(new PolicyResource(authorizer)));
        get("/api/policies/?userId=nonExistingUser&from=0&to=10").should().respond(200).contain("\"items\":[]");
        get("/api/policies/?projectId=nonExistingProject&from=0&to=10").should().respond(200).contain("\"items\":[]");
        get("/api/policies/?userId=nonExistingUser&projectId=nonExistingProject&from=0&to=10").should().respond(200).contain("\"items\":[]");
    }

    @Test
    public void add_user_policy_with_bad_role_format_returns_bad_request() {
        authorizer.addRoleForUserInProject("jane", Role.PROJECT_MEMBER, Domain.of(""), "test-datashare");
        configure(routes -> routes.add(new PolicyResource(authorizer)));
        put("/api/policies/?userId=jane&projectId=test-datashare&role=READER]").should().contain("Invalid role in input: READER]").respond(400);
    }

    @Test
    public void upsert_user_policy_success_returns_ok() {
        configure(routes -> routes.add(new PolicyResource(authorizer)));
        put("/api/policies/?userId=jane&projectId=test-datashare&role=PROJECT_MEMBER").withPreemptiveAuthentication("jane", "").should().respond(200);
        put("/api/policies/?userId=jane&projectId=test-datashare&role=PROJECT_ADMIN").withPreemptiveAuthentication("jane", "").should().respond(200);
    }


    @Test
    public void delete_user_policy_success_returns_204() throws IOException {
        //GIVEN
        authorizer.addRoleForUserInProject("jane", Role.PROJECT_MEMBER, Domain.of(""), "test-datashare");
        //WHEN
        authorizer.deleteRoleForUserInProject("jane", Role.PROJECT_MEMBER, Domain.of(""), "test-datashare");
        configure(routes -> routes.add(new PolicyResource(authorizer)).
                filter(new BasicAuthFilter("/", "icij", DatashareUser.singleUser(new DatashareUser(new HashMap<>() {{
                    put("uid", "jane");
                    put("groups_by_applications", Map.of("datashare", List.of("test-datashare")));
                }})))));
        //THEN
        delete("/api/policies/?userId=jane&projectId=test-datashare").withPreemptiveAuthentication("jane", "").should().respond(204);
    }
}