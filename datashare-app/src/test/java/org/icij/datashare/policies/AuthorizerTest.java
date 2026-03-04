package org.icij.datashare.policies;

import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.localUser;
import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

public class AuthorizerTest {
    private Authorizer authorizer;
    private Domain domain;
    private User user;
    private Project project;

    @Before
    public void setUp() throws Exception {
        CasbinRuleAdapter adapter = Mockito.mock(CasbinRuleAdapter.class);
        authorizer = new Authorizer(adapter);
        domain = Domain.of("test_domain");
        String userId = "test_user";
        user = new User(userId);
        project = project("test_project");
    }


    @Test
    public void test_can_with_role() {
        authorizer.addRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project);
        assertTrue(authorizer.can(user.id, domain, project.getId(), Role.PROJECT_ADMIN));
        assertTrue(authorizer.can(user.id, domain, project.getId(), Role.PROJECT_EDITOR));
    }

    @Test
    public void test_can_with_action_string() {
        authorizer.addRoleForUserInProject(user, Role.PROJECT_MEMBER, domain, project);
        assertTrue(authorizer.can(user.id, domain, project.getId(), "PROJECT_MEMBER"));
    }

    @Test
    public void test_add_role_for_user_in_instance() {
        assertTrue(authorizer.addRoleForUserInInstance(user, Role.INSTANCE_ADMIN));
    }

    @Test
    public void test_add_role_for_user_in_domain() {
        assertTrue(authorizer.addRoleForUserInDomain(user, Role.DOMAIN_ADMIN, domain));
    }

    @Test
    public void test_delete_role_for_user_in_domain() {
        authorizer.addRoleForUserInDomain(user, Role.DOMAIN_ADMIN, domain);
        assertTrue(authorizer.deleteRoleForUserInDomain(user, Role.DOMAIN_ADMIN, domain));
    }

    @Test
    public void test_update_role_for_user_in_domain() {
        authorizer.addRoleForUserInDomain(user, Role.DOMAIN_ADMIN, domain);
        assertTrue(authorizer.updateRoleForUserInDomain(user, Role.DOMAIN_ADMIN, domain));
    }

    @Test
    public void test_get_roles_for_user_in_domain() {
        List<String> roles = authorizer.getRolesForUserInDomain(user, domain);
        assertNotNull(roles);
    }

    @Test
    public void test_add_project_admin() {
        assertTrue(authorizer.addProjectAdmin(user, domain, project));
    }

    @Test
    public void test_add_role_for_user_in_project() {
        assertTrue(authorizer.addRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project));
    }

    @Test
    public void test_delete_role_for_user_in_project() {
        authorizer.addRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project);
        assertTrue(authorizer.deleteRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project));
    }

    @Test
    public void test_update_role_for_user_in_project() {
        authorizer.addRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project);
        assertTrue(authorizer.updateRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project));
    }

    @Test
    public void test_get_roles_for_user_in_project() {
        List<String> roles = authorizer.getRolesForUserInProject(user, domain, project);
        assertNotNull(roles);
    }

    @Test
    public void test_get_permissions_by_domain() {
        authorizer.addRoleForUserInDomain(user, Role.DOMAIN_ADMIN, domain);
        authorizer.addRoleForUserInProject(user, Role.DOMAIN_ADMIN, domain, project("p1"));
        List<CasbinRule> permissions = authorizer.getGroupPermissions(domain);
        assertFalse(permissions.isEmpty());
        assertTrue(permissions.stream().anyMatch(p -> p.ptype.equals("g") && p.v0.equals(user.id) && p.v1.equals(Role.DOMAIN_ADMIN.name())));
        assertThat(permissions.size()).isEqualTo(2);
    }

    @Test
    public void test_get_permissions_by_project() {
        authorizer.addRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project);
        authorizer.addRoleForUserInProject(new User("other_user"), Role.PROJECT_ADMIN, domain, project);
        authorizer.addRoleForUserInProject(user, Role.PROJECT_ADMIN, domain, project("project2"));
        authorizer.addRoleForUserInProject(user, Role.PROJECT_ADMIN, Domain.of("other"), project);
        List<CasbinRule> permissions = authorizer.getGroupPermissions(domain, project.getId());
        assertFalse(permissions.isEmpty());
        assertThat(permissions.size()).isEqualTo(2);
        assertTrue(permissions.stream().anyMatch(p -> p.ptype.equals("g") && p.v0.equals(user.id) && p.v1.equals(Role.PROJECT_ADMIN.name())));
        List<CasbinRule> permissionsP2 = authorizer.getGroupPermissions(domain, "project2");
        assertThat(permissionsP2.size()).isEqualTo(1);
    }

    @Test
    public void test_role_hierarchy_with_g2() {
        authorizer.addProjectAdmin(new User("alice"), Domain.of("icij"), project("project1"));
        assertTrue(authorizer.can("alice", Domain.of("icij"), "project1", "PROJECT_MEMBER"));
    }

    @Test
    public void test_get_all_permissions_instance_level() {
        // GIVEN: A user with INSTANCE-level access (pattern: *::*)
        User instUser = localUser("alice");
        authorizer.addRoleForUserInInstance(instUser, Role.INSTANCE_ADMIN);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getGroupPermissions(instUser);

        // THEN: Verify instance-level permission is retrieved
        assertFalse(permissions.isEmpty());
        assertTrue(permissions.stream()
                .anyMatch(p -> p.ptype.equals("g") && p.v0.equals(instUser.getId())
                        && p.v1.equals(Role.INSTANCE_ADMIN.name())
                        && p.v2.equals("*::*")));
    }

    @Test
    public void test_get_all_permissions_includes_domain_level() {
        // GIVEN: A user with DOMAIN-level access (pattern: domain::*)
        User domainUser = localUser("bob");
        Domain domain = Domain.of("icij");

        authorizer.addRoleForUserInDomain(domainUser, Role.DOMAIN_ADMIN, domain);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getGroupPermissions(domainUser);

        // THEN: Verify domain-level permission is retrieved
        assertFalse(permissions.isEmpty());
        assertTrue(permissions.stream()
                .anyMatch(p -> p.ptype.equals("g") && p.v0.equals(domainUser.getId())
                        && p.v1.equals(Role.DOMAIN_ADMIN.name())
                        && p.v2.equals("icij::*")));
    }

    @Test
    public void test_get_all_permissions_includes_project_level() {
        // GIVEN: A user with PROJECT-level access (pattern: domain::project)
        User projectUser = localUser("charlie");
        Domain domain = Domain.of("datashare");
        Project project = project("myProject");

        authorizer.addRoleForUserInProject(projectUser, Role.PROJECT_ADMIN, domain, project);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getGroupPermissions(projectUser);

        // THEN: Verify project-level permission is retrieved
        assertFalse(permissions.isEmpty());
        assertTrue(permissions.stream()
                .anyMatch(p -> p.ptype.equals("g") && p.v0.equals(projectUser.getId())
                        && p.v1.equals(Role.PROJECT_ADMIN.name())
                        && p.v2.equals("datashare::myProject")));
    }

    @Test
    public void test_get_all_permissions_includes_all_access_levels() {
        // GIVEN: A user with permissions at all levels (instance, domain, and project)
        User multiUser = localUser("diana");
        Domain domain1 = Domain.of("icij");
        Domain domain2 = Domain.of("datashare");
        Project project1 = project("project1");
        Project project2 = project("project2");

        authorizer.addRoleForUserInInstance(multiUser, Role.INSTANCE_ADMIN);
        authorizer.addRoleForUserInDomain(multiUser, Role.DOMAIN_ADMIN, domain1);
        authorizer.addRoleForUserInProject(multiUser, Role.PROJECT_ADMIN, domain2, project1);
        authorizer.addRoleForUserInProject(multiUser, Role.PROJECT_MEMBER, domain1, project2);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getGroupPermissions(multiUser);

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

    @Test
    public void test_get_all_permissions_for_user_with_no_permissions() {
        // GIVEN: A user with no permissions
        User noPermUser = localUser("eve");

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getGroupPermissions(noPermUser);

        // THEN: Verify that the list is empty
        assertTrue(permissions.isEmpty());
    }

    @Test
    public void test_get_all_permissions_filters_by_user() {
        // GIVEN: Multiple users with different permissions
        User user1 = localUser("frank");
        User user2 = localUser("grace");
        Domain domain = Domain.of("shared");

        authorizer.addRoleForUserInInstance(user1, Role.INSTANCE_ADMIN);
        authorizer.addRoleForUserInDomain(user2, Role.DOMAIN_ADMIN, domain);

        // WHEN: Get all permissions for user1
        List<CasbinRule> user1Perms = authorizer.getGroupPermissions(user1);

        // THEN: Verify only user1's permissions are returned, not user2's
        assertTrue(user1Perms.stream().allMatch(p -> p.v0.equals(user1.getId())));
        assertFalse(user1Perms.stream().anyMatch(p -> p.v0.equals(user2.getId())));
        assertThat(user1Perms.size()).isEqualTo(1);
    }

    @Test
    public void test_get_all_permissions_includes_multiple_roles_for_same_domain() {
        // GIVEN: A user with multiple roles at the same domain/project level
        User multiRoleUser = localUser("hank");
        Domain domain = Domain.of("test");
        Project project = project("testProj");

        authorizer.addRoleForUserInProject(multiRoleUser, Role.PROJECT_ADMIN, domain, project);
        authorizer.addRoleForUserInProject(multiRoleUser, Role.PROJECT_MEMBER, domain, project);

        // WHEN: Get all permissions for the user
        List<CasbinRule> permissions = authorizer.getGroupPermissions(multiRoleUser);

        // THEN: Verify all role assignments are retrieved
        assertThat(permissions.size()).isGreaterThanOrEqualTo(2);
        assertTrue(permissions.stream()
                .filter(p -> p.v2.equals("test::testProj"))
                .count() >= 2);
    }

    @Test
    public void test_require_value() {
        assertEquals("value", Authorizer.requireValue("value", false));
        assertEquals("*", Authorizer.requireValue("*", true));

        try {
            Authorizer.requireValue(null, true);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("The parameter cannot be null or blank", e.getMessage());
        }

        try {
            Authorizer.requireValue("", true);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("The parameter cannot be null or blank", e.getMessage());
        }

        try {
            Authorizer.requireValue(" ", true);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("The parameter cannot be null or blank", e.getMessage());
        }

        try {
            Authorizer.requireValue("*", false);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("The parameter cannot be a wildcard", e.getMessage());
        }
    }

    @Test
    public void test_require_role() {
        assertEquals(Role.PROJECT_ADMIN, Authorizer.requireRole("PROJECT_ADMIN"));
        try {
            Authorizer.requireRole("INVALID_ROLE");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Invalid role value:INVALID_ROLE"));
        }
    }

    @Test(expected = UnauthorizedException.class)
    public void test_require_current_user() {
        Context context = Mockito.mock(Context.class);
        DatashareUser user = new DatashareUser("alice");
        when(context.currentUser()).thenReturn(user);

        assertEquals(user, Authorizer.requireCurrentUser(context));

        when(context.currentUser()).thenReturn(null);
        Authorizer.requireCurrentUser(context);
        fail("Expected UnauthorizedException");
    }

    @Test
    public void test_require_domain() {
        Domain d = Authorizer.requireDomain("icij", false);
        assertEquals("icij", d.id());

        Domain wildcard = Authorizer.requireDomain("*", true);
        assertEquals("*", wildcard.id());

        try {
            Authorizer.requireDomain("*", false);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("The parameter cannot be a wildcard", e.getMessage());
        }
    }

    @Test
    public void test_require_id_param() {
        Context context = Mockito.mock(Context.class);
        when(context.pathParam("id")).thenReturn("123");

        assertEquals("123", Authorizer.requireIdParam(context, "id"));

        when(context.pathParam("id")).thenReturn(null);
        try {
            Authorizer.requireIdParam(context, "id");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals("The parameter cannot be null or blank", e.getMessage());
        }
    }

}