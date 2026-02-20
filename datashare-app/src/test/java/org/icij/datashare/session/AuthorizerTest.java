package org.icij.datashare.session;

import junit.framework.TestCase;
import org.icij.datashare.CasbinRuleAdapter;
import org.icij.datashare.user.Domain;
import org.icij.datashare.user.Role;
import org.mockito.Mockito;

import java.util.List;

public class AuthorizerTest extends TestCase {
    private Authorizer authorizer;
    private CasbinRuleAdapter adapter;
    private Domain domain;
    private String userId;
    private String project;

    public void setUp() throws Exception {
        super.setUp();
        adapter = Mockito.mock(CasbinRuleAdapter.class);
        authorizer = new Authorizer(adapter);
        domain = Domain.of("test_domain");
        userId = "test_user";
        project = "test_project";
    }

    public void tearDown() throws Exception {
        super.tearDown();
    }

    public void test_can_with_role() {
        authorizer.addRoleForUserInProject(userId, Role.PROJECT_ADMIN, domain, project);
        assertTrue(authorizer.can(userId, domain, project, Role.PROJECT_ADMIN));
        assertTrue(authorizer.can(userId, domain, project, Role.PROJECT_EDITOR));
    }

    public void test_can_with_action_string() {
        authorizer.addRoleForUserInProject(userId, Role.PROJECT_MEMBER, domain, project);
        assertTrue(authorizer.can(userId, domain, project, "PROJECT_MEMBER"));
    }

    public void test_add_role_for_user_in_instance() {
        assertTrue(authorizer.addRoleForUserInInstance(userId, Role.INSTANCE_ADMIN));
    }

    public void test_add_role_for_user_in_domain() {
        assertTrue(authorizer.addRoleForUserInDomain(userId, Role.DOMAIN_ADMIN, domain));
    }

    public void test_delete_role_for_user_in_domain() {
        authorizer.addRoleForUserInDomain(userId, Role.DOMAIN_ADMIN, domain);
        assertTrue(authorizer.deleteRoleForUserInDomain(userId, Role.DOMAIN_ADMIN, domain));
    }

    public void test_update_role_for_user_in_domain() {
        authorizer.addRoleForUserInDomain(userId, Role.DOMAIN_ADMIN, domain);
        assertTrue(authorizer.updateRoleForUserInDomain(userId, Role.DOMAIN_ADMIN, domain));
    }

    public void test_get_roles_for_user_in_domain() {
        List<String> roles = authorizer.getRolesForUserInDomain(userId, domain);
        assertNotNull(roles);
    }

    public void test_add_project_admin() {
        assertTrue(authorizer.addProjectAdmin(userId, domain, project));
    }

    public void test_add_role_for_user_in_project() {
        assertTrue(authorizer.addRoleForUserInProject(userId, Role.PROJECT_ADMIN, domain, project));
    }

    public void test_delete_role_for_user_in_project() {
        authorizer.addRoleForUserInProject(userId, Role.PROJECT_ADMIN, domain, project);
        assertTrue(authorizer.deleteRoleForUserInProject(userId, Role.PROJECT_ADMIN, domain, project));
    }

    public void test_update_role_for_user_in_project() {
        authorizer.addRoleForUserInProject(userId, Role.PROJECT_ADMIN, domain, project);
        assertTrue(authorizer.updateRoleForUserInProject(userId, Role.PROJECT_ADMIN, domain, project));
    }

    public void test_get_roles_for_user_in_project() {
        List<String> roles = authorizer.getRolesForUserInProject(userId, domain, project);
        assertNotNull(roles);
    }
}