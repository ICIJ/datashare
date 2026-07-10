package org.icij.datashare.session;

import com.google.inject.Inject;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.text.Project;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciles casbin project roles with the projects granted by the identity
 * provider (the user's application groups) at each login. The identity provider
 * is the source of truth for project membership: projects newly granted get the
 * default member role, and roles held on projects no longer granted are revoked.
 * The role level on still-granted projects is left untouched (an admin may have
 * elevated it), as are instance and domain level roles.
 */
public class PostLoginEnroller {
    private static final String SEPARATOR = "::";
    private static final String PROJECT_PREFIX = Domain.DEFAULT.id() + SEPARATOR;
    private final Authorizer authorizer;

    @Inject
    public PostLoginEnroller(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    public void enroll(DatashareUser user) {
        Set<String> granted = user.getApplicationProjects().stream().map(Project::getId).collect(Collectors.toSet());
        Map<String, List<Role>> held = heldProjectRoles(user);

        held.forEach((projectId, roles) -> {
            if (!granted.contains(projectId)) {
                roles.forEach(role -> authorizer.deleteRoleForUserInProject(user, role, Domain.DEFAULT, new Project(projectId)));
            }
        });
        granted.stream().filter(projectId -> !held.containsKey(projectId)).forEach(projectId -> {
            if (!authorizer.can(user.id, Domain.DEFAULT, projectId, Role.PROJECT_VISITOR)) {
                authorizer.addRoleForUserInProject(user, Role.PROJECT_MEMBER, Domain.DEFAULT, new Project(projectId));
            }
        });
    }

    private Map<String, List<Role>> heldProjectRoles(DatashareUser user) {
        return authorizer.getGroupPermissions(user, Domain.DEFAULT).stream()
                .filter(rule -> rule.getV2().startsWith(PROJECT_PREFIX) && !rule.getV2().endsWith(SEPARATOR + "*"))
                .collect(Collectors.groupingBy(
                        rule -> rule.getV2().substring(PROJECT_PREFIX.length()),
                        Collectors.mapping(rule -> Role.valueOf(rule.getV1()), Collectors.toList())));
    }
}
