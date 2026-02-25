package org.icij.datashare.policies;

import junit.framework.TestCase;
import org.mockito.Mockito;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class AuthorizerTest extends TestCase {
    private Authorizer authorizer;
    private CasbinRuleAdapter adapter;
    private Domain domain;
    private String userId;
    private String project;

    public void setUp() throws Exception {
        super.setUp();
        adapter = Mockito.mock(CasbinRuleAdapter.class);
        authorizer = new Authorizer(adapter, false);
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

    public void test_get_permissions_by_domain() {
        authorizer.addRoleForUserInDomain(userId, Role.DOMAIN_ADMIN, domain);
        authorizer.addRoleForUserInProject(userId, Role.DOMAIN_ADMIN, domain, "p1");
        List<CasbinRule> permissions = authorizer.getAllPermissions(domain);
        assertFalse(permissions.isEmpty());
        assertTrue(permissions.stream().anyMatch(p -> p.ptype.equals("g") && p.v0.equals(userId) && p.v1.equals(Role.DOMAIN_ADMIN.name())));
        assertThat(permissions.size()).isEqualTo(2);
    }

    public void test_get_permissions_by_project() {
        authorizer.addRoleForUserInProject(userId, Role.PROJECT_ADMIN, domain, project);
        authorizer.addRoleForUserInProject("other_user", Role.PROJECT_ADMIN, domain, project);
        authorizer.addRoleForUserInProject(userId, Role.PROJECT_ADMIN, domain, "project2");
        authorizer.addRoleForUserInProject(userId, Role.PROJECT_ADMIN, Domain.of("other"), project);
        List<CasbinRule> permissions = authorizer.getAllPermissions(domain, project);
        assertFalse(permissions.isEmpty());
        assertThat(permissions.size()).isEqualTo(2);
        assertTrue(permissions.stream().anyMatch(p -> p.ptype.equals("g") && p.v0.equals(userId) && p.v1.equals(Role.PROJECT_ADMIN.name())));
        List<CasbinRule> permissionsP2 = authorizer.getAllPermissions(domain, "project2");
        assertThat(permissionsP2.size()).isEqualTo(1);
    }

    public void test_role_hierarchy_with_g2() {
        authorizer.addProjectAdmin("alice", Domain.of("icij"), "project1");
        assertTrue(authorizer.can("alice", Domain.of("icij"), "project1", "PROJECT_MEMBER"));
    }


    public void test_get_all_permissions_instance_level() {
        // GIVEN: A user with INSTANCE-level access (pattern: *::*)
        String instUser = "alice";
        authorizer.addRoleForUserInInstance(instUser, Role.INSTANCE_ADMIN);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getAllPermissions(instUser);

        // THEN: Verify instance-level permission is retrieved
        assertFalse(permissions.isEmpty());
        assertTrue(permissions.stream()
                .anyMatch(p -> p.ptype.equals("g") && p.v0.equals(instUser)
                        && p.v1.equals(Role.INSTANCE_ADMIN.name())
                        && p.v2.equals("*::*")));
    }

    public void test_get_all_permissions_includes_domain_level() {
        // GIVEN: A user with DOMAIN-level access (pattern: domain::*)
        String domainUser = "bob";
        Domain domain = Domain.of("icij");

        authorizer.addRoleForUserInDomain(domainUser, Role.DOMAIN_ADMIN, domain);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getAllPermissions(domainUser);

        // THEN: Verify domain-level permission is retrieved
        assertFalse(permissions.isEmpty());
        assertTrue(permissions.stream()
                .anyMatch(p -> p.ptype.equals("g") && p.v0.equals(domainUser)
                        && p.v1.equals(Role.DOMAIN_ADMIN.name())
                        && p.v2.equals("icij::*")));
    }

    public void test_get_all_permissions_includes_project_level() {
        // GIVEN: A user with PROJECT-level access (pattern: domain::project)
        String projectUser = "charlie";
        Domain domain = Domain.of("datashare");
        String project = "myProject";

        authorizer.addRoleForUserInProject(projectUser, Role.PROJECT_ADMIN, domain, project);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getAllPermissions(projectUser);

        // THEN: Verify project-level permission is retrieved
        assertFalse(permissions.isEmpty());
        assertTrue(permissions.stream()
                .anyMatch(p -> p.ptype.equals("g") && p.v0.equals(projectUser)
                        && p.v1.equals(Role.PROJECT_ADMIN.name())
                        && p.v2.equals("datashare::myProject")));
    }

    public void test_get_all_permissions_includes_all_access_levels() {
        // GIVEN: A user with permissions at all levels (instance, domain, and project)
        String multiUser = "diana";
        Domain domain1 = Domain.of("icij");
        Domain domain2 = Domain.of("datashare");
        String project1 = "project1";
        String project2 = "project2";

        authorizer.addRoleForUserInInstance(multiUser, Role.INSTANCE_ADMIN);
        authorizer.addRoleForUserInDomain(multiUser, Role.DOMAIN_ADMIN, domain1);
        authorizer.addRoleForUserInProject(multiUser, Role.PROJECT_ADMIN, domain2, project1);
        authorizer.addRoleForUserInProject(multiUser, Role.PROJECT_MEMBER, domain1, project2);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getAllPermissions(multiUser);

        // THEN: Verify all permissions across all levels are retrieved
        assertThat(permissions.size()).isEqualTo(4);
        assertTrue(permissions.stream()
                .anyMatch(p -> p.v2.equals("*::*")));  // Instance level
        assertTrue(permissions.stream()
                .anyMatch(p -> p.v2.equals("icij::*")));  // Domain level
        assertTrue(permissions.stream()
                .anyMatch(p -> p.v2.equals("datashare::project1")));  // Project level
        assertTrue(permissions.stream()
                .anyMatch(p -> p.v2.equals("icij::project2")));  // Project level
    }

    public void test_get_all_permissions_for_user_with_no_permissions() {
        // GIVEN: A user with no permissions
        String noPermUser = "eve";

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getAllPermissions(noPermUser);

        // THEN: Verify that the list is empty
        assertTrue(permissions.isEmpty());
    }

    public void test_get_all_permissions_filters_by_user() {
        // GIVEN: Multiple users with different permissions
        String user1 = "frank";
        String user2 = "grace";
        Domain domain = Domain.of("shared");

        authorizer.addRoleForUserInInstance(user1, Role.INSTANCE_ADMIN);
        authorizer.addRoleForUserInDomain(user2, Role.DOMAIN_ADMIN, domain);

        // WHEN: Get all permissions for user1
        List<CasbinRule> user1Perms = authorizer.getAllPermissions(user1);

        // THEN: Verify only user1's permissions are returned, not user2's
        assertTrue(user1Perms.stream().allMatch(p -> p.v0.equals(user1)));
        assertFalse(user1Perms.stream().anyMatch(p -> p.v0.equals(user2)));
        assertThat(user1Perms.size()).isEqualTo(1);
    }

    public void test_get_all_permissions_includes_multiple_roles_for_same_domain() {
        // GIVEN: A user with multiple roles at the same domain/project level
        String multiRoleUser = "hank";
        Domain domain = Domain.of("test");
        String project = "testProj";

        authorizer.addRoleForUserInProject(multiRoleUser, Role.PROJECT_ADMIN, domain, project);
        authorizer.addRoleForUserInProject(multiRoleUser, Role.PROJECT_MEMBER, domain, project);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getAllPermissions(multiRoleUser);

        // THEN: Verify all role assignments are retrieved
        assertThat(permissions.size()).isGreaterThanOrEqualTo(2);
        assertTrue(permissions.stream()
                .filter(p -> p.v2.equals("test::testProj"))
                .count() >= 2);
    }
}